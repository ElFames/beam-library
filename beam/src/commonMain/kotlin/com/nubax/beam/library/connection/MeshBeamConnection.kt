package com.nubax.beam.library.connection

import com.nubax.beam.library.connectivity.NetworkSocketBinder
import com.nubax.beam.library.core.BeamCrypto
import com.nubax.beam.library.core.BeamProtocol
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
import kotlinx.coroutines.IO
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.Charset

/**
 * Implementación única de [BeamConnection] para Desktop y Android (ambos JVM).
 * Simétrica: cualquier instancia anuncia, descubre, acepta y conecta. El pinganillo
 * (firmware C++ aparte) solo implementa la mitad de acceptor de este mismo
 * protocolo — ver PROJECT.md §2/§3 para el modelo de emparejamiento completo.
 */
internal class MeshBeamConnection(
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
        val socket: Socket,
        val channel: SecureChannel,
        val writeMutex: Mutex = Mutex(),
        var readJob: Job? = null
    )

    /** Por qué se está intentando ESTA conexión saliente — determina cómo resolver el handshake. */
    private sealed class PairingIntent {
        data class PinganilloCredentials(val result: CompletableDeferred<BeamResult<Unit>>) : PairingIntent()
        data class DesktopCode(val code: String, val result: CompletableDeferred<BeamResult<Unit>>) : PairingIntent()
    }

    /** Cuánto esperar tras conectar, sin recibir un rechazo, antes de dar la vinculación por buena. */
    private val pinganilloLinkGraceMs = 2_000L

    private lateinit var identity: DeviceIdentity
    private lateinit var history: DeviceHistoryStore
    private var deviceName: String = "UDIS device"
    private var myKind: PeerKind = PeerKind.ANDROID

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stateMutex = Mutex()

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var beaconSendJob: Job? = null
    private var beaconListenJob: Job? = null
    private var acceptJob: Job? = null

    private val peers = mutableMapOf<String, PeerSession>()
    private val connectingIds = mutableSetOf<String>()
    private val pairingResultDeferreds = mutableMapOf<String, CompletableDeferred<BeamResult<Unit>>>()

    // Enlace de red adicional (p. ej. el pinganillo cuando Android lo une como red "solo
    // local"): el socket UDP/servidor por defecto ya escucha en todas las interfaces sin
    // necesitar nada especial, así que lo único que hace falta atar explícitamente a esta
    // red es lo que SALE de este dispositivo (beacon y conexión TCP saliente).
    private var networkBinder: NetworkSocketBinder? = null
    private var attachedBeaconJob: Job? = null
    private var attachedSocket: DatagramSocket? = null

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

        udpSocket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(java.net.InetSocketAddress(beaconPort))
            soTimeout = 1000
        }
        serverSocket = ServerSocket(tcpPort).apply { reuseAddress = true }

        refreshPairingCode()
        beaconSendJob = scope.launch { beaconSendLoop() }
        beaconListenJob = scope.launch { beaconListenLoop() }
        acceptJob = scope.launch { acceptLoop() }
        Log.i("Mesh iniciado: id=${identity.id} kind=$myKind tcpPort=$tcpPort beaconPort=$beaconPort")
    }

    override fun stop() {
        beaconSendJob?.cancel()
        beaconListenJob?.cancel()
        acceptJob?.cancel()
        scope.launch {
            stateMutex.withLock {
                peers.values.forEach { runCatching { it.socket.close() } }
                peers.clear()
            }
        }
        runCatching { serverSocket?.close() }
        runCatching { udpSocket?.close() }
        _connectedPeers.value = emptyList()
        _discoveredDevices.value = emptyList()
    }

    /** Solo hay código mientras seamos un Desktop sin ningún Android activo vinculado. */
    private fun refreshPairingCode() {
        _pairingCode.value = if (myKind == PeerKind.DESKTOP && history.activeDevice(PeerKind.ANDROID) == null) {
            (100_000..999_999).random().toString()
        } else {
            null
        }
    }

    // ---------------------------------------------------------------------
    // Discovery
    // ---------------------------------------------------------------------

    /**
     * Direcciones de broadcast a las que probar, además de la global 255.255.255.255.
     * Algunos routers/mesh (o restricciones de un lado, p. ej. permiso de red local
     * de macOS) dejan pasar el broadcast dirigido a la subred (p. ej. 192.168.1.255)
     * pero no el broadcast limitado, o al revés — probar los dos multiplica las
     * posibilidades de que el beacon llegue de verdad.
     */
    private fun broadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                iface.interfaceAddresses.forEach { it.broadcast?.let(addresses::add) }
            }
        }
        addresses.add(InetAddress.getByName("255.255.255.255"))
        return addresses.distinct()
    }

    private fun currentBeacon() = Beacon(identity.id, deviceName, myKind, tcpPort)

    private suspend fun beaconSendLoop() = withContext(Dispatchers.IO) {
        val socket = udpSocket ?: return@withContext
        val targets = broadcastAddresses()
        Log.i("Beacon: enviando a ${targets.joinToString { it.hostAddress ?: "?" }} (puerto $beaconPort)")
        while (isActive) {
            try {
                val bytes = BeamProtocol.json.encodeToString(currentBeacon()).encodeToByteArray()
                targets.forEach { target -> socket.send(DatagramPacket(bytes, bytes.size, target, beaconPort)) }
            } catch (e: Exception) {
                Log.e("Error enviando beacon: ${e.message}")
            }
            delay(beaconIntervalMs)
        }
    }

    private suspend fun beaconListenLoop() = withContext(Dispatchers.IO) {
        val socket = udpSocket ?: return@withContext
        val buffer = ByteArray(1024)
        while (isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val beacon = runCatching {
                    val text =
                        java.lang.String(packet.data, 0, packet.length, Charset.forName("UTF-8"))
                    BeamProtocol.json.decodeFromString<Beacon>(text.toString())
                }.getOrNull() ?: continue

                if (beacon.id == identity.id) continue
                val senderAddress = packet.address?.hostAddress ?: continue
                onBeaconReceived(beacon, senderAddress)
            } catch (_: SocketTimeoutException) {
                // normal, solo para poder revisar isActive periódicamente
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

        // La reconexión automática (beacon -> conectar solos) SOLO aplica a un peer YA
        // vinculado y activo. Un emparejamiento nuevo es siempre una acción explícita
        // (pairPinganillo/pairDesktopWithCode) — nunca algo que dispare el discovery pasivo.
        if (!trusted) return

        val alreadyHandled = stateMutex.withLock {
            peers.containsKey(beacon.id) || beacon.id in connectingIds
        }
        if (alreadyHandled) return

        // El pinganillo nunca conecta hacia fuera (su firmware solo acepta), así que aquí
        // SIEMPRE hay que ir a buscarlo. Entre Android y Desktop (ambos simétricos) se
        // desempata por id para que no intenten conectar los dos a la vez.
        val shouldInitiate = beacon.kind == PeerKind.PINGANILLO || identity.id < beacon.id
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

    private suspend fun openSocket(host: String, port: Int): Socket {
        // Si hay una red adicional adjuntada (el pinganillo en Android), probamos primero
        // a conectar atando el socket a esa red explícitamente: un socket normal no
        // encontraría ruta hacia una red "solo local" y fallaría rápido, así que probamos
        // esta vía primero y si no aplica (el peer real está en la red por defecto, p. ej.
        // el desktop en la WiFi de casa) caemos al camino de siempre sin tocar nada.
        val binder = networkBinder
        if (binder != null) {
            val bound = runCatching {
                val socket = Socket()
                binder.bind(socket)
                socket.connect(java.net.InetSocketAddress(host, port), 4000)
                socket
            }
            bound.getOrNull()?.let { return it }
        }
        return Socket(host, port)
    }

    override suspend fun connectDirect(host: String, port: Int): BeamResult<Unit> = withContext(Dispatchers.IO) {
        try {
            performHandshake(openSocket(host, port), isInitiator = true, intent = null)
            BeamResult.Success(Unit)
        } catch (e: Exception) {
            Log.e("Error conectando a $host:$port -> ${e.message}")
            BeamResult.Failure(e.message ?: "Error de conexión")
        }
    }

    override suspend fun pairPinganillo(host: String, port: Int): BeamResult<Unit> = withContext(Dispatchers.IO) {
        val result = CompletableDeferred<BeamResult<Unit>>()
        try {
            performHandshake(openSocket(host, port), isInitiator = true, intent = PairingIntent.PinganilloCredentials(result))
            // El pinganillo puede rechazar (ya vinculado con otro Android) DESPUÉS del
            // handshake — hay que esperar un poco antes de dar la vinculación por buena,
            // ver el "período de gracia" en performHandshake.
            withTimeout(codePairingTimeoutMs) { result.await() }
        } catch (e: Exception) {
            Log.e("Error emparejando con pinganillo $host:$port -> ${e.message}")
            BeamResult.Failure(e.message ?: "Error de emparejamiento")
        }
    }

    override suspend fun pairDesktopWithCode(host: String, port: Int, code: String): BeamResult<Unit> = withContext(Dispatchers.IO) {
        val result = CompletableDeferred<BeamResult<Unit>>()
        try {
            performHandshake(openSocket(host, port), isInitiator = true, intent = PairingIntent.DesktopCode(code, result))
            withTimeout(codePairingTimeoutMs) { result.await() }
        } catch (e: TimeoutCancellationException) {
            BeamResult.Failure("El desktop no respondió al código a tiempo")
        } catch (e: Exception) {
            Log.e("Error emparejando con desktop $host:$port -> ${e.message}")
            BeamResult.Failure(e.message ?: "Error de emparejamiento")
        }
    }

    // ---------------------------------------------------------------------
    // Red adicional (pinganillo)
    // ---------------------------------------------------------------------

    override fun attachNetwork(binder: NetworkSocketBinder) {
        detachNetwork()
        networkBinder = binder
        attachedBeaconJob = scope.launch { attachedBeaconSendLoop(binder) }
        Log.i("Red adicional adjuntada (pinganillo u otra interfaz local)")
    }

    override fun detachNetwork() {
        attachedBeaconJob?.cancel()
        attachedBeaconJob = null
        runCatching { attachedSocket?.close() }
        attachedSocket = null
        networkBinder = null
    }

    /**
     * El socket UDP por defecto ya RECIBE beacons de cualquier interfaz (un socket wildcard
     * no distingue por qué interfaz llegó el paquete), así que solo hace falta un envío de
     * beacon adicional atado a la red nueva para que el pinganillo nos vea a nosotros.
     */
    private suspend fun attachedBeaconSendLoop(binder: NetworkSocketBinder) = withContext(Dispatchers.IO) {
        if (!this@MeshBeamConnection::identity.isInitialized) {
            Log.e("attachNetwork llamado antes de start(); se ignora")
            return@withContext
        }
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                binder.bind(this)
            }
        } catch (e: Exception) {
            Log.e("No se pudo preparar el socket de la red adicional: ${e.message}")
            return@withContext
        }
        attachedSocket = socket
        val target = InetAddress.getByName("255.255.255.255")
        while (isActive) {
            try {
                val bytes = BeamProtocol.json.encodeToString(currentBeacon()).encodeToByteArray()
                socket.send(DatagramPacket(bytes, bytes.size, target, beaconPort))
            } catch (e: Exception) {
                Log.e("Error enviando beacon por la red adicional: ${e.message}")
            }
            delay(beaconIntervalMs)
        }
    }

    private suspend fun acceptLoop() = withContext(Dispatchers.IO) {
        val server = serverSocket ?: return@withContext
        while (isActive) {
            try {
                val client = server.accept()
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
     * Evita interbloqueos por lectura simultánea sin necesitar coordinación extra.
     *
     * Tras el ECDH, quién queda "vinculado" (no solo conectado) depende de [intent]
     * — ver PROJECT.md §2.3/§2.4 para los dos flujos, y la rama `else` de más abajo
     * para el caso "ni vinculado, ni intención, ni auto-link de acceptor: rechazar".
     */
    private suspend fun performHandshake(socket: Socket, isInitiator: Boolean, intent: PairingIntent?) = withContext(Dispatchers.IO) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val myHello = HandshakeHello(identity.id, deviceName, myKind, BeamCrypto.toBase64(identity.publicKeyEncoded))

        val theirHello: HandshakeHello
        if (isInitiator) {
            BeamProtocol.sendPlainObject(output, myHello)
            theirHello = BeamProtocol.receivePlainObject(input)
        } else {
            theirHello = BeamProtocol.receivePlainObject(input)
            BeamProtocol.sendPlainObject(output, myHello)
        }

        if (theirHello.id == identity.id) {
            socket.close()
            return@withContext
        }

        val ephemeral = BeamCrypto.generateKeyPair()
        val mySignature = BeamCrypto.sign(identity.keyPair.private, ephemeral.public.encoded)
        val myOffer = EphemeralOffer(BeamCrypto.toBase64(ephemeral.public.encoded), BeamCrypto.toBase64(mySignature))

        val theirOffer: EphemeralOffer
        if (isInitiator) {
            BeamProtocol.sendPlainObject(output, myOffer)
            theirOffer = BeamProtocol.receivePlainObject(input)
        } else {
            theirOffer = BeamProtocol.receivePlainObject(input)
            BeamProtocol.sendPlainObject(output, myOffer)
        }

        val theirIdentityPublicKey = BeamCrypto.decodePublicKey(BeamCrypto.fromBase64(theirHello.publicKeyBase64))
        val theirEphemeralBytes = BeamCrypto.fromBase64(theirOffer.publicKeyBase64)
        val validSignature = BeamCrypto.verify(
            theirIdentityPublicKey,
            theirEphemeralBytes,
            BeamCrypto.fromBase64(theirOffer.signatureBase64)
        )
        if (!validSignature) {
            Log.e("Firma inválida de ${theirHello.id}, cerrando conexión")
            intent?.let { (it as? PairingIntent.DesktopCode)?.result?.complete(BeamResult.Failure("Firma inválida")) }
            socket.close()
            return@withContext
        }

        val sharedSecret = BeamCrypto.ecdh(ephemeral.private, theirEphemeralBytes)
        val channel = SecureChannel(sharedSecret)
        val existing = history.get(theirHello.id)

        when {
            existing?.active == true -> {
                // Ya vinculado y activo: reconexión normal, sin volver a pasar por pairing.
                registerPeer(theirHello.id, theirHello.kind, theirHello.name, theirHello.publicKeyBase64, socket, channel)
            }

            intent is PairingIntent.PinganilloCredentials -> {
                // Conocer las credenciales WiFi del pinganillo YA es la prueba de autorización,
                // PERO el pinganillo puede rechazar después del handshake si ya está vinculado
                // con otro Android (manda LinkStateChanged(DESVINCULADO) y cierra). Por eso no
                // se vincula aquí sin más: se registra el peer (para poder recibir ese posible
                // rechazo) y se guarda el deferred; si no llega rechazo en el plazo de gracia,
                // se da la vinculación por buena (ver el scope.launch más abajo).
                registerPeer(theirHello.id, theirHello.kind, theirHello.name, theirHello.publicKeyBase64, socket, channel)
                stateMutex.withLock { pairingResultDeferreds[theirHello.id] = intent.result }
                scope.launch {
                    delay(pinganilloLinkGraceMs)
                    val stillPending = stateMutex.withLock { pairingResultDeferreds.remove(theirHello.id) }
                    if (stillPending != null) {
                        history.link(theirHello.id, theirHello.kind, theirHello.name, theirHello.publicKeyBase64)
                        refreshPairingCode()
                        _linkEvents.emit(LinkEvent.Linked(theirHello.id, theirHello.kind, theirHello.name))
                        stillPending.complete(BeamResult.Success(Unit))
                    }
                }
            }

            intent is PairingIntent.DesktopCode -> {
                // Conectado, pero SIN vincular todavía: falta que el desktop confirme el código.
                registerPeer(theirHello.id, theirHello.kind, theirHello.name, theirHello.publicKeyBase64, socket, channel)
                stateMutex.withLock { pairingResultDeferreds[theirHello.id] = intent.result }
                sendControlMessage(theirHello.id, ControlMessage.DesktopPairCodeSubmit(intent.code))
            }

            !isInitiator && myKind == PeerKind.PINGANILLO && history.activeDevice(PeerKind.ANDROID) == null -> {
                // Acceptor de un pinganillo virgen: quien completó el handshake ya demostró
                // conocer las credenciales de mi propio AP -> autovincular sin más pasos.
                history.link(theirHello.id, theirHello.kind, theirHello.name, theirHello.publicKeyBase64)
                registerPeer(theirHello.id, theirHello.kind, theirHello.name, theirHello.publicKeyBase64, socket, channel)
                _linkEvents.emit(LinkEvent.Linked(theirHello.id, theirHello.kind, theirHello.name))
            }

            !isInitiator && myKind == PeerKind.DESKTOP && history.activeDevice(PeerKind.ANDROID) == null -> {
                // Acceptor de un desktop sin vincular: registro temporal, espero DesktopPairCodeSubmit.
                registerPeer(theirHello.id, theirHello.kind, theirHello.name, theirHello.publicKeyBase64, socket, channel)
            }

            else -> {
                // Ni vinculado, ni intención explícita, ni auto-link de acceptor aplicable:
                // o es un desconocido, o ya tengo otro dispositivo de ese tipo vinculado.
                Log.i("Rechazando a ${theirHello.id.take(8)}: no vinculado y sin intención de emparejar")
                runCatching { BeamProtocol.sendRaw(output, encodeControl(ControlMessage.LinkStateChanged(LinkState.DESVINCULADO)), channel) }
                intent?.let { (it as? PairingIntent.DesktopCode)?.result?.complete(BeamResult.Failure("Rechazado")) }
                socket.close()
            }
        }
    }

    private suspend fun registerPeer(
        id: String,
        kind: PeerKind,
        name: String,
        publicKeyBase64: String,
        socket: Socket,
        channel: SecureChannel
    ) {
        val session = PeerSession(id, kind, name, publicKeyBase64, socket, channel)
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

    private suspend fun readLoop(session: PeerSession) = withContext(Dispatchers.IO) {
        val input = session.socket.getInputStream()
        while (isActive) {
            try {
                val bytes = BeamProtocol.receiveRaw(input, session.channel)
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
                    val pendingPairing = stateMutex.withLock { pairingResultDeferreds.remove(session.id) }
                    if (pendingPairing != null) {
                        // No era una vinculación ya existente rompiéndose: era un intento de
                        // pairPinganillo() en curso que el pinganillo rechazó (ya vinculado
                        // con otro Android) — resolver ese intento como fallo, no como unlink.
                        Log.i("${session.id.take(8)} rechazó el emparejamiento (ya vinculado con otro)")
                        pendingPairing.complete(BeamResult.Failure("Ya está vinculado con otro dispositivo"))
                        cleanupPeer(session.id)
                    } else {
                        Log.i("${session.id.take(8)} nos avisa de que ya no estamos vinculados")
                        history.unlink(session.id)
                        refreshPairingCode()
                        cleanupPeer(session.id)
                        _linkEvents.emit(LinkEvent.Unlinked(session.id))
                    }
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
            peers.remove(id)?.let { runCatching { it.socket.close() } }
        }
        _connectedPeers.value = stateMutex.withLock { peers.keys.toList() }
    }

    // ---------------------------------------------------------------------
    // Desvinculación
    // ---------------------------------------------------------------------

    override fun unlink(deviceId: String) {
        scope.launch {
            history.unlink(deviceId)
            refreshPairingCode()
            runCatching { sendControlMessage(deviceId, ControlMessage.LinkStateChanged(LinkState.DESVINCULADO)) }
            cleanupPeer(deviceId)
            _linkEvents.emit(LinkEvent.Unlinked(deviceId))
        }
    }

    // ---------------------------------------------------------------------
    // Mensajería
    // ---------------------------------------------------------------------

    override suspend fun send(peerId: String, data: ByteArray, onProgress: ((Float) -> Unit)?): BeamResult<Unit> {
        val session = stateMutex.withLock { peers[peerId] }
            ?: return BeamResult.Failure("No conectado con $peerId")
        return try {
            session.writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    BeamProtocol.sendRawWithProgress(
                        session.socket.getOutputStream(),
                        data,
                        session.channel,
                        onProgress = onProgress
                    )
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
