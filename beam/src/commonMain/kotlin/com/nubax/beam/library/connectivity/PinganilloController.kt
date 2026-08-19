package com.nubax.beam.library.connectivity

import kotlinx.coroutines.flow.StateFlow

sealed class PinganilloConnectionState {
    data object Idle : PinganilloConnectionState()
    data object Connecting : PinganilloConnectionState()
    data object Connected : PinganilloConnectionState()
    data class Failed(val message: String) : PinganilloConnectionState()
}

/**
 * Une este dispositivo a la red que levanta el pinganillo (su propio AP WiFi, con
 * credenciales fijas de fábrica — ver [PinganilloDefaults]). Una vez conectado, el
 * discovery normal de Beam (beacon UDP + handshake TCP) hace el resto sin código
 * adicional: el pinganillo es "una red más" desde el punto de vista del protocolo,
 * igual que la WiFi de casa o el hotspot de Android.
 *
 * Cada plataforma decide CÓMO unirse (en Android, una red "solo local" que no roba la
 * ruta a internet por defecto; en Desktop, uniéndose directamente porque ahí el
 * pinganillo hace de pasarela NAT hacia internet).
 */
interface PinganilloController {
    val state: StateFlow<PinganilloConnectionState>

    /**
     * No nulo mientras la conexión al pinganillo esté activa Y haga falta atar sockets a
     * ella explícitamente (Android). Quien monte la UI debe observarlo y llamar a
     * `BeamApplication.attachNetwork`/`detachNetwork` cuando cambie. En Desktop siempre
     * es null: ahí no hace falta atar nada, la única red activa ya es la del pinganillo.
     */
    val networkBinder: StateFlow<NetworkSocketBinder?>

    fun connect(ssid: String = PinganilloDefaults.AP_SSID, password: String = PinganilloDefaults.AP_PASSWORD)
    fun disconnect()
}
