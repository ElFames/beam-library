package com.nubax.beam.library.connection

import com.nubax.beam.library.connectivity.NetworkSocketBinder
import com.nubax.beam.library.core.BeamCrypto
import com.nubax.beam.library.core.BeamProtocol
import com.nubax.beam.library.core.DeviceIdentity
import com.nubax.beam.library.core.Log
import com.nubax.beam.library.core.SecureChannel
import com.nubax.beam.library.core.TrustStore
import com.nubax.beam.library.core.PairedDevice
import com.nubax.beam.library.sdk.models.BeamResult
import com.nubax.beam.library.sdk.models.EphemeralOffer
import com.nubax.beam.library.sdk.models.HandshakeHello
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Implementación única de [BeamConnection] para Desktop y Android (ambos JVM).
 * Antes había dos clases con roles fijos (Desktop siempre servidor, Android siempre
 * cliente); esta es simétrica: cualquier instancia anuncia, descubre, acepta y conecta,
 * lo cual hace falta en cuanto hay más de dos nodos hablando entre sí (desktop, móvil,
 * pinganillo) porque ya no hay un "servidor" fijo al que todos apunten.
 */
internal class MeshBeamConnection(
    private val beaconPort: Int = 8888,
    private val tcpPort: Int = 9999,
    private val beaconIntervalMs: Long = 3000,
    private val pairingTimeoutMs: Long = 60_000
) : BeamConnection {

    private class PeerSession(
        val id: String,
        val socket: Socket,
        val channel: SecureChannel,
        val writeMutex: Mutex = Mutex(),
        var readJob: Job? = null
    )

    private class PendingPairing(
        val socket: Socket,
        val channel: SecureChannel,
        val remotePublicKeyBase64: String,
        val name: String,
        var timeoutJob: Job? = null
    )

    private lateinit var identity: DeviceIdentity
    private lateinit var trustStore: TrustStore
    private var deviceName: String = "UDIS device"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stateMutex = Mutex()

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var beaconSendJob: Job? = null
    private var beaconListenJob: Job? = null
    private var acceptJob: Job? = null

    private val peers = mutableMapOf<String, PeerSession>()
    private val pendingPairings = mutableMapOf<String, PendingPairing>()
    private val connectingIds = mutableSetOf<String>()

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

    private val _pairingRequests = MutableSharedFlow<PairingRequestEvent>(extraBufferCapacity = 16)
    override val pairingRequests = _pairingRequests.asSharedFlow()

    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    override val incomingMessages = _incomingMessages.asSharedFlow()

    override fun start(identity: DeviceIdentity, trustStore: TrustStore, deviceName: String) {
        this.identity = identity
        this.trustStore = trustStore
        this.deviceName = deviceName

        udpSocket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(java.net.InetSocketAddress(beaconPort))
            soTimeout = 1000
        }
        serverSocket = ServerSocket(tcpPort).apply { reuseAddress = true }

        beaconSendJob = scope.launch { beaconSendLoop() }
        beaconListenJob = scope.launch { beaconListenLoop() }
        acceptJob = scope.launch { acceptLoop() }
        Log.i("Mesh iniciado: id=${identity.id} tcpPort=$tcpPort beaconPort=$beaconPort")
    }

    override fun stop() {
        beaconSendJob?.cancel()
        beaconListenJob?.cancel()
        acceptJob?.cancel()
        scope.launch {
            stateMutex.withLock {
                peers.values.forEach { runCatching { it.socket.close() } }
                peers.clear()
                pendingPairings.values.forEach { runCatching { it.socket.close() } }
                pendingPairings.clear()
            }
        }
        runCatching { serverSocket?.close() }
        runCatching { udpSocket?.close() }
        _connectedPeers.value = emptyList()
        _discoveredDevices.value = emptyList()
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

    private suspend fun beaconSendLoop() = withContext(Dispatchers.IO) {
        val socket = udpSocket ?: return@withContext
        val targets = broadcastAddresses()
        Log.i("Beacon: enviando a ${targets.joinToString { it.hostAddress ?: "?" }} (puerto $beaconPort)")
        var loggedFirstSend = false
        while (isActive) {
            try {
                val beacon = Beacon(identity.id, deviceName, tcpPort)
                val bytes = BeamProtocol.json.encodeToString(beacon).encodeToByteArray()
                targets.forEach { target -> socket.send(DatagramPacket(bytes, bytes.size, target, beaconPort)) }
                if (!loggedFirstSend) {
                    Log.i("Primer beacon enviado sin errores")
                    loggedFirstSend = true
                }
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
                    BeamProtocol.json.decodeFromString<Beacon>(String(packet.data, 0, packet.length))
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
        val trusted = trustStore.isTrusted(beacon.id)
        val isNewSighting = stateMutex.withLock {
            val current = _discoveredDevices.value
            val wasKnown = current.any { it.id == beacon.id }
            _discoveredDevices.value = current.filterNot { it.id == beacon.id } +
                DiscoveredDevice(beacon.id, beacon.name, address, beacon.tcpPort, trusted)
            !wasKnown
        }
        if (isNewSighting) Log.i("Descubierto: ${beacon.name} (${beacon.id.take(8)}) en $address")

        val alreadyHandled = stateMutex.withLock {
            peers.containsKey(beacon.id) || pendingPairings.containsKey(beacon.id) || beacon.id in connectingIds
        }
        if (alreadyHandled) return

        // Regla de arbitraje: solo el id "menor" abre la conexión saliente; el otro la aceptará.
        // Determinista y sin coordinación de red, evita que ambos extremos conecten a la vez.
        if (identity.id < beacon.id) {
            if (isNewSighting) Log.i("Mi id (${identity.id.take(8)}) es menor, intento conectar a ${beacon.name}")
            scope.launch { attemptConnect(address, beacon.tcpPort, beacon.id) }
        } else if (isNewSighting) {
            Log.i("Mi id (${identity.id.take(8)}) es mayor, espero a que ${beacon.name} conecte")
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

    override suspend fun connectDirect(host: String, port: Int): BeamResult<Unit> = withContext(Dispatchers.IO) {
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
            bound.getOrNull()?.let { socket ->
                return@withContext try {
                    performHandshake(socket, isInitiator = true)
                    BeamResult.Success(Unit)
                } catch (e: Exception) {
                    Log.e("Error en handshake (red adicional) con $host:$port -> ${e.message}")
                    BeamResult.Failure(e.message ?: "Error de conexión")
                }
            }
        }
        try {
            val socket = Socket(host, port)
            performHandshake(socket, isInitiator = true)
            BeamResult.Success(Unit)
        } catch (e: Exception) {
            Log.e("Error conectando a $host:$port -> ${e.message}")
            BeamResult.Failure(e.message ?: "Error de conexión")
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
                val beacon = Beacon(identity.id, deviceName, tcpPort)
                val bytes = BeamProtocol.json.encodeToString(beacon).encodeToByteArray()
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
                    runCatching { performHandshake(client, isInitiator = false) }
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
     */
    private suspend fun performHandshake(socket: Socket, isInitiator: Boolean) = withContext(Dispatchers.IO) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val myHello = HandshakeHello(identity.id, deviceName, BeamCrypto.toBase64(identity.publicKeyEncoded))

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
            socket.close()
            return@withContext
        }

        val sharedSecret = BeamCrypto.ecdh(ephemeral.private, theirEphemeralBytes)
        val channel = SecureChannel(sharedSecret)

        if (trustStore.isTrusted(theirHello.id)) {
            registerPeer(theirHello.id, socket, channel)
        } else {
            val pending = PendingPairing(socket, channel, theirHello.publicKeyBase64, theirHello.name)
            stateMutex.withLock { pendingPairings[theirHello.id] = pending }
            pending.timeoutJob = scope.launch {
                delay(pairingTimeoutMs)
                if (stateMutex.withLock { pendingPairings.containsKey(theirHello.id) }) {
                    Log.i("Pairing con ${theirHello.id} expiró sin confirmación")
                    rejectPairing(theirHello.id)
                }
            }
            _pairingRequests.emit(PairingRequestEvent(theirHello.id, theirHello.name, channel.fingerprint))
        }
    }

    private suspend fun registerPeer(id: String, socket: Socket, channel: SecureChannel) {
        val session = PeerSession(id, socket, channel)
        stateMutex.withLock { peers[id] = session }
        session.readJob = scope.launch { readLoop(session) }
        _connectedPeers.value = stateMutex.withLock { peers.keys.toList() }
        Log.i("Peer conectado: $id")
    }

    private suspend fun readLoop(session: PeerSession) = withContext(Dispatchers.IO) {
        val input = session.socket.getInputStream()
        while (isActive) {
            try {
                val bytes = BeamProtocol.receiveRaw(input, session.channel)
                _incomingMessages.emit(IncomingMessage(session.id, bytes))
            } catch (e: Exception) {
                Log.e("Conexión con ${session.id} perdida: ${e.message}")
                cleanupPeer(session.id)
                return@withContext
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
    // Pairing
    // ---------------------------------------------------------------------

    override suspend fun confirmPairing(peerId: String): BeamResult<Unit> {
        val pending = stateMutex.withLock { pendingPairings.remove(peerId) }
            ?: return BeamResult.Failure("No hay pairing pendiente con $peerId")
        pending.timeoutJob?.cancel()

        trustStore.add(PairedDevice(peerId, pending.remotePublicKeyBase64, pending.name))
        registerPeer(peerId, pending.socket, pending.channel)
        return BeamResult.Success(Unit)
    }

    override fun rejectPairing(peerId: String) {
        scope.launch {
            val pending = stateMutex.withLock { pendingPairings.remove(peerId) }
            pending?.timeoutJob?.cancel()
            runCatching { pending?.socket?.close() }
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
