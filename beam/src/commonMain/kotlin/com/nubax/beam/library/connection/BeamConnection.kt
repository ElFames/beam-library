package com.nubax.beam.library.connection

import com.nubax.beam.library.connectivity.NetworkSocketBinder
import com.nubax.beam.library.core.DeviceHistoryStore
import com.nubax.beam.library.core.DeviceIdentity
import com.nubax.beam.library.core.PeerKind
import com.nubax.beam.library.sdk.models.BeamResult
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transporte de red de Aircom. Simétrico a propósito (cualquiera anuncia, descubre,
 * acepta y conecta) — solo hay dos [PeerKind] (MOBILE, DESKTOP). El puente de red
 * (antes "pinganillo", ver PROJECT.md §3) no habla este protocolo en absoluto: es
 * infraestructura de red transparente, no un peer.
 */
internal interface BeamConnection {
    val discoveredDevices: StateFlow<List<DiscoveredDevice>>
    val connectedPeers: StateFlow<List<String>>
    val linkEvents: SharedFlow<LinkEvent>
    val incomingMessages: SharedFlow<IncomingMessage>

    /** Código de emparejamiento que este Desktop está mostrando ahora mismo (null si no aplica). */
    val pairingCode: StateFlow<String?>

    fun start(identity: DeviceIdentity, history: DeviceHistoryStore, deviceName: String, myKind: PeerKind)
    fun stop()

    /** Conexión directa a una IP conocida, saltándose el discovery (fallback manual / tests). */
    suspend fun connectDirect(host: String, port: Int): BeamResult<Unit>

    /** Empareja con un Desktop enviando el código que se está leyendo en su pantalla. */
    suspend fun pairDesktopWithCode(host: String, port: Int, code: String): BeamResult<Unit>

    /** Desvincula localmente y avisa al otro lado si está conectado ahora mismo. */
    fun unlink(deviceId: String)

    suspend fun send(peerId: String, data: ByteArray, onProgress: ((Float) -> Unit)? = null): BeamResult<Unit>
    fun disconnect(peerId: String)

    /**
     * Añade un enlace de red adicional (más allá del socket por defecto, sin atar) para
     * poder hablar con peers que solo son alcanzables por una interfaz concreta —
     * típicamente el puente de red (PROJECT.md §3) cuando Android lo une como red "solo
     * local" sin perder su ruta a internet por defecto. Como mucho hay un enlace extra
     * activo a la vez: llamar de nuevo reemplaza el anterior. No-op por defecto para no
     * forzar a otras implementaciones (p. ej. tests) a lidiar con esto.
     */
    fun attachNetwork(binder: NetworkSocketBinder) {}

    /** Cierra y retira el enlace de red adicional, si hay uno activo. */
    fun detachNetwork() {}
}
