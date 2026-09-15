package com.nubax.beam.library.connectivity

import kotlinx.coroutines.flow.StateFlow

sealed class NetworkBridgeConnectionState {
    data object Idle : NetworkBridgeConnectionState()
    data object Connecting : NetworkBridgeConnectionState()
    data object Connected : NetworkBridgeConnectionState()
    data class Failed(val message: String) : NetworkBridgeConnectionState()
}

/**
 * Une este dispositivo a la red que levanta un puente de red (ESP32, o cualquier
 * router de viaje/travel router — ver PROJECT.md §3): un AP WiFi normal, sin ningún
 * protocolo especial. Una vez conectado, el discovery normal de Aircom (beacon UDP +
 * handshake TCP) hace el resto sin código adicional: el puente es "una red más" desde
 * el punto de vista del protocolo, igual que la WiFi de casa o el hotspot de Android.
 *
 * A diferencia del "pinganillo" original, este dispositivo NO tiene credenciales fijas
 * conocidas de antemano por la app — SSID/contraseña son los que traiga la tarjeta de
 * la unidad concreta, tecleados por el usuario la primera vez, igual que unirse a
 * cualquier WiFi nueva (ver [com.nubax.beam.media.WifiJoiner] para el mismo patrón de
 * UI en el caso del hotspot de Android).
 *
 * Cada plataforma decide CÓMO unirse (en Android, una red "solo local" que no roba la
 * ruta a internet por defecto; en Desktop, uniéndose directamente porque ahí el puente
 * puede hacer de pasarela NAT hacia internet si tiene WiFi de casa configurada).
 */
interface NetworkBridgeController {
    val state: StateFlow<NetworkBridgeConnectionState>

    /**
     * No nulo mientras la conexión al puente esté activa Y haga falta atar sockets a
     * ella explícitamente (Android). Quien monte la UI debe observarlo y llamar a
     * `BeamApplication.attachNetwork`/`detachNetwork` cuando cambie. En Desktop siempre
     * es null: ahí no hace falta atar nada, la única red activa ya es la del puente.
     */
    val networkBinder: StateFlow<NetworkSocketBinder?>

    fun connect(ssid: String, password: String)
    fun disconnect()
}
