package com.nubax.beam.library

import com.nubax.beam.library.connection.MeshBeamConnection
import com.nubax.beam.library.core.BeamStorage
import com.nubax.beam.library.core.PeerKind
import com.nubax.beam.library.sdk.BeamApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

@Serializable
private data class TestMessage(val text: String)

private class HarnessStorage : BeamStorage {
    private val map = mutableMapOf<String, String>()
    override fun readString(key: String): String? = map[key]
    override fun writeString(key: String, value: String) { map[key] = value }
}

/**
 * NO es un test automático (no hace assert de nada): es un programa manual para
 * validar, sin ESP32 real, que el handshake criptográfico del firmware
 * (esp32-pinganillo/main/beam_crypto.cpp + beam_protocol.cpp) es byte-compatible
 * con esta implementación real de Kotlin (MeshBeamConnection).
 *
 * Se arranca como `kind = PINGANILLO` a propósito: es justo el rol ACEPTOR que
 * ejercerá el firmware real (nunca conecta hacia fuera, solo acepta y autovincula
 * a quien complete el handshake — ver PROJECT.md §2.3), así que este harness
 * ejercita exactamente esa misma rama de MeshBeamConnection. El programa nativo
 * se anuncia con `kind: "ANDROID"` en su HandshakeHello para encajar en el otro
 * lado de ese mismo flujo.
 *
 * Cómo usarlo:
 * 1. Corre esta función `main` (▶ del IDE, o `./gradlew :beam:jvmTestClasses` y
 *    luego ejecuta la clase ManualHandshakeHarnessKt con el classpath del módulo).
 * 2. En otra terminal: `cd esp32-pinganillo/native_test && ./build.sh && ./handshake_test`
 *    (necesita `brew install mbedtls@3 cjson` la primera vez).
 * 3. Si ambos lados imprimen el mensaje de texto que se mandan y acaba con
 *    "TODO OK", el protocolo criptográfico interopera correctamente y el
 *    firmware está listo para probarse ya en hardware real.
 */
fun main() = runBlocking {
    val app = BeamApplication(HarnessStorage(), MeshBeamConnection())
    app.start("JVM-test-harness", PeerKind.PINGANILLO)

    println("Escuchando en el puerto 9999. device_id = ${app.deviceId}")
    println("Ahora ejecuta esp32-pinganillo/native_test/handshake_test (ver su README)")
    println("(Ctrl+C para terminar)")

    launch {
        app.linkEvents.collect { event -> println("Evento de vinculación: $event") }
    }

    launch {
        app.observeIncoming(TestMessage.serializer()).collect { (peerId, message) ->
            println("✅ Mensaje recibido de $peerId: \"${message.text}\"")
            app.send(peerId, TestMessage("hola desde la JVM, recibido: ${message.text}"), TestMessage.serializer())
        }
    }

    while (true) delay(1000)
}
