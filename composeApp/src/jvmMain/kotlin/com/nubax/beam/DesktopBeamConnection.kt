package com.nubax.beam

import com.nubax.beam.library.ble.BeamConnection
import com.nubax.beam.library.ble.PairingRequest
import com.nubax.beam.library.ble.PairingResponse
import com.nubax.beam.library.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket

class DesktopBeamConnection : BeamConnection {
    private val tcpPort = 9999
    private val udpPort = 8888
    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var discoveryJob: Job? = null
    private var listeningJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val timeout = 15000

    private val _incomingData = MutableSharedFlow<ByteArray>()
    override val incomingData = _incomingData.asSharedFlow()

    override suspend fun startPairing(
        ownToken: String,
        targetToken: String?
    ): BleResult<BeamState> = withContext(Dispatchers.IO) {
        try {
            Log.i("Iniciando emparejamiento...")
            startDiscoveryAnnouncement(ownToken)

            serverSocket = ServerSocket(tcpPort).apply { reuseAddress = true }
            val client = serverSocket!!.accept()
            activeSocket = client
            stopDiscoveryAnnouncement()

            val input = client.getInputStream()
            val out = client.getOutputStream()

            Log.i("Socket TCP entre dispositivos creado! Esperando Pairing request")
            // 1. Recibe petición (tokens + clave pública de Android)
            val request = BeamProtocol.receiveObject<PairingRequest>(input)
            Log.i("Recibida petición de emparejamiento: $request")

            // 2. Validar Token
            val isCorrect = request.targetDesktopToken == ownToken

            if (isCorrect) {
                Log.i("Token correcto! Generando clave privada...")
                // 3. Seguridad: Generar par de claves propio
                val myKeyPair = BeamSecurity.generateKeyPair()
                Log.i("Clave privada generada: ${myKeyPair.private}")

                // 4. Calcular Secreto Compartido (ECDH)
                BeamSecurity.computeSharedSecret(myKeyPair.private, request.androidPublicKey)
                Log.i("Secreto compartido calculado.")

                // 5. Responder con éxito y nuestra clave pública
                val response = PairingResponse(
                    success = true,
                    message = "Token validado y canal seguro establecido",
                    desktopPublicKey = myKeyPair.public.encoded
                )
                BeamProtocol.sendObject(out, response)
                Log.i("Respuesta enviada.")

                startListeningLoop(input)

                Log.i("Conexión establecida!")
                BleResult.Success(BeamState.Connected(deviceToken = request.androidToken))
            } else {
                Log.i("Token incorrecto!")
                val response = PairingResponse(
                    success = false,
                    message = "Token incorrecto",
                    desktopPublicKey = null
                )
                BeamProtocol.sendObject(out, response)
                client.close()
                Log.i("Socket TCP cerrado.")
                BleResult.Failure("Fallo de validación")
            }
        } catch (e: Exception) {
            BleResult.Failure(e.message ?: "Error")
        }
    }

    private fun startDiscoveryAnnouncement(myToken: String) {
        discoveryJob = scope.launch(Dispatchers.IO) {
            val udpSocket = DatagramSocket(udpPort)
            udpSocket.soTimeout = timeout
            val buffer = ByteArray(1024)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                udpSocket.receive(packet)
                Log.i("Recibido paquete de descubrimiento.")
                val msg = String(packet.data, 0, packet.length)

                if (msg == "BEAM_QUERY_DESKTOP:$myToken") {
                    Log.i("Enviando respuesta de descubrimiento...")
                    val reply = "DESKTOP_HERE:$myToken".toByteArray()
                    udpSocket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    Log.i("Respuesta enviada.")
                }
            }
            Log.i("Cerrando socket UDP.")
            udpSocket.close()
        }
    }

    private fun startListeningLoop(input: InputStream) {
        listeningJob?.cancel()
        Log.i("Iniciando bucle de escucha...")
        listeningJob = scope.launch {
            try {
                while (isActive) {
                    val data = BeamProtocol.receiveRaw(input)
                    Log.i("Datos recibidos.")
                    _incomingData.emit(data)
                }
            } catch (e: Exception) {
                Log.i("Socket cerrado o error de desencriptación. Error: ${e.message}")
            }
        }
    }

    override suspend fun sendRawData(data: ByteArray): BleResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val out = activeSocket?.getOutputStream()
                    ?: return@withContext BleResult.Failure("No hay socket")
                Log.i("Enviando datos..")
                BeamProtocol.sendRaw(out, data)
                Log.i("Datos enviados")
                BleResult.Success(Unit)
            } catch (e: Exception) {
                BleResult.Failure(e.message ?: "Error")
            }
        }

    override fun close() {
        discoveryJob?.cancel()
        listeningJob?.cancel()
        activeSocket?.close()
        serverSocket?.close()
        BeamSecurity.clearSession()
    }

    private fun stopDiscoveryAnnouncement() {
        discoveryJob?.cancel()
    }
}