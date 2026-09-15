package com.nubax.beam

import androidx.compose.ui.window.ComposeUIViewController
import com.nubax.beam.library.storage.IosBeamStorage
import com.nubax.beam.media.IosImagePicker
import com.nubax.beam.media.IosReceivedFileSaver
import platform.UIKit.UIViewController

/**
 * Punto de entrada que llama Swift (`iosApp/iosApp/iOSApp.swift`) para obtener el
 * `UIViewController` que aloja toda la UI de Compose. Sin hotspotController/wifiJoiner/
 * networkBridgeController todavía — alcance actual: solo emparejamiento con Desktop por
 * código (mismo flujo que Android↔Desktop). Unirse al puente de red (PROJECT.md §3)
 * sin perder la ruta a internet por defecto es un hueco real pendiente en iOS, no
 * implementado — ver la cabecera de `IosMeshBeamConnection`.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    App(
        storage = IosBeamStorage(),
        imagePicker = IosImagePicker(),
        fileSaver = IosReceivedFileSaver()
    )
}
