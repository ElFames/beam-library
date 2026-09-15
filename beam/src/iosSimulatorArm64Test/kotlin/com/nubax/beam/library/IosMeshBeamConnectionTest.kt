package com.nubax.beam.library

import com.nubax.beam.library.connection.IosMeshBeamConnection
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
    override fun writeString(key: String, value: String) {
        map[key] = value
    }
}

/**
 * Mismo test end-to-end que `MeshBeamConnectionTest.kt` (jvmTest), pero contra el
 * transporte real de iOS (`IosMeshBeamConnection`, sockets BSD + BeamCryptoKit/CryptoKit)
 * en vez del de la JVM — dos "dispositivos" en el mismo proceso, conectados por loopback,
 * que completan el handshake criptográfico, se emparejan por código, y se pasan una
 * imagen byte a byte. Verifica que el actual de iOS completo (Fase C) funciona de
 * extremo a extremo, no solo pieza por pieza.
 */
class IosMeshBeamConnectionTest {

    @Test
    fun `dos peers iOS se emparejan por codigo y se pasan una imagen byte a byte`() = runBlocking {
        val desktop = BeamApplication(InMemoryStorage(), IosMeshBeamConnection(beaconPort = 28881, tcpPort = 29991))
        val mobile = BeamApplication(InMemoryStorage(), IosMeshBeamConnection(beaconPort = 28882, tcpPort = 29992))

        try {
            desktop.start("DeviceDesktop", PeerKind.DESKTOP)
            mobile.start("DeviceMobile", PeerKind.MOBILE)

            val code = withTimeout(5_000) { desktop.pairingCode.filterNotNull().first() }
            val pairResult = mobile.pairDesktopWithCode("127.0.0.1", 29991, code)
            assertTrue(pairResult is BeamResult.Success, "Emparejamiento falló: $pairResult")

            withTimeout(10_000) {
                desktop.connectedPeers.first { it.isNotEmpty() }
                mobile.connectedPeers.first { it.isNotEmpty() }
            }
            assertEquals(listOf(mobile.deviceId), desktop.connectedPeers.value)
            assertEquals(listOf(desktop.deviceId), mobile.connectedPeers.value)

            val originalBytes = ByteArray(200_000) { (it % 256).toByte() }
            val sent = MediaMessage(
                id = "media-1",
                name = "valencia.jpg",
                description = "Foto de prueba",
                mimeType = "image/jpeg",
                bytes = originalBytes
            )

            val sendResult = desktop.send(mobile.deviceId, sent, MediaMessage.serializer())
            assertTrue(sendResult is BeamResult.Success, "Envío falló: $sendResult")

            val received = withTimeout(10_000) {
                mobile.observeIncoming(MediaMessage.serializer()).first().second
            }

            assertEquals(sent.id, received.id)
            assertEquals(sent.name, received.name)
            assertEquals(sent.description, received.description)
            assertEquals(sent.mimeType, received.mimeType)
            assertTrue(originalBytes.contentEquals(received.bytes), "Los bytes recibidos no coinciden con los enviados")
        } finally {
            desktop.shutdown()
            mobile.shutdown()
            delay(200)
        }
    }
}
