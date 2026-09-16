package com.nubax.beam.library.sdk

import com.nubax.beam.library.connection.BeamConnection
import com.nubax.beam.library.connection.DiscoveredDevice
import com.nubax.beam.library.connection.LinkEvent
import com.nubax.beam.library.core.BeamStorage
import com.nubax.beam.library.core.DeviceHistoryStore
import com.nubax.beam.library.core.DeviceIdentity
import com.nubax.beam.library.core.LinkedDevice
import com.nubax.beam.library.core.Locator
import com.nubax.beam.library.core.PeerKind
import com.nubax.beam.library.sdk.models.BeamResult
import com.nubax.beam.library.sdk.models.BeamState
import com.nubax.beam.library.sdk.models.onFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

class BeamApplication internal constructor(
    private val storage: BeamStorage,
    private val beamConnection: BeamConnection = Locator.beamConnection!!
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var identity: DeviceIdentity
    private lateinit var history: DeviceHistoryStore

    private val _state = MutableStateFlow<BeamState>(BeamState.Disabled)
    val state: StateFlow<BeamState> = _state.asStateFlow()

    val deviceId: String get() = identity.id
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> get() = beamConnection.discoveredDevices
    val connectedPeers: StateFlow<List<String>> get() = beamConnection.connectedPeers
    val linkEvents: Flow<LinkEvent> get() = beamConnection.linkEvents

    /** Código que este Desktop está mostrando ahora mismo para que un Android se empareje (null si no aplica). */
    val pairingCode: StateFlow<String?> get() = beamConnection.pairingCode

    /** Genera (o recupera) la identidad de este dispositivo y empieza a anunciarse/descubrir. */
    fun start(deviceName: String, kind: PeerKind) {
        identity = DeviceIdentity.loadOrCreate(storage)
        history = DeviceHistoryStore(storage)
        beamConnection.start(identity, history, deviceName, kind)
        _state.value = BeamState.Activated

        beamConnection.connectedPeers.onEach { peers ->
            _state.value = when {
                peers.isNotEmpty() -> BeamState.Connected(peers.first())
                _state.value is BeamState.Connected -> BeamState.Activated
                else -> _state.value
            }
        }.launchIn(scope)
    }

    /** Conexión manual a una IP conocida; para cuando el discovery automático no basta (o para pruebas). */
    suspend fun connectTo(host: String, port: Int = 9999): BeamResult<Unit> {
        _state.value = BeamState.Connecting
        return beamConnection.connectDirect(host, port)
            .onFailure { _state.value = BeamState.Error(it) }
    }

    /** Empareja con un Desktop mandando el [code] que se está leyendo en su pantalla. */
    suspend fun pairDesktopWithCode(host: String, port: Int, code: String): BeamResult<Unit> =
        beamConnection.pairDesktopWithCode(host, port, code)

    /** Desvincula localmente; si el otro lado está conectado ahora mismo, se le avisa. */
    fun unlink(deviceId: String) = beamConnection.unlink(deviceId)

    fun disconnect(peerId: String) = beamConnection.disconnect(peerId)

    /** Historial completo de dispositivos (vinculados o no) con los que se ha interactuado. */
    fun linkedDevices(): List<LinkedDevice> = history.all()

    /** El dispositivo activo (vinculado ahora mismo) de un tipo dado — solo tiene sentido para [PeerKind.MOBILE] (1:1 desde un Desktop). */
    fun activeDevice(kind: PeerKind): LinkedDevice? = history.activeDevice(kind)

    /** Todos los dispositivos activos de un tipo dado — para [PeerKind.DESKTOP] puede haber más de uno (cardinalidad abierta, ver PROJECT.md §2.5). */
    fun activeDevices(kind: PeerKind): List<LinkedDevice> = history.activeDevices(kind)

    fun shutdown() {
        beamConnection.stop()
        _state.value = BeamState.Disabled
    }

    suspend fun <T> send(
        peerId: String,
        data: T,
        serializer: KSerializer<T>,
        onProgress: ((Float) -> Unit)? = null
    ): BeamResult<Unit> {
        return try {
            val jsonString = Json.encodeToString(serializer, data)
            beamConnection.send(peerId, jsonString.encodeToByteArray(), onProgress)
        } catch (e: Exception) {
            BeamResult.Failure(e.message ?: "Error de envío")
        }
    }

    /** Emite (idPeerEmisor, mensaje) para cada mensaje entrante que encaje con el serializer dado. */
    fun <T> observeIncoming(serializer: KSerializer<T>): Flow<Pair<String, T>> {
        return beamConnection.incomingMessages.mapNotNull { msg ->
            runCatching { msg.peerId to Json.decodeFromString(serializer, msg.bytes.decodeToString()) }
                .getOrNull()
        }
    }
}
