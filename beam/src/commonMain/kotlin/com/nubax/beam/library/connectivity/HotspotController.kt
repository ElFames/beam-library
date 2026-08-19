package com.nubax.beam.library.connectivity

import kotlinx.coroutines.flow.StateFlow

data class LocalHotspotInfo(val ssid: String, val passphrase: String)

/**
 * Abstracción común para poder referenciar el hotspot local desde código commonMain
 * (la app de demo) aunque la única implementación real hoy sea Android-only.
 * Desktop simplemente no tiene una instancia de esto (no le hace falta).
 */
interface HotspotController {
    val hotspot: StateFlow<LocalHotspotInfo?>
    val error: StateFlow<String?>

    /**
     * El SSID/contraseña los elige Android al azar cada vez — las apps normales no pueden
     * fijarlos (es una restricción de la plataforma, pensada para evitar que una app cree
     * redes con nombre estable y rastreable). Por eso el otro lado tiene que leerlos de la
     * pantalla, no hay forma de calcularlos de antemano.
     */
    fun start()
    fun stop()
}
