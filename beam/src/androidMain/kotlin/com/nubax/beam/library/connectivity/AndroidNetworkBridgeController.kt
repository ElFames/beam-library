package com.nubax.beam.library.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.nubax.beam.library.core.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramSocket
import java.net.Socket

/**
 * Une el móvil a la red de un puente de red (ver PROJECT.md §3) como red "solo local"
 * (`WifiNetworkSpecifier`, Android 10+): a diferencia de conectarse a una WiFi normal
 * desde Ajustes, esto NO se convierte en la ruta por defecto, así que el móvil sigue
 * usando datos móviles (o su WiFi normal) para todo lo demás. Solo el tráfico que se
 * ata explícitamente a la [Network] resultante pasa por el puente — de eso se encarga
 * el [NetworkSocketBinder] que se publica en [networkBinder] en cuanto la conexión
 * está lista.
 *
 * Usa `setWpa3Passphrase` (no WPA2): el firmware del puente exige WPA3-SAE (ver
 * `esp32-bridge/main/wifi_setup.cpp`).
 *
 * Por debajo de Android 10 no hay una forma decente de pedir esto sin APIs ya deprecadas
 * desde esa misma versión, así que ahí se falla explícitamente en vez de fingir que
 * funciona.
 */
class AndroidNetworkBridgeController(context: Context) : NetworkBridgeController {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    private val _state = MutableStateFlow<NetworkBridgeConnectionState>(NetworkBridgeConnectionState.Idle)
    override val state: StateFlow<NetworkBridgeConnectionState> = _state.asStateFlow()

    private val _networkBinder = MutableStateFlow<NetworkSocketBinder?>(null)
    override val networkBinder: StateFlow<NetworkSocketBinder?> = _networkBinder.asStateFlow()

    override fun connect(ssid: String, password: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _state.value = NetworkBridgeConnectionState.Failed("Necesita Android 10 o superior")
            return
        }
        disconnect()
        _state.value = NetworkBridgeConnectionState.Connecting

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa3Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i("Puente de red conectado (red solo-local)")
                _networkBinder.value = AndroidNetworkSocketBinder(network)
                _state.value = NetworkBridgeConnectionState.Connected
            }

            override fun onUnavailable() {
                _state.value = NetworkBridgeConnectionState.Failed("No se encontró el puente de red ($ssid)")
            }

            override fun onLost(network: Network) {
                Log.i("Se perdió la conexión con el puente de red")
                _networkBinder.value = null
                _state.value = NetworkBridgeConnectionState.Idle
            }
        }
        callback = cb
        connectivityManager.requestNetwork(request, cb)
    }

    override fun disconnect() {
        callback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        callback = null
        _networkBinder.value = null
        _state.value = NetworkBridgeConnectionState.Idle
    }
}

private class AndroidNetworkSocketBinder(private val network: Network) : JvmNetworkSocketBinder {
    override fun bind(socket: DatagramSocket) {
        network.bindSocket(socket)
    }

    override fun bind(socket: Socket) {
        network.bindSocket(socket)
    }
}
