package com.nubax.beam.library

import com.nubax.beam.library.connection.MeshBeamConnection
import com.nubax.beam.library.core.BeamStorage
import com.nubax.beam.library.core.PeerKind
import com.nubax.beam.library.sdk.BeamApplication
import com.nubax.beam.library.sdk.models.BeamResult
import com.nubax.beam.library.sdk.models.MediaMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
 * (identidad, historial y transporte totalmente independientes) se conectan por
 * loopback, pasan por el handshake con firma+ECDH, se emparejan con el flujo de
 * código de Desktop (como haría Android leyendo el código en la pantalla de
 * Desktop) y se mandan una imagen de verdad. Sirve para verificar el diseño sin
 * depender de dos dispositivos físicos ni de que el broadcast UDP funcione en
 * este entorno.
 */
class MeshBeamConnectionTest {

    @Test
    fun `dos peers se emparejan por codigo y se pasan una imagen byte a byte`() = runBlocking {
        val desktop = BeamApplication(InMemoryStorage(), MeshBeamConnection(beaconPort = 18881, tcpPort = 19991))
        val android = BeamApplication(InMemoryStorage(), MeshBeamConnection(beaconPort = 18882, tcpPort = 19992))

        try {
            desktop.start("DeviceDesktop", PeerKind.DESKTOP)
            android.start("DeviceAndroid", PeerKind.ANDROID)

            val code = withTimeout(5_000) { desktop.pairingCode.filterNotNull().first() }
            val pairResult = android.pairDesktopWithCode("127.0.0.1", 19991, code)
            assertTrue(pairResult is BeamResult.Success, "Emparejamiento falló: $pairResult")

            withTimeout(10_000) {
                desktop.connectedPeers.first { it.isNotEmpty() }
                android.connectedPeers.first { it.isNotEmpty() }
            }
            assertEquals(listOf(android.deviceId), desktop.connectedPeers.value)
            assertEquals(listOf(desktop.deviceId), android.connectedPeers.value)

            val originalBytes = ByteArray(200_000) { (it % 256).toByte() } // simula una foto pequeña
            val sent = MediaMessage(
                id = "media-1",
                name = "valencia.jpg",
                description = "Foto de prueba",
                mimeType = "image/jpeg",
                bytes = originalBytes
            )

            var lastProgress = 0f
            val sendResult = desktop.send(android.deviceId, sent, MediaMessage.serializer()) { progress ->
                lastProgress = progress
            }
            assertTrue(sendResult is BeamResult.Success, "Envío falló: $sendResult")
            assertEquals(1f, lastProgress, "El progreso debería terminar en 100%")

            val received = withTimeout(10_000) {
                android.observeIncoming(MediaMessage.serializer()).first().second
            }

            assertEquals(sent.id, received.id)
            assertEquals(sent.name, received.name)
            assertEquals(sent.description, received.description)
            assertEquals(sent.mimeType, received.mimeType)
            assertTrue(originalBytes.contentEquals(received.bytes), "Los bytes recibidos no coinciden con los enviados")
        } finally {
            desktop.shutdown()
            android.shutdown()
            delay(200) // deja que los sockets se cierren limpiamente antes de terminar
        }
    }
}
