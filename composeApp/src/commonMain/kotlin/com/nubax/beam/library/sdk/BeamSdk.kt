package com.nubax.beam.library.sdk

import com.nubax.beam.library.core.Locator

object BeamSdk {
    fun init(): BeamApplication {
        return BeamApplication(Locator.beamConnection!!)
    }
}
