package com.nubax.beam.library.connectivity

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.nubax.beam.library.core.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Envuelve WifiManager#startLocalOnlyHotspot: crea una red WiFi local bajo demanda,
 * sin internet y sin tocar el hotspot del sistema (no requiere que el usuario active
 * nada a mano). Es la pieza que permite que otro UDIS (p. ej. un Desktop) se una al
 * móvil aunque no haya ninguna red compartida a mano, por ejemplo en la calle.
 *
 * La reserva solo vive mientras esta instancia se mantenga viva: para uso real hará
 * falta sostenerla desde un foreground service; para esta prueba, mientras la
 * Activity esté en primer plano es suficiente.
 */
class LocalHotspotController(context: Context) : HotspotController {

    private val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val _hotspot = MutableStateFlow<LocalHotspotInfo?>(null)
    override val hotspot: StateFlow<LocalHotspotInfo?> = _hotspot.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    override fun start() {
        if (reservation != null) return
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                reservation = res
                _hotspot.value = extractInfo(res)
                _error.value = null
                Log.i("Hotspot local activo: ${_hotspot.value}")
            }

            override fun onStopped() {
                reservation = null
                _hotspot.value = null
                Log.i("Hotspot local detenido")
            }

            override fun onFailed(reason: Int) {
                reservation = null
                _hotspot.value = null
                _error.value = "No se pudo crear el hotspot local (código $reason)"
                Log.e(_error.value ?: "")
            }
        }

        try {
            wifiManager.startLocalOnlyHotspot(callback, null)
        } catch (e: SecurityException) {
            _error.value = "Falta el permiso de red cercana/ubicación para crear el hotspot"
            Log.e(_error.value ?: "")
        }
    }

    override fun stop() {
        reservation?.close()
        reservation = null
        _hotspot.value = null
    }

    @Suppress("DEPRECATION")
    private fun extractInfo(res: WifiManager.LocalOnlyHotspotReservation): LocalHotspotInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = res.softApConfiguration
            LocalHotspotInfo(
                ssid = config.ssid ?: "?",
                passphrase = config.passphrase ?: "?"
            )
        } else {
            val config = res.wifiConfiguration
            LocalHotspotInfo(
                ssid = config?.SSID?.removeSurrounding("\"") ?: "?",
                passphrase = config?.preSharedKey ?: "?"
            )
        }
    }
}
