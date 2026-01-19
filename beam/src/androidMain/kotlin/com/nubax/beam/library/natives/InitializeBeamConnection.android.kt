package com.nubax.beam.library.natives

import com.nubax.beam.library.AndroidBeamConnection
import com.nubax.beam.library.core.Locator

actual fun initializeBeamConnection() {
    if (Locator.beamConnection == null)
        Locator.beamConnection = AndroidBeamConnection()
}