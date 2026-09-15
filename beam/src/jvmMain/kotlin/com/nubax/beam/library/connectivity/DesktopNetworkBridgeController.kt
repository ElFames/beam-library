package com.nubax.beam.library.connectivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Se une a la red de un puente de red (ver PROJECT.md §3) en macOS vía
 * `networksetup -setairportnetwork` (la misma técnica que ya usa la demo para el
 * hotspot de reencuentro del móvil). A diferencia de Android, aquí NO hace falta
 * atar sockets a nada: el puente pasa a ser la ÚNICA red WiFi activa tras unirse
 * (un portátil solo tiene una radio WiFi), y si tiene detrás una WiFi de casa
 * conocida, reparte internet por NAT de forma transparente — por eso [networkBinder]
 * siempre es null aquí.
 *
 * Si este Desktop tiene su ruta a internet por Ethernet (no por esta misma WiFi),
 * unirse al puente no le cuesta el internet general — son interfaces físicamente
 * independientes.
 */
class DesktopNetworkBridgeController : NetworkBridgeController {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<NetworkBridgeConnectionState>(NetworkBridgeConnectionState.Idle)
    override val state: StateFlow<NetworkBridgeConnectionState> = _state.asStateFlow()

    override val networkBinder: StateFlow<NetworkSocketBinder?> = MutableStateFlow<NetworkSocketBinder?>(null).asStateFlow()

    override fun connect(ssid: String, password: String) {
        _state.value = NetworkBridgeConnectionState.Connecting
        scope.launch {
            val device = wifiDeviceName()
            if (device == null) {
                _state.value = NetworkBridgeConnectionState.Failed("No se encontró el interfaz WiFi")
                return@launch
            }
            val process = ProcessBuilder("networksetup", "-setairportnetwork", device, ssid, password)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            _state.value = if (exitCode == 0) {
                NetworkBridgeConnectionState.Connected
            } else {
                NetworkBridgeConnectionState.Failed(output.ifBlank { "networksetup devolvió $exitCode" })
            }
        }
    }

    override fun disconnect() {
        // networksetup no tiene un "desconectar limpio"; el usuario cambia de red a mano
        // (o llamando a connect() con otra red) cuando quiera salir de la del puente.
        _state.value = NetworkBridgeConnectionState.Idle
    }

    private fun wifiDeviceName(): String? {
        val process = ProcessBuilder("networksetup", "-listallhardwareports").start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        val lines = output.lines()
        val portIndex = lines.indexOfFirst { it.contains("Wi-Fi") || it.contains("AirPort") }
        if (portIndex == -1) return null
        val deviceLine = lines.getOrNull(portIndex + 1) ?: return null
        return deviceLine.substringAfter("Device:").trim().takeIf { it.isNotBlank() }
    }
}
