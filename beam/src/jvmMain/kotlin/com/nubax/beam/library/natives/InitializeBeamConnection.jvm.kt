package com.nubax.beam.library.natives

import com.nubax.beam.library.DesktopBeamConnection
import com.nubax.beam.library.core.Locator

actual fun initializeBeamConnection() {
    Locator.beamConnection = DesktopBeamConnection()
}