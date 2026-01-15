package com.nubax.beam

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

actual fun isAndroid() = false

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "KMP_Beam",
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