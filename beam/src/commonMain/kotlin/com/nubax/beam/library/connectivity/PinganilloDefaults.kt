package com.nubax.beam.library.connectivity

/**
 * Identidad de red que el pinganillo trae de fábrica: SSID/contraseña fijos (a diferencia
 * del hotspot de Android, que los genera al azar cada vez), para que móvil y desktop puedan
 * unirse sin que el pinganillo tenga pantalla donde mostrarlos.
 *
 * OJO: estas constantes tienen que coincidir carácter a carácter con las mismas en el
 * firmware (esp32-pinganillo/main/wifi_setup.cpp: AP_SSID / AP_PASSWORD). Son dos repos y
 * lenguajes distintos — no hay forma de compartir una única fuente de verdad entre Kotlin
 * y C++ aquí, así que si cambias una constante cambia también la otra a mano.
 */
object PinganilloDefaults {
    const val AP_SSID = "Pinganillo-Beam"
    const val AP_PASSWORD = "beam12345"

    /** Debe coincidir con beaconPort/tcpPort por defecto de MeshBeamConnection. */
    const val BEACON_PORT = 8888
    const val TCP_PORT = 9999
}
