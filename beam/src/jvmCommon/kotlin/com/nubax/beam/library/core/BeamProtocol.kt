package com.nubax.beam.library.core

import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

internal object BeamProtocol {
    val json = Json { ignoreUnknownKeys = true }

    private fun readExactly(inputStream: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var bytesRead = 0
        while (bytesRead < size) {
            val result = inputStream.read(buffer, bytesRead, size - bytesRead)
            if (result == -1) throw Exception("Connection interrupted")
            bytesRead += result
        }
        return buffer
    }

    private fun readSize(inputStream: InputStream): Int {
        val b1 = inputStream.read().takeIf { it != -1 } ?: throw Exception("Stream closed")
        val b2 = inputStream.read().takeIf { it != -1 } ?: throw Exception("Stream closed")
        val b3 = inputStream.read().takeIf { it != -1 } ?: throw Exception("Stream closed")
        val b4 = inputStream.read().takeIf { it != -1 } ?: throw Exception("Stream closed")
        return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
    }

    private fun writeSizePrefixed(outputStream: OutputStream, bytes: ByteArray) {
        val size = bytes.size
        outputStream.write(size shr 24)
        outputStream.write(size shr 16)
        outputStream.write(size shr 8)
        outputStream.write(size)
        outputStream.write(bytes)
        outputStream.flush()
    }

    /** Envía datos cifrados con la sesión de ESTE peer. */
    fun sendRaw(outputStream: OutputStream, bytes: ByteArray, channel: SecureChannel) {
        writeSizePrefixed(outputStream, channel.encrypt(bytes))
    }

    /**
     * Igual que [sendRaw] pero escribiendo el bloque cifrado en trozos, reportando
     * progreso — pensado para archivos/imágenes donde la UI quiere una barra real.
     * El cifrado en sí sigue siendo de una vez (AES-GCM necesita el bloque completo),
     * solo la escritura al socket se trocea.
     */
    fun sendRawWithProgress(
        outputStream: OutputStream,
        bytes: ByteArray,
        channel: SecureChannel,
        chunkSize: Int = 64 * 1024,
        onProgress: ((Float) -> Unit)? = null
    ) {
        val encrypted = channel.encrypt(bytes)
        val size = encrypted.size
        outputStream.write(size shr 24)
        outputStream.write(size shr 16)
        outputStream.write(size shr 8)
        outputStream.write(size)

        var written = 0
        while (written < encrypted.size) {
            val len = minOf(chunkSize, encrypted.size - written)
            outputStream.write(encrypted, written, len)
            written += len
            onProgress?.invoke(written.toFloat() / encrypted.size.toFloat())
        }
        outputStream.flush()
    }

    fun receiveRaw(inputStream: InputStream, channel: SecureChannel): ByteArray {
        val size = readSize(inputStream)
        val buffer = readExactly(inputStream, size)
        return channel.decrypt(buffer)
    }

    /** Envío en claro (sin cifrar) usado SOLO durante el handshake, antes de tener sesión. */
    inline fun <reified T> sendPlainObject(outputStream: OutputStream, data: T) {
        val bytes = json.encodeToString(data).encodeToByteArray()
        writeSizePrefixed(outputStream, bytes)
    }

    inline fun <reified T> receivePlainObject(inputStream: InputStream): T {
        val size = readSize(inputStream)
        val buffer = readExactly(inputStream, size)
        return json.decodeFromString<T>(buffer.decodeToString())
    }
}
