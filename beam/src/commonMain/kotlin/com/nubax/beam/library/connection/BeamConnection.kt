package com.nubax.beam.library.connection

import com.nubax.beam.library.connectivity.NetworkSocketBinder
import com.nubax.beam.library.core.DeviceIdentity
import com.nubax.beam.library.core.TrustStore
import com.nubax.beam.library.sdk.models.BeamResult
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transporte de red de Aircom/Beam. Simétrico a propósito: la misma implementación
 * sirve para Desktop, Android y (más adelante) el pinganillo — cualquiera puede
 * anunciarse, descubrir a los demás, aceptar conexiones y abrir conexiones, todos
 * a la vez, con varios peers en paralelo.
 */
internal interface BeamConnection {
    val discoveredDevices: StateFlow<List<DiscoveredDevice>>
    val connectedPeers: StateFlow<List<String>>
    val pairingRequests: SharedFlow<PairingRequestEvent>
    val incomingMessages: SharedFlow<IncomingMessage>

    fun start(identity: DeviceIdentity, trustStore: TrustStore, deviceName: String)
    fun stop()

    /** Conexión directa a una IP conocida, saltándose el discovery (fallback manual / tests). */
    suspend fun connectDirect(host: String, port: Int): BeamResult<Unit>

    suspend fun confirmPairing(peerId: String): BeamResult<Unit>
    fun rejectPairing(peerId: String)

    suspend fun send(peerId: String, data: ByteArray, onProgress: ((Float) -> Unit)? = null): BeamResult<Unit>
    fun disconnect(peerId: String)

    /**
     * Añade un enlace de red adicional (más allá del socket por defecto, sin atar) para
     * poder hablar con peers que solo son alcanzables por una interfaz concreta —
     * típicamente el pinganillo cuando Android lo une como red "solo local" sin perder su
     * ruta a internet por defecto. Como mucho hay un enlace extra activo a la vez: llamar
     * de nuevo reemplaza el anterior. No-op por defecto para no forzar a otras
     * implementaciones (p. ej. tests) a lidiar con esto.
     */
    fun attachNetwork(binder: NetworkSocketBinder) {}

    /** Cierra y retira el enlace de red adicional, si hay uno activo. */
    fun detachNetwork() {}
}
