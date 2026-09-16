package com.nubax.beam

import androidx.compose.ui.window.ComposeUIViewController
import com.nubax.beam.library.storage.IosBeamStorage
import com.nubax.beam.media.IosImagePicker
import com.nubax.beam.media.IosReceivedFileSaver
import platform.UIKit.UIViewController

/**
 * Punto de entrada que llama Swift (`iosApp/iosApp/iOSApp.swift`) para obtener el
 * `UIViewController` que aloja toda la UI de Compose. Sin hotspotController/wifiJoiner
 * todavía — alcance actual: solo emparejamiento con Desktop por código (mismo flujo
 * que Android↔Desktop). Cuando no comparten red, el camino es el Hotspot personal de
 * iOS (activado a mano en Ajustes, fuera del control de la app) + que el Desktop se
 * una a esa red — ver PROJECT.md §3.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    App(
        storage = IosBeamStorage(),
        imagePicker = IosImagePicker(),
        fileSaver = IosReceivedFileSaver()
    )
}
