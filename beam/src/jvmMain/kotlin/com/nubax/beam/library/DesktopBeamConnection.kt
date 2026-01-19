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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class DesktopBeamConnection : BeamConnection {

    private val tcpPort = 9999
    private val udpPort = 8888

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var discoveryJob: Job? = null
    private var listeningJob: Job? = null

    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null

    private val _incomingData = MutableSharedFlow<ByteArray>()
    override val incomingData = _incomingData.asSharedFlow()

    private fun computeHmac(nonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(BeamSecurity.APP_SECRET.toByteArray(), "HmacSHA256"))
        return mac.doFinal(nonce)
    }

    private fun verifyHmac(nonce: ByteArray, received: ByteArray): Boolean {
        return computeHmac(nonce).contentEquals(received)
    }

    override fun startDiscovery() {
        if (discoveryJob != null) return

        Log.i("Iniciando discovery UDP")
        discoveryJob = scope.launch {
            DatagramSocket(udpPort).use { socket ->
                socket.soTimeout = 1000
                val buffer = ByteArray(1024)

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)

                        val msg = String(packet.data, 0, packet.length)
                        Log.i("Paquete UDP recibido: $msg")
                        if (msg == "BEAM_QUERY_DESKTOP") {
                            val reply = "DESKTOP_HERE".toByteArray()
                            socket.send(
                                DatagramPacket(reply, reply.size, packet.address, packet.port)
                            )
                            Log.i("Respuesta UDP enviada al cliente")
                        }
                    } catch (_: SocketTimeoutException) {
                        // ignora timeout para check isActive
                    }
                }
            }
        }
    }

    override fun stopDiscovery() {
        Log.i("Deteniendo discovery UDP")
        discoveryJob?.cancel()
        discoveryJob = null
    }
    override suspend fun startPairing(
        ownToken: String,
        targetToken: String?
    ): BeamResult<BeamState> = withContext(Dispatchers.IO) {
        Log.i("Esperando conexión TCP de Android...")

        try {
            serverSocket = ServerSocket(tcpPort).apply {
                soTimeout = 15000
                reuseAddress = true
            }

            val client = try {
                serverSocket!!.accept()
            } catch (e: SocketTimeoutException) {
                Log.e("No se recibió intento de conexión")
                close()
                return@withContext BeamResult.Failure("Nadie intentó conectar")
            }

            Log.i("Cliente conectado desde ${client.inetAddress.hostAddress}")
            activeSocket = client
            val input = client.getInputStream()
            val out = client.getOutputStream()

            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            Log.i("Generando nonce para challenge HMAC")
            out.write(nonce)
            out.flush()
            Log.i("Nonce enviado: ${nonce.joinToString { "%02X".format(it) }}")

            val hmac = ByteArray(32) // HMAC-SHA256 = 32 bytes
            input.read(hmac)
            Log.i("HMAC recibido del cliente")

            if (!verifyHmac(nonce, hmac)) {
                client.close()
                Log.e("HMAC inválido, cerrando conexión")
                return@withContext BeamResult.Failure("HMAC inválido, app no autorizada")
            }
            Log.i("HMAC verificado correctamente")

            val request = BeamProtocol.receiveObject<PairingRequest>(input)
            Log.i("PairingRequest recibida: $request")

            if (request.targetDesktopToken != ownToken) {
                client.close()
                Log.e("Token incorrecto, cerrando conexión")
                return@withContext BeamResult.Failure("Token incorrecto")
            }

            val myKeyPair = BeamSecurity.generateKeyPair()
            Log.i("Clave ECDH generada")

            BeamSecurity.computeSharedSecret(myKeyPair.private, request.androidPublicKey)
            Log.i("Secreto compartido calculado correctamente")

            BeamProtocol.sendObject(
                out,
                PairingResponse(
                    success = true,
                    message = "OK",
                    desktopPublicKey = myKeyPair.public.encoded
                )
            )
            Log.i("PairingResponse enviada")

            startListeningLoop(input)
            Log.i("Bucle de escucha iniciado")

            BeamResult.Success(BeamState.Connected(request.androidToken))

        } catch (e: Exception) {
            Log.e("Error en pairing: ${e.message}")
            BeamResult.Failure(e.message ?: "Error pairing")
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
            Log.i("Enviando datos de ${data.size} bytes")
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
        stopDiscovery()
        listeningJob?.cancel()
        activeSocket?.close()
        serverSocket?.close()
        BeamSecurity.clearSession()
    }
}