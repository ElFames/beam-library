package com.nubax.beam.library.sdk

import com.nubax.beam.library.core.Locator
import com.nubax.beam.library.natives.initializeBeamConnection

object BeamSdk {
    fun init(): BeamApplication {
        if (Locator.beamConnection == null) initializeBeamConnection()
        return BeamApplication(Locator.beamConnection!!)
    }
}
