package com.nubax.beam

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

actual fun isAndroid() = false

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Beam Test App",
    ) {
        App()
    }
}

actual fun requiredWifiPermissions(): Array<String> {
    return arrayOf("")
}

actual fun hasWifiPermissions(context: Any): Boolean {
    return true
}