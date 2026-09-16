package com.nubax.beam.library.connection

import com.nubax.beam.library.core.DeviceHistoryStore
import com.nubax.beam.library.core.DeviceIdentity
import com.nubax.beam.library.core.PeerKind
import com.nubax.beam.library.sdk.models.BeamResult
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transporte de red de Aircom. Simétrico a propósito (cualquiera anuncia, descubre,
 * acepta y conecta) — solo hay dos [PeerKind] (MOBILE, DESKTOP). Cuando no comparten
 * red ya (fuera de casa/oficina), el móvil comparte su propia conexión (hotspot local
 * en Android, Hotspot personal en iOS) y el Desktop se une a ella como a cualquier
 * WiFi — sin ningún dispositivo ni protocolo intermedio (ver PROJECT.md §3, antiguo
 * hardware ESP32 retirado por completo).
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
}
