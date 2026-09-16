package com.nubax.beam.library.connection

import com.nubax.beam.library.core.BeamCrypto
import com.nubax.beam.library.core.DeviceHistoryStore
import com.nubax.beam.library.core.DeviceIdentity
import com.nubax.beam.library.core.LinkState
import com.nubax.beam.library.core.Log
import com.nubax.beam.library.core.PeerKind
import com.nubax.beam.library.core.SecureChannel
import com.nubax.beam.library.sdk.models.BeamResult
import com.nubax.beam.library.sdk.models.ControlMessage
import com.nubax.beam.library.sdk.models.EphemeralOffer
import com.nubax.beam.library.sdk.models.HandshakeHello
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * Implementación de [BeamConnection] para iOS, sobre sockets BSD crudos (`PosixSocket.kt`)
 * en vez de `java.net.*` — misma lógica de protocolo/emparejamiento que
 * `MeshBeamConnection` (jvmCommon), solo cambia la capa de transporte. Ver PROJECT.md
 * §2 para el modelo de emparejamiento completo.
 */
internal class IosMeshBeamConnection(
    private val beaconPort: Int = 8888,
    private val tcpPort: Int = 9999,
    private val beaconIntervalMs: Long = 3000,
    private val codePairingTimeoutMs: Long = 15_000
) : BeamConnection {

    private class PeerSession(
        val id: String,
        val kind: PeerKind,
        val name: String,
        val publicKeyBase64: String,
        val connection: TcpConnection,
        val channel: SecureChannel,
        val writeMutex: Mutex = Mutex(),
        var readJob: Job? = null
    )

    private sealed class PairingIntent {
        data class DesktopCode(val code: String, val result: CompletableDeferred<BeamResult<Unit>>) : PairingIntent()
    }

    private lateinit var identity: DeviceIdentity
    private lateinit var history: DeviceHistoryStore
    private var deviceName: String = "UDIS device"
    private var myKind: PeerKind = PeerKind.MOBILE

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val stateMutex = Mutex()

    private var serverFd: Int? = null
    private var udpSocket: UdpSocket? = null
    private var beaconSendJob: Job? = null
    private var beaconListenJob: Job? = null
    private var acceptJob: Job? = null

    private val peers = mutableMapOf<String, PeerSession>()
    private val connectingIds = mutableSetOf<String>()
    private val pairingResultDeferreds = mutableMapOf<String, CompletableDeferred<BeamResult<Unit>>>()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices = _discoveredDevices.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<String>>(emptyList())
    override val connectedPeers = _connectedPeers.asStateFlow()

    private val _linkEvents = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 16)
    override val linkEvents = _linkEvents.asSharedFlow()

    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    override val incomingMessages = _incomingMessages.asSharedFlow()

    private val _pairingCode = MutableStateFlow<String?>(null)
    override val pairingCode = _pairingCode.asStateFlow()

    override fun start(identity: DeviceIdentity, history: DeviceHistoryStore, deviceName: String, myKind: PeerKind) {
        this.identity = identity
        this.history = history
        this.deviceName = deviceName
        this.myKind = myKind

        udpSocket = openUdpBeaconSocket(beaconPort)
        serverFd = openTcpServer(tcpPort)

        refreshPairingCode()
        beaconSendJob = scope.launch { beaconSendLoop() }
        beaconListenJob = scope.launch { beaconListenLoop() }
        acceptJob = scope.launch { acceptLoop() }
        Log.i("Mesh (iOS) iniciado: id=${identity.id} kind=$myKind tcpPort=$tcpPort beaconPort=$beaconPort")
    }

    override fun stop() {
        beaconSendJob?.cancel()
        beaconListenJob?.cancel()
        acceptJob?.cancel()
        scope.launch {
            stateMutex.withLock {
                peers.values.forEach { runCatching { it.connection.close() } }
                peers.clear()
            }
        }
        serverFd?.let { runCatching { closeServer(it) } }
        runCatching { udpSocket?.close() }
        _connectedPeers.value = emptyList()
        _discoveredDevices.value = emptyList()
    }

    private fun refreshPairingCode() {
        _pairingCode.value = if (myKind == PeerKind.DESKTOP && history.activeDevice(PeerKind.MOBILE) == null) {
            (100_000..999_999).random().toString()
        } else {
            null
        }
    }

    // ---------------------------------------------------------------------
    // Discovery
    // ---------------------------------------------------------------------

    private fun currentBeacon() = Beacon(identity.id, deviceName, myKind, tcpPort)

    private suspend fun beaconSendLoop() = withContext(Dispatchers.Default) {
        val socket = udpSocket ?: return@withContext
        Log.i("Beacon: enviando a 255.255.255.255 (puerto $beaconPort)")
        while (isActive) {
            try {
                val bytes = IosFraming.json.encodeToString(currentBeacon()).encodeToByteArray()
                socket.sendBroadcast(beaconPort, bytes)
            } catch (e: Exception) {
                Log.e("Error enviando beacon: ${e.message}")
            }
            delay(beaconIntervalMs)
        }
    }

    private suspend fun beaconListenLoop() = withContext(Dispatchers.Default) {
        val socket = udpSocket ?: return@withContext
        while (isActive) {
            try {
                val (bytes, address) = socket.receive() ?: continue // timeout normal, solo para revisar isActive
                val beacon = runCatching {
                    IosFraming.json.decodeFromString<Beacon>(bytes.decodeToString())
                }.getOrNull() ?: continue

                if (beacon.id == identity.id) continue
                onBeaconReceived(beacon, address)
            } catch (e: Exception) {
                Log.e("Error en discovery: ${e.message}")
            }
        }
    }

    private suspend fun onBeaconReceived(beacon: Beacon, address: String) {
        val trusted = history.isActive(beacon.id)
        val isNewSighting = stateMutex.withLock {
            val current = _discoveredDevices.value
            val wasKnown = current.any { it.id == beacon.id }
            _discoveredDevices.value = current.filterNot { it.id == beacon.id } +
                DiscoveredDevice(beacon.id, beacon.name, beacon.kind, address, beacon.tcpPort, trusted)
            !wasKnown
        }
        if (isNewSighting) Log.i("Descubierto: ${beacon.name} (${beacon.id.take(8)}) en $address")

        if (!trusted) return

        val alreadyHandled = stateMutex.withLock {
            peers.containsKey(beacon.id) || beacon.id in connectingIds
        }
        if (alreadyHandled) return

        val shouldInitiate = identity.id < beacon.id
        if (shouldInitiate) {
            scope.launch { attemptConnect(address, beacon.tcpPort, beacon.id) }
        }
    }

    private suspend fun attemptConnect(host: String, port: Int, expectedId: String?) {
        stateMutex.withLock { connectingIds.add(expectedId ?: host) }
        try {
            val result = connectDirect(host, port)
            if (result is BeamResult.Failure) Log.e("Intento de conexión a $host:$port falló: ${result.message}")
        } finally {
            stateMutex.withLock { connectingIds.remove(expectedId ?: host) }
        }
    }

    // ---------------------------------------------------------------------
    // Conexión saliente / entrante
    // ---------------------------------------------------------------------

    override suspend fun connectDirect(host: String, port: Int): BeamResult<Unit> = withContext(Dispatchers.Default) {
        try {
            performHandshake(connectTcp(host, port), isInitiator = true, intent = null)
            BeamResult.Success(Unit)
        } catch (e: Exception) {
            Log.e("Error conectando a $host:$port -> ${e.message}")
            BeamResult.Failure(e.message ?: "Error de conexión")
        }
    }

    override suspend fun pairDesktopWithCode(host: String, port: Int, code: String): BeamResult<Unit> = withContext(Dispatchers.Default) {
        val result = CompletableDeferred<BeamResult<Unit>>()
        try {
            performHandshake(connectTcp(host, port), isInitiator = true, intent = PairingIntent.DesktopCode(code, result))
            withTimeout(codePairingTimeoutMs) { result.await() }
        } catch (e: TimeoutCancellationException) {
            BeamResult.Failure("El desktop no respondió al código a tiempo")
        } catch (e: Exception) {
            Log.e("Error emparejando con desktop $host:$port -> ${e.message}")
            BeamResult.Failure(e.message ?: "Error de emparejamiento")
        }
    }

    private suspend fun acceptLoop() = withContext(Dispatchers.Default) {
        val server = serverFd ?: return@withContext
        while (isActive) {
            try {
                val client = acceptConnection(server)
                scope.launch {
                    runCatching { performHandshake(client, isInitiator = false, intent = null) }
                        .onFailure { Log.e("Handshake entrante fallido: ${it.message}") }
                }
            } catch (e: Exception) {
                if (isActive) Log.e("Error aceptando conexión: ${e.message}")
            }
        }
    }

    /**
     * Protocolo ping-pong: quien inicia manda primero cada mensaje, el otro responde.
     * Misma lógica de vinculación que `MeshBeamConnection.performHandshake` (jvmCommon) —
     * ver PROJECT.md §2.3/§2.4 para el detalle de cada rama.
     */
    private suspend fun performHandshake(connection: TcpConnection, isInitiator: Boolean, intent: PairingIntent?) = withContext(Dispatchers.Default) {
        val myHello = HandshakeHello(identity.id, deviceName, myKind, Base64.encode(identity.publicKeyEncoded))

        val theirHello: HandshakeHello
        if (isInitiator) {
            IosFraming.sendPlainObject(connection, myHello)
            theirHello = IosFraming.receivePlainObject(connection)
        } else {
            theirHello = IosFraming.receivePlainObject(connection)
            IosFraming.sendPlainObject(connection, myHello)
        }

        if (theirHello.id == identity.id) {
            connection.close()
            return@withContext
        }

        val ephemeral = BeamCrypto.generateKeyPair()
        val mySignature = BeamCrypto.sign(identity.privateKey, ephemeral.publicKeyEncoded)
        val myOffer = EphemeralOffer(Base64.encode(ephemeral.publicKeyEncoded), Base64.encode(mySignature))

        val theirOffer: EphemeralOffer
        if (isInitiator) {
            IosFraming.sendPlainObject(connection, myOffer)
            theirOffer = IosFraming.receivePlainObject(connection)
        } else {
            theirOffer = IosFraming.receivePlainObject(connection)
            IosFraming.sendPlainObject(connection, myOffer)
        }

        val theirIdentityPublicKeyEncoded = Base64.decode(theirHello.publicKeyBase64)
        val theirEphemeralBytes = Base64.decode(theirOffer.publicKeyBase64)
        val validSignature = BeamCrypto.verify(
            theirIdentityPublicKeyEncoded,
            theirEphemeralBytes,
            Base64.decode(theirOffer.signatureBase64)
        )
        if (!validSignature) {
            Log.e("Firma inválida de ${theirHello.id}, cerrando conexión")
            intent?.let { (it as? PairingIntent.DesktopCode)?.result?.complete(BeamResult.Failure("Firma inválida")) }
            connection.close()
            return@withContext
        }

        val sharedSecret = BeamCrypto.ecdh(ephemeral.privateKey, theirEphemeralBytes)
        val channel = SecureChannel(sharedSecret)
        val existing = history.get(theirHello.id)

        when {
            existing?.active == true -> {
                registerPeer(theirHello.id, theirHello.kind, theirHello.name, theirHello.publicKeyBase64, connection, channel)
            }

            intent is PairingIntent.DesktopCode -> {
                registerPeer(theirHello.id, theirHello.kind, theirHello.name, theirHello.publicKeyBase64, connection, channel)
                stateMutex.withLock { pairingResultDeferreds[theirHello.id] = intent.result }
                sendControlMessage(theirHello.id, ControlMessage.DesktopPairCodeSubmit(intent.code))
            }

            !isInitiator && myKind == PeerKind.DESKTOP && history.activeDevice(PeerKind.MOBILE) == null -> {
                registerPeer(theirHello.id, theirHello.kind, theirHello.name, theirHello.publicKeyBase64, connection, channel)
            }

            else -> {
                Log.i("Rechazando a ${theirHello.id.take(8)}: no vinculado y sin intención de emparejar")
                runCatching { IosFraming.sendRaw(connection, encodeControl(ControlMessage.LinkStateChanged(LinkState.DESVINCULADO)), channel) }
                intent?.let { (it as? PairingIntent.DesktopCode)?.result?.complete(BeamResult.Failure("Rechazado")) }
                connection.close()
            }
        }
    }

    private suspend fun registerPeer(
        id: String,
        kind: PeerKind,
        name: String,
        publicKeyBase64: String,
        connection: TcpConnection,
        channel: SecureChannel
    ) {
        val session = PeerSession(id, kind, name, publicKeyBase64, connection, channel)
        stateMutex.withLock { peers[id] = session }
        session.readJob = scope.launch { readLoop(session) }
        _connectedPeers.value = stateMutex.withLock { peers.keys.toList() }
        Log.i("Peer conectado: $id ($kind)")
    }

    private fun encodeControl(message: ControlMessage): ByteArray =
        Json.encodeToString(ControlMessage.serializer(), message).encodeToByteArray()

    private suspend fun sendControlMessage(peerId: String, message: ControlMessage) {
        send(peerId, encodeControl(message))
    }

    private suspend fun readLoop(session: PeerSession) = withContext(Dispatchers.Default) {
        while (isActive) {
            try {
                val bytes = IosFraming.receiveRaw(session.connection, session.channel)
                val control = runCatching {
                    Json.decodeFromString(ControlMessage.serializer(), bytes.decodeToString())
                }.getOrNull()
                if (control != null) {
                    handleControlMessage(session, control)
                } else {
                    _incomingMessages.emit(IncomingMessage(session.id, bytes))
                }
            } catch (e: Exception) {
                Log.e("Conexión con ${session.id} perdida: ${e.message}")
                cleanupPeer(session.id)
                return@withContext
            }
        }
    }

    private suspend fun handleControlMessage(session: PeerSession, message: ControlMessage) {
        when (message) {
            is ControlMessage.LinkStateChanged -> {
                if (message.state == LinkState.DESVINCULADO) {
                    Log.i("${session.id.take(8)} nos avisa de que ya no estamos vinculados")
                    history.unlink(session.id)
                    refreshPairingCode()
                    cleanupPeer(session.id)
                    _linkEvents.emit(LinkEvent.Unlinked(session.id))
                }
            }

            is ControlMessage.DesktopPairCodeSubmit -> {
                val expected = _pairingCode.value
                val matches = myKind == PeerKind.DESKTOP && expected != null && expected == message.code
                if (matches) {
                    history.link(session.id, session.kind, session.name, session.publicKeyBase64)
                    refreshPairingCode()
                    sendControlMessage(session.id, ControlMessage.DesktopPairCodeResult(true))
                    _linkEvents.emit(LinkEvent.Linked(session.id, session.kind, session.name))
                } else {
                    sendControlMessage(session.id, ControlMessage.DesktopPairCodeResult(false))
                    cleanupPeer(session.id)
                }
            }

            is ControlMessage.DesktopPairCodeResult -> {
                val deferred = stateMutex.withLock { pairingResultDeferreds.remove(session.id) }
                if (message.success) {
                    history.link(session.id, session.kind, session.name, session.publicKeyBase64)
                    _linkEvents.emit(LinkEvent.Linked(session.id, session.kind, session.name))
                    deferred?.complete(BeamResult.Success(Unit))
                } else {
                    cleanupPeer(session.id)
                    deferred?.complete(BeamResult.Failure("Código incorrecto"))
                }
            }
        }
    }

    private suspend fun cleanupPeer(id: String) {
        stateMutex.withLock {
            peers.remove(id)?.let { runCatching { it.connection.close() } }
        }
        _connectedPeers.value = stateMutex.withLock { peers.keys.toList() }
    }

    override fun unlink(deviceId: String) {
        scope.launch {
            history.unlink(deviceId)
            refreshPairingCode()
            runCatching { sendControlMessage(deviceId, ControlMessage.LinkStateChanged(LinkState.DESVINCULADO)) }
            cleanupPeer(deviceId)
            _linkEvents.emit(LinkEvent.Unlinked(deviceId))
        }
    }

    override suspend fun send(peerId: String, data: ByteArray, onProgress: ((Float) -> Unit)?): BeamResult<Unit> {
        val session = stateMutex.withLock { peers[peerId] }
            ?: return BeamResult.Failure("No conectado con $peerId")
        return try {
            session.writeMutex.withLock {
                withContext(Dispatchers.Default) {
                    IosFraming.sendRaw(session.connection, data, session.channel)
                    onProgress?.invoke(1f)
                }
            }
            BeamResult.Success(Unit)
        } catch (e: Exception) {
            Log.e("Error enviando a $peerId: ${e.message}")
            cleanupPeer(peerId)
            BeamResult.Failure(e.message ?: "Error de envío")
        }
    }

    override fun disconnect(peerId: String) {
        scope.launch { cleanupPeer(peerId) }
    }
}
