package com.nubax.beam.library.sdk

import com.nubax.beam.library.natives.initializeBeamConnection

object BeamSdk {
    fun init(): BeamApplication {
        initializeBeamConnection()
        return BeamApplication()
    }
}
