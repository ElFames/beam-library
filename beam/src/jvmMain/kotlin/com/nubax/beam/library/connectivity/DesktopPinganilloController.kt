package com.nubax.beam.library.connectivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Se une a la red del pinganillo en macOS vía `networksetup -setairportnetwork` (la misma
 * técnica que ya usa la demo para el hotspot de reencuentro del móvil). A diferencia de
 * Android, aquí NO hace falta atar sockets a nada: el pinganillo pasa a ser la ÚNICA red
 * WiFi activa tras unirse (un portátil solo tiene una radio WiFi), y si tiene detrás una
 * WiFi de casa conocida, reparte internet por NAT de forma transparente — por eso
 * [networkBinder] siempre es null aquí.
 */
class DesktopPinganilloController : PinganilloController {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<PinganilloConnectionState>(PinganilloConnectionState.Idle)
    override val state: StateFlow<PinganilloConnectionState> = _state.asStateFlow()

    override val networkBinder: StateFlow<NetworkSocketBinder?> = MutableStateFlow<NetworkSocketBinder?>(null).asStateFlow()

    override fun connect(ssid: String, password: String) {
        _state.value = PinganilloConnectionState.Connecting
        scope.launch {
            val device = wifiDeviceName()
            if (device == null) {
                _state.value = PinganilloConnectionState.Failed("No se encontró el interfaz WiFi")
                return@launch
            }
            val process = ProcessBuilder("networksetup", "-setairportnetwork", device, ssid, password)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            _state.value = if (exitCode == 0) {
                PinganilloConnectionState.Connected
            } else {
                PinganilloConnectionState.Failed(output.ifBlank { "networksetup devolvió $exitCode" })
            }
        }
    }

    override fun disconnect() {
        // networksetup no tiene un "desconectar limpio"; el usuario cambia de red a mano
        // (o llamando a connect() con otra red) cuando quiera salir de la del pinganillo.
        _state.value = PinganilloConnectionState.Idle
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
