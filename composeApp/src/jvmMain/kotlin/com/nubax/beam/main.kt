package com.nubax.beam

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.nubax.beam.library.connectivity.DesktopNetworkBridgeController
import com.nubax.beam.library.storage.DesktopBeamStorage
import com.nubax.beam.media.DesktopImagePicker
import com.nubax.beam.media.DesktopReceivedFileSaver
import com.nubax.beam.media.DesktopWifiJoiner

actual fun isAndroid() = false
actual fun isIos() = false

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Beam Test App",
    ) {
        App(
            storage = DesktopBeamStorage(),
            imagePicker = DesktopImagePicker(),
            fileSaver = DesktopReceivedFileSaver(),
            wifiJoiner = DesktopWifiJoiner(),
            networkBridgeController = DesktopNetworkBridgeController()
        )
    }
}

actual fun requiredWifiPermissions(): Array<String> {
    return arrayOf("")
}

actual fun hasWifiPermissions(context: Any): Boolean {
    return true
}