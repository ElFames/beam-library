package com.nubax.beam.library

import com.nubax.beam.library.connection.MeshBeamConnection
import com.nubax.beam.library.core.BeamStorage
import com.nubax.beam.library.sdk.BeamApplication
import com.nubax.beam.library.sdk.models.MediaMessage
import com.nubax.beam.library.sdk.models.onFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class InMemoryStorage : BeamStorage {
    private val map = mutableMapOf<String, String>()
    override fun readString(key: String): String? = map[key]
    override fun writeString(key: String, value: String) { map[key] = value }
}

/**
 * Prueba de extremo a extremo dentro de un mismo proceso JVM: dos "dispositivos"
 * (identidad, trust store y transporte totalmente independientes) se conectan por
 * loopback, pasan por el handshake con firma+ECDH, confirman el pairing por primera
 * vez (como haría un usuario tras comparar el código en pantalla) y se mandan una
 * imagen de verdad. Sirve para verificar el diseño sin depender de dos dispositivos
 * físicos ni de que el broadcast UDP funcione en este entorno.
 */
class MeshBeamConnectionTest {

    @Test
    fun `dos peers se emparejan y se pasan una imagen byte a byte`() = runBlocking {
        val appA = BeamApplication(InMemoryStorage(), MeshBeamConnection(beaconPort = 18881, tcpPort = 19991))
        val appB = BeamApplication(InMemoryStorage(), MeshBeamConnection(beaconPort = 18882, tcpPort = 19992))

        val scope = CoroutineScope(Dispatchers.Default)
        val autoConfirmA = scope.launch { appA.pairingRequests.collect { appA.confirmPairing(it.peerId) } }
        val autoConfirmB = scope.launch { appB.pairingRequests.collect { appB.confirmPairing(it.peerId) } }

        try {
            appA.start("DeviceA")
            appB.start("DeviceB")

            appB.connectTo("127.0.0.1", 19991).onFailure {
                throw AssertionError("No se pudo conectar B->A: $it")
            }

            withTimeout(10_000) {
                appA.connectedPeers.first { it.isNotEmpty() }
                appB.connectedPeers.first { it.isNotEmpty() }
            }
            assertEquals(listOf(appB.deviceId), appA.connectedPeers.value)
            assertEquals(listOf(appA.deviceId), appB.connectedPeers.value)

            val originalBytes = ByteArray(200_000) { (it % 256).toByte() } // simula una foto pequeña
            val sent = MediaMessage(
                id = "media-1",
                name = "valencia.jpg",
                description = "Foto de prueba",
                mimeType = "image/jpeg",
                bytes = originalBytes
            )

            var lastProgress = 0f
            val sendResult = appA.send(appB.deviceId, sent, MediaMessage.serializer()) { progress ->
                lastProgress = progress
            }
            assertTrue(sendResult is com.nubax.beam.library.sdk.models.BeamResult.Success, "Envío falló: $sendResult")
            assertEquals(1f, lastProgress, "El progreso debería terminar en 100%")

            val received = withTimeout(10_000) {
                appB.observeIncoming(MediaMessage.serializer()).first().second
            }

            assertEquals(sent.id, received.id)
            assertEquals(sent.name, received.name)
            assertEquals(sent.description, received.description)
            assertEquals(sent.mimeType, received.mimeType)
            assertTrue(originalBytes.contentEquals(received.bytes), "Los bytes recibidos no coinciden con los enviados")
        } finally {
            autoConfirmA.cancel()
            autoConfirmB.cancel()
            appA.shutdown()
            appB.shutdown()
            delay(200) // deja que los sockets se cierren limpiamente antes de terminar
        }
    }
}
