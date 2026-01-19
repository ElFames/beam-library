package com.nubax.beam.library

import com.nubax.beam.library.connection.BeamConnection
import com.nubax.beam.library.core.BeamProtocol
import com.nubax.beam.library.core.BeamSecurity
import com.nubax.beam.library.core.Log
import com.nubax.beam.library.sdk.models.BeamResult
import com.nubax.beam.library.sdk.models.BeamState
import com.nubax.beam.library.sdk.models.PairingRequest
import com.nubax.beam.library.sdk.models.PairingResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class AndroidBeamConnection : BeamConnection {

    private val tcpPort = 9999
    private val udpPort = 8888
    private val timeout = 2000

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listeningJob: Job? = null
    private var activeSocket: Socket? = null

    private val _incomingData = MutableSharedFlow<ByteArray>()
    override val incomingData = _incomingData.asSharedFlow()

    private fun computeHmac(nonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(BeamSecurity.APP_SECRET.toByteArray(), "HmacSHA256"))
        return mac.doFinal(nonce)
    }

    override fun startDiscovery() {
        Log.i("Discovery: Android no anuncia")
    }

    override fun stopDiscovery() {
        Log.i("Discovery detenido (Android)")
    }
    override suspend fun startPairing(
        ownToken: String,
        targetToken: String?
    ): BeamResult<BeamState> = withContext(Dispatchers.IO) {

        Log.i("Iniciando pairing con token: $targetToken")
        val desktopIp = discoverWithRetries()
            ?: return@withContext BeamResult.Failure("Desktop no encontrado")
        Log.i("Desktop encontrado en IP: $desktopIp")

        try {
            val socket = Socket(desktopIp, tcpPort)
            activeSocket = socket
            Log.i("Socket TCP conectado a $desktopIp:$tcpPort")

            val input = socket.getInputStream()
            val out = socket.getOutputStream()

            val nonce = ByteArray(16)
            input.read(nonce)
            Log.i("Nonce recibido: ${nonce.joinToString { "%02X".format(it) }}")

            val hmac = computeHmac(nonce)
            out.write(hmac)
            out.flush()
            Log.i("HMAC enviado para autenticación")

            val myKeyPair = BeamSecurity.generateKeyPair()
            Log.i("Clave ECDH generada")

            BeamProtocol.sendObject(
                out,
                PairingRequest(
                    androidToken = ownToken,
                    targetDesktopToken = targetToken!!,
                    androidPublicKey = myKeyPair.public.encoded
                )
            )
            Log.i("PairingRequest enviada")

            val response = BeamProtocol.receiveObject<PairingResponse>(input)
            Log.i("PairingResponse recibida: $response")

            if (!response.success) {
                socket.close()
                Log.e("Pairing fallido: ${response.message}")
                return@withContext BeamResult.Failure(response.message)
            }

            BeamSecurity.computeSharedSecret(myKeyPair.private, response.desktopPublicKey!!)
            Log.i("Secreto compartido calculado correctamente")

            startListeningLoop(input)
            Log.i("Bucle de escucha iniciado")

            BeamResult.Success(BeamState.Connected(targetToken))

        } catch (e: Exception) {
            Log.e("Error de conexión: ${e.message}")
            BeamResult.Failure(e.message ?: "Error conexión")
        }
    }


    private suspend fun discoverWithRetries(): String? {
        Log.i("Iniciando descubrimiento con reintentos")
        repeat(60) { // ~2 minutos
            discoverOnce()?.let {
                Log.i("Desktop encontrado durante descubrimiento: $it")
                return it
            }
            delay(2000)
            Log.i("Reintentando descubrimiento...")
        }
        Log.e("No se encontró el Desktop después de varios intentos")
        return null
    }

    private suspend fun discoverOnce(): String? =
        withContext(Dispatchers.IO) {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = timeout

                val query = "BEAM_QUERY_DESKTOP".toByteArray()
                socket.send(
                    DatagramPacket(
                        query,
                        query.size,
                        InetAddress.getByName("255.255.255.255"),
                        udpPort
                    )
                )
                Log.i("Paquete de descubrimiento UDP enviado")

                return@withContext try {
                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    val msg = String(packet.data, 0, packet.length)
                    Log.i("Respuesta UDP recibida: $msg")
                    if (msg == "DESKTOP_HERE") {
                        packet.address.hostAddress
                    } else null
                } catch (e: Exception) {
                    Log.e("Error en discoverOnce: ${e.message}")
                    null
                }
            }
        }

    private fun startListeningLoop(input: InputStream) {
        listeningJob?.cancel()
        listeningJob = scope.launch {
            Log.i("Iniciando bucle de escucha de datos entrantes")
            while (isActive) {
                try {
                    val data = BeamProtocol.receiveRaw(input)
                    Log.i("Datos recibidos: ${data.size} bytes")
                    _incomingData.emit(data)
                } catch (e: Exception) {
                    Log.e("Error en bucle de escucha: ${e.message}")
                }
            }
        }
    }

    override suspend fun sendRawData(data: ByteArray): BeamResult<Unit> {
        return try {
            Log.i("Enviando datos de ${data.size} bytes...")
            BeamProtocol.sendRaw(withContext(Dispatchers.IO) {
                activeSocket!!.getOutputStream()
            }, data)
            Log.i("Datos enviados correctamente")
            BeamResult.Success(Unit)
        } catch (e: Exception) {
            Log.e("Error enviando datos: ${e.message}")
            BeamResult.Failure(e.message ?: "Error envío")
        }
    }

    override fun close() {
        Log.i("Cerrando conexión y limpiando recursos")
        listeningJob?.cancel()
        activeSocket?.close()
        BeamSecurity.clearSession()
    }
}