@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nubax.beam.library.connection

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.INADDR_ANY
import platform.posix.INADDR_BROADCAST
import platform.posix.SO_BROADCAST
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.listen
import platform.posix.recv
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval

/**
 * Sockets BSD crudos (POSIX) para el transporte de Aircom en iOS — mismo modelo mental
 * que ya usan `MeshBeamConnection.kt` (java.net.*, en jvmCommon) y el firmware ESP32
 * (beam_protocol.cpp, también BSD sockets sobre lwIP), en vez de la API asíncrona basada
 * en callbacks de Network.framework. Kotlin/Native expone `platform.posix.*` en todos sus
 * targets Apple, así que esto es igual de portable que cinterop con Network.framework,
 * pero con una forma mucho más parecida al resto de Aircom.
 *
 * `htons`/`htonl`/`inet_addr` de libc no son símbolos enlazables en Darwin (son macros/
 * builtins), así que cinterop no los expone — de ahí las versiones propias de más abajo.
 */

/** Big-endian de un puerto de 16 bits (host -> network), a mano porque htons no es enlazable en Darwin. */
private fun portToNetworkOrder(port: Int): UShort {
    val p = port and 0xFFFF
    return (((p and 0xFF) shl 8) or ((p shr 8) and 0xFF)).toUShort()
}

/** Parsea "a.b.c.d" al entero de 32 bits que espera sin_addr.s_addr (ya en orden de red). */
private fun parseIPv4(host: String): UInt {
    val parts = host.split(".").map { it.trim().toInt() and 0xFF }
    require(parts.size == 4) { "Dirección IPv4 inválida: $host" }
    return parts[0].toUInt() or (parts[1].toUInt() shl 8) or (parts[2].toUInt() shl 16) or (parts[3].toUInt() shl 24)
}

private fun ipv4ToString(networkOrderAddr: UInt): String {
    val a = networkOrderAddr
    val b0 = (a and 0xFFu).toInt()
    val b1 = ((a shr 8) and 0xFFu).toInt()
    val b2 = ((a shr 16) and 0xFFu).toInt()
    val b3 = ((a shr 24) and 0xFFu).toInt()
    return "$b0.$b1.$b2.$b3"
}

internal class PosixSocketException(message: String) : Exception(message)

internal class TcpConnection(internal val fd: Int, val remoteAddress: String) {
    fun readExactly(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var readTotal = 0
        while (readTotal < size) {
            val n = buffer.usePinned { pinned ->
                recv(fd, pinned.addressOf(readTotal), (size - readTotal).convert(), 0)
            }
            if (n <= 0) throw PosixSocketException("Conexión cerrada o error leyendo (recv=$n)")
            readTotal += n.toInt()
        }
        return buffer
    }

    fun write(data: ByteArray) {
        var written = 0
        while (written < data.size) {
            val n = data.usePinned { pinned ->
                send(fd, pinned.addressOf(written), (data.size - written).convert(), 0)
            }
            if (n <= 0) throw PosixSocketException("Error escribiendo (send=$n)")
            written += n.toInt()
        }
    }

    fun close() {
        close(fd)
    }
}

internal fun openTcpServer(port: Int): Int {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) throw PosixSocketException("No se pudo crear el socket TCP servidor")
    memScoped {
        val opt = alloc<IntVar>()
        opt.value = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, opt.ptr, sizeOf<IntVar>().convert())
    }
    memScoped {
        val addr = alloc<sockaddr_in>()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = portToNetworkOrder(port)
        addr.sin_addr.s_addr = INADDR_ANY
        val bound = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        if (bound != 0) {
            close(fd)
            throw PosixSocketException("No se pudo hacer bind en el puerto $port")
        }
    }
    if (listen(fd, 8) != 0) {
        close(fd)
        throw PosixSocketException("No se pudo escuchar en el puerto $port")
    }
    return fd
}

internal fun acceptConnection(serverFd: Int): TcpConnection = memScoped {
    val addr = alloc<sockaddr_in>()
    val len = alloc<UIntVar>()
    len.value = sizeOf<sockaddr_in>().convert()
    val clientFd = accept(serverFd, addr.ptr.reinterpret(), len.ptr)
    if (clientFd < 0) throw PosixSocketException("accept() falló")
    TcpConnection(clientFd, ipv4ToString(addr.sin_addr.s_addr))
}

internal fun connectTcp(host: String, port: Int): TcpConnection {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) throw PosixSocketException("No se pudo crear el socket TCP saliente")
    memScoped {
        val addr = alloc<sockaddr_in>()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = portToNetworkOrder(port)
        addr.sin_addr.s_addr = parseIPv4(host)
        val result = connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        if (result != 0) {
            close(fd)
            throw PosixSocketException("No se pudo conectar a $host:$port")
        }
    }
    return TcpConnection(fd, host)
}

internal fun closeServer(fd: Int) {
    close(fd)
}

internal class UdpSocket(private val fd: Int) {
    fun sendBroadcast(port: Int, data: ByteArray) = sendTo(INADDR_BROADCAST, port, data)

    fun sendTo(host: String, port: Int, data: ByteArray) = sendTo(parseIPv4(host), port, data)

    private fun sendTo(networkOrderAddr: UInt, port: Int, data: ByteArray) {
        memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = portToNetworkOrder(port)
            addr.sin_addr.s_addr = networkOrderAddr
            data.usePinned { pinned ->
                sendto(fd, pinned.addressOf(0), data.size.convert(), 0, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            }
        }
    }

    /** Bloqueante: espera el siguiente datagrama. Null si el socket se cerró o hubo error. */
    fun receive(maxSize: Int = 2048): Pair<ByteArray, String>? {
        memScoped {
            val buffer = ByteArray(maxSize)
            val addr = alloc<sockaddr_in>()
            val len = alloc<UIntVar>()
            len.value = sizeOf<sockaddr_in>().convert()
            val n = buffer.usePinned { pinned ->
                recvfrom(fd, pinned.addressOf(0), maxSize.convert(), 0, addr.ptr.reinterpret(), len.ptr)
            }
            if (n <= 0) return null
            return buffer.copyOf(n.toInt()) to ipv4ToString(addr.sin_addr.s_addr)
        }
    }

    fun close() {
        close(fd)
    }
}

internal fun openUdpBeaconSocket(port: Int): UdpSocket {
    val fd = socket(AF_INET, SOCK_DGRAM, 0)
    if (fd < 0) throw PosixSocketException("No se pudo crear el socket UDP")
    memScoped {
        val opt = alloc<IntVar>()
        opt.value = 1
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, opt.ptr, sizeOf<IntVar>().convert())
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, opt.ptr, sizeOf<IntVar>().convert())
        // Igual que soTimeout=1000 en el DatagramSocket de la JVM: recvfrom() no debe
        // bloquear para siempre, para poder revisar isActive() periódicamente en el loop.
        val timeout = alloc<timeval>()
        timeout.tv_sec = 1
        timeout.tv_usec = 0
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())
    }
    memScoped {
        val addr = alloc<sockaddr_in>()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = portToNetworkOrder(port)
        addr.sin_addr.s_addr = INADDR_ANY
        if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
            close(fd)
            throw PosixSocketException("No se pudo hacer bind del socket UDP en el puerto $port")
        }
    }
    return UdpSocket(fd)
}
