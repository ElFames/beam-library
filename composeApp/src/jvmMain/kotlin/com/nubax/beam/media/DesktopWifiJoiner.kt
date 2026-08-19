package com.nubax.beam.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Se une a una red WiFi por código en macOS, vía `networksetup` (herramienta que ya
 * trae el sistema, sin dependencias nuevas). Es la pieza que falta para que el hotspot
 * de reencuentro (SSID/contraseña fijos, ver HotspotCredentials) sea de verdad automático:
 * sin esto, alguien tendría que teclearlo a mano en Preferencias del Sistema cada vez.
 *
 * OJO: esto desconecta al Mac de su WiFi actual para unirlo a la del móvil. Es una acción
 * real sobre la conexión de red del usuario, por eso está detrás de un botón explícito
 * en la demo, no algo que se dispare solo en segundo plano.
 */
class DesktopWifiJoiner : WifiJoiner {

    override suspend fun join(ssid: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        val device = wifiDeviceName() ?: return@withContext Result.failure(IllegalStateException("No se encontró el interfaz WiFi"))
        val process = ProcessBuilder("networksetup", "-setairportnetwork", device, ssid, password)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode == 0) Result.success(Unit) else Result.failure(IllegalStateException(output.ifBlank { "networksetup devolvió $exitCode" }))
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
