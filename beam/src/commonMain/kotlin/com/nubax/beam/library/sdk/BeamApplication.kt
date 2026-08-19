package com.nubax.beam.library.sdk

import com.nubax.beam.library.connection.BeamConnection
import com.nubax.beam.library.connection.DiscoveredDevice
import com.nubax.beam.library.connection.PairingRequestEvent
import com.nubax.beam.library.connectivity.NetworkSocketBinder
import com.nubax.beam.library.core.BeamStorage
import com.nubax.beam.library.core.DeviceIdentity
import com.nubax.beam.library.core.Locator
import com.nubax.beam.library.core.TrustStore
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
    private lateinit var trustStore: TrustStore

    private val _state = MutableStateFlow<BeamState>(BeamState.Disabled)
    val state: StateFlow<BeamState> = _state.asStateFlow()

    val deviceId: String get() = identity.id
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> get() = beamConnection.discoveredDevices
    val connectedPeers: StateFlow<List<String>> get() = beamConnection.connectedPeers
    val pairingRequests: Flow<PairingRequestEvent> get() = beamConnection.pairingRequests

    /** Genera (o recupera) la identidad de este dispositivo y empieza a anunciarse/descubrir. */
    fun start(deviceName: String) {
        identity = DeviceIdentity.loadOrCreate(storage)
        trustStore = TrustStore(storage)
        beamConnection.start(identity, trustStore, deviceName)
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

    /** El usuario confirmó que el código de verificación coincide en ambas pantallas. */
    suspend fun confirmPairing(peerId: String): BeamResult<Unit> = beamConnection.confirmPairing(peerId)

    fun rejectPairing(peerId: String) = beamConnection.rejectPairing(peerId)

    fun disconnect(peerId: String) = beamConnection.disconnect(peerId)

    /** Ids de dispositivos ya emparejados (aunque no estén conectados ahora mismo). */
    fun trustedPeerIds(): List<String> = trustStore.all().map { it.id }

    /**
     * Adjunta una red adicional (p. ej. la del pinganillo una vez [PinganilloController]
     * la ha unido) para que el discovery/handshake también intente esa vía. Ver
     * [com.nubax.beam.library.connection.BeamConnection.attachNetwork].
     */
    fun attachNetwork(binder: NetworkSocketBinder) = beamConnection.attachNetwork(binder)

    fun detachNetwork() = beamConnection.detachNetwork()

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
