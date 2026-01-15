package com.nubax.beam.library

import com.nubax.beam.library.connection.BeamConnection
import com.nubax.beam.library.core.BeamProtocol
import com.nubax.beam.library.core.BeamSecurity
import com.nubax.beam.library.core.Log
import com.nubax.beam.library.sdk.BeamResult
import com.nubax.beam.library.sdk.BeamState
import com.nubax.beam.library.sdk.PairingRequest
import com.nubax.beam.library.sdk.PairingResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

class AndroidBeamConnection : BeamConnection {
    private val tcpPort = 9999
    private val udpDiscoveryPort = 8888
    private var activeSocket: Socket? = null
    private var listeningJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val timeout = 15000

    private val _incomingData = MutableSharedFlow<ByteArray>()
    override val incomingData = _incomingData.asSharedFlow()

    override suspend fun startPairing(ownToken: String, targetToken: String?): BeamResult<BeamState> = withContext(Dispatchers.IO) {
        try {
            // 1. Descubrimiento UDP
            val desktopIp = discoverDesktopDevice(targetToken!!)
                ?: return@withContext BeamResult.Failure("No se encontró el Desktop con token: $targetToken")

            val socket = Socket(desktopIp, tcpPort)
            activeSocket = socket
            Log.i("Socket TCP entre dispositivos creado!\nGenerando clave privada...")

            // 2. Seguridad: Generar par de claves locales (Pública/Privada)
            val myKeyPair = BeamSecurity.generateKeyPair()
            Log.i("Clave privada generada: ${myKeyPair.private}")

            Log.i("Enviando tokens y clave pública.")
            // 3. Handshake: Enviamos tokens y NUESTRA clave pública
            BeamProtocol.sendObject(socket.getOutputStream(), PairingRequest(
                androidToken = ownToken,
                targetDesktopToken = targetToken,
                androidPublicKey = myKeyPair.public.encoded
            ))

            Log.i("Esperando respuesta del host con la clave pública...")
            // 4. Recibir respuesta con la clave pública del Desktop
            val response = BeamProtocol.receiveObject<PairingResponse>(socket.getInputStream())

            Log.i("Respuesta recibida: ${response.message}")
            if (response.success && response.desktopPublicKey != null) {
                // 5. CALCULAR SECRETO COMPARTIDO (ECDH)
                // A partir de aquí, BeamSecurity ya tiene la sessionKey lista
                Log.i("Clave pública del Desktop recibida: ${response.desktopPublicKey.decodeToString()}")
                BeamSecurity.computeSharedSecret(myKeyPair.private, response.desktopPublicKey)
                Log.i("Clave compartida calculada.")
                startListeningLoop(socket.getInputStream())
                Log.i("Conexión segura establecida!")
                BeamResult.Success(BeamState.Connected(deviceToken = targetToken))
            } else {
                socket.close()
                BeamResult.Failure("El Desktop rechazó la conexión o no envió clave: ${response.message}")
            }
        } catch (e: Exception) {
            BeamResult.Failure("Error de conexión: ${e.message}")
        }
    }

    private suspend fun discoverDesktopDevice(targetToken: String): String? = withContext(Dispatchers.IO) {
        val udpSocket = DatagramSocket().apply {
            broadcast = true
            soTimeout = timeout
        }
        Log.i("Socket UDP creado con timeout: ${udpSocket.soTimeout} ms")
        try {
            val query = "BEAM_QUERY_DESKTOP:$targetToken".toByteArray()
            val packet = DatagramPacket(query, query.size, InetAddress.getByName("255.255.255.255"), udpDiscoveryPort)
            udpSocket.send(packet)
            Log.i("Enviado paquete de descubrimiento...")
            val receiveBuffer = ByteArray(1024)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            udpSocket.receive(receivePacket)
            Log.i("Recibido paquete de respuesta del descubrimiento.")
            val response = String(receivePacket.data, 0, receivePacket.length)
            if (response == "DESKTOP_HERE:$targetToken") {
                val hostAddress = receivePacket.address.hostAddress
                Log.i("Host descubierto! Host address: $hostAddress")
                return@withContext hostAddress
            }
            null
        } catch (e: Exception) {
            Log.i("Error de descubrimiento: ${e.message}")
            null
        } finally {
            Log.i("Cerrando socket UDP")
            udpSocket.close()
        }
    }

    private fun startListeningLoop(input: InputStream) {
        listeningJob?.cancel()
        Log.i("Iniciando bucle de escucha")
        listeningJob = scope.launch {
            try {
                while (isActive) {
                    Log.i("Esperando datos en socket")
                    val data = BeamProtocol.receiveRaw(input)
                    Log.i("Datos recibidos.")
                    _incomingData.emit(data)
                }
            } catch (e: Exception) {
                Log.i("Socket cerrado o error de desencriptación: ${e.message}")
            }
        }
    }

    override suspend fun sendRawData(data: ByteArray): BeamResult<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i("Enviando datos...")
            val out = activeSocket?.getOutputStream() ?: return@withContext BeamResult.Failure("Sin conexión")
            BeamProtocol.sendRaw(out, data)
            Log.i("Datos enviados")
            BeamResult.Success(Unit)
        } catch (e: Exception) { BeamResult.Failure(e.message ?: "Error enviando datos.SendDataRaw.") }
    }

    override fun close() {
        listeningJob?.cancel()
        activeSocket?.close()
        BeamSecurity.clearSession()
    }
}