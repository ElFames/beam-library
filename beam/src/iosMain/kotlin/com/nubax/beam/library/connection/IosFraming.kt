package com.nubax.beam.library.connection

import com.nubax.beam.library.core.SecureChannel
import kotlinx.serialization.json.Json

/**
 * Framing de 4 bytes de longitud + payload, igual que `BeamProtocol.kt` (jvmCommon), pero
 * operando sobre [TcpConnection] (sockets BSD crudos) en vez de InputStream/OutputStream.
 */
internal object IosFraming {
    val json = Json { ignoreUnknownKeys = true }

    private fun sizeToBytes(size: Int): ByteArray = byteArrayOf(
        (size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()
    )

    private fun bytesToSize(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)

    private fun writeSizePrefixed(connection: TcpConnection, bytes: ByteArray) {
        connection.write(sizeToBytes(bytes.size))
        connection.write(bytes)
    }

    private fun readSizePrefixed(connection: TcpConnection): ByteArray {
        val size = bytesToSize(connection.readExactly(4))
        return connection.readExactly(size)
    }

    fun sendRaw(connection: TcpConnection, bytes: ByteArray, channel: SecureChannel) {
        writeSizePrefixed(connection, channel.encrypt(bytes))
    }

    fun receiveRaw(connection: TcpConnection, channel: SecureChannel): ByteArray {
        val encrypted = readSizePrefixed(connection)
        return channel.decrypt(encrypted)
    }

    inline fun <reified T> sendPlainObject(connection: TcpConnection, data: T) {
        writeSizePrefixed(connection, json.encodeToString(data).encodeToByteArray())
    }

    inline fun <reified T> receivePlainObject(connection: TcpConnection): T {
        val bytes = readSizePrefixed(connection)
        return json.decodeFromString<T>(bytes.decodeToString())
    }
}
