package com.nubax.beam.library.sdk

import com.nubax.beam.library.connection.MeshBeamConnection
import com.nubax.beam.library.core.BeamStorage
import com.nubax.beam.library.core.Locator

object BeamSdk {
    fun init(storage: BeamStorage): BeamApplication {
        if (Locator.beamConnection == null) {
            Locator.beamConnection = MeshBeamConnection()
        }
        return BeamApplication(storage)
    }
}
