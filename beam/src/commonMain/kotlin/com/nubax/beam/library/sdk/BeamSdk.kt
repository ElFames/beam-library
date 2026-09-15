package com.nubax.beam.library.sdk

import com.nubax.beam.library.connection.BeamConnection
import com.nubax.beam.library.core.BeamStorage
import com.nubax.beam.library.core.Locator

/**
 * Cada plataforma provee su propia implementación de [BeamConnection] (en JVM/Android
 * es `MeshBeamConnection`, con sockets `java.net.*`; iOS tendrá la suya sobre
 * `Network.framework`) — `commonMain` nunca puede nombrar la clase concreta.
 */
internal expect fun createDefaultBeamConnection(): BeamConnection

object BeamSdk {
    fun init(storage: BeamStorage): BeamApplication {
        if (Locator.beamConnection == null) {
            Locator.beamConnection = createDefaultBeamConnection()
        }
        return BeamApplication(storage)
    }
}
