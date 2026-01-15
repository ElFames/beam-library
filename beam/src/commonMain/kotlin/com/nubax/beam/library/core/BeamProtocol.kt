package com.nubax.beam.library.core

import kotlinx.serialization.json.Json

internal object BeamProtocol {
    val json = Json { ignoreUnknownKeys = true }

    fun sendRaw(outputStream: java.io.OutputStream, bytes: ByteArray) {
        val encrypted = BeamSecurity.encrypt(bytes)

        val size = encrypted.size
        outputStream.write(size shr 24)
        outputStream.write(size shr 16)
        outputStream.write(size shr 8)
        outputStream.write(size)
        outputStream.write(encrypted)
        outputStream.flush()
    }

    fun receiveRaw(inputStream: java.io.InputStream): ByteArray {
        val b1 = inputStream.read().takeIf { it != -1 } ?: throw Exception("Stream closed")
        val b2 = inputStream.read().takeIf { it != -1 } ?: throw Exception("Stream closed")
        val b3 = inputStream.read().takeIf { it != -1 } ?: throw Exception("Stream closed")
        val b4 = inputStream.read().takeIf { it != -1 } ?: throw Exception("Stream closed")

        val size = (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
        val buffer = ByteArray(size)

        var bytesRead = 0
        while (bytesRead < size) {
            val result = inputStream.read(buffer, bytesRead, size - bytesRead)
            if (result == -1) throw Exception("Connection interrupted")
            bytesRead += result
        }

        return BeamSecurity.decrypt(buffer)
    }

    inline fun <reified T> sendObject(outputStream: java.io.OutputStream, data: T) {
        val jsonString = json.encodeToString(data)
        val bytes = jsonString.encodeToByteArray()

        val size = bytes.size
        outputStream.write(size shr 24)
        outputStream.write(size shr 16)
        outputStream.write(size shr 8)
        outputStream.write(size)
        outputStream.write(bytes)
        outputStream.flush()
    }

    inline fun <reified T> receiveObject(inputStream: java.io.InputStream): T {
        val b1 = inputStream.read().takeIf { it != -1 } ?: throw Exception("Stream closed")
        val b2 = inputStream.read().takeIf { it != -1 } ?: throw Exception("Stream closed")
        val b3 = inputStream.read().takeIf { it != -1 } ?: throw Exception("Stream closed")
        val b4 = inputStream.read().takeIf { it != -1 } ?: throw Exception("Stream closed")

        val size = (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
        val buffer = ByteArray(size)
        inputStream.read(buffer)

        return json.decodeFromString<T>(buffer.decodeToString())
    }
}