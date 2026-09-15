@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nubax.beam.library.core

import com.nubax.beam.library.native.cryptokit.BeamCryptoKit
import com.nubax.beam.library.native.cryptokit.BeamPrivateKeyHandle

actual class BeamPrivateKey(internal val handle: BeamPrivateKeyHandle)

/**
 * Prefijo DER fijo de un SubjectPublicKeyInfo de EC P-256 (secp256r1), sin comprimir:
 * `SEQUENCE { SEQUENCE { OID id-ecPublicKey, OID prime256v1 }, BIT STRING <65 bytes> }`.
 * CryptoKit (vía BeamCryptoKit.swift) solo habla en el punto crudo sin comprimir
 * (x963Representation, 0x04||X||Y, 65 bytes) — este prefijo es lo que hace falta anteponer
 * para hablar el mismo SubjectPublicKeyInfo DER que ya usan Kotlin/JVM
 * (java.security.KeyFactory/X509EncodedKeySpec) y el firmware ESP32 (mbedTLS). Es un
 * prefijo fijo (siempre la misma curva) — nada que calcular, solo concatenar/quitar.
 */
private val P256_SPKI_PREFIX = byteArrayOf(
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A.toByte(), 0x86.toByte(), 0x48, 0xCE.toByte(),
    0x3D, 0x02, 0x01, 0x06, 0x08, 0x2A.toByte(), 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D,
    0x03, 0x01, 0x07, 0x03, 0x42, 0x00
)

private fun rawPointToSpki(raw: ByteArray): ByteArray = P256_SPKI_PREFIX + raw

private fun spkiToRawPoint(spki: ByteArray): ByteArray {
    require(spki.size == P256_SPKI_PREFIX.size + 65) { "SubjectPublicKeyInfo de EC P-256 con tamaño inesperado: ${spki.size}" }
    return spki.copyOfRange(P256_SPKI_PREFIX.size, spki.size)
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    val v = byte.toInt() and 0xFF
    val hi = "0123456789abcdef"[v shr 4]
    val lo = "0123456789abcdef"[v and 0x0F]
    "$hi$lo"
}

actual object BeamCrypto {

    actual fun generateKeyPair(): BeamKeyPair {
        val result = BeamCryptoKit.generateKeyPair()
        return BeamKeyPair(
            privateKey = BeamPrivateKey(result.privateKey()),
            publicKeyEncoded = rawPointToSpki(result.publicKeyRaw().toByteArray())
        )
    }

    actual fun encodePrivateKey(privateKey: BeamPrivateKey): ByteArray =
        BeamCryptoKit.encodePrivateKey(privateKey.handle).toByteArray()

    actual fun decodePrivateKey(bytes: ByteArray): BeamPrivateKey {
        val handle = BeamCryptoKit.decodePrivateKey(bytes.toNSData(), error = null)
            ?: error("No se pudo decodificar la clave privada")
        return BeamPrivateKey(handle)
    }

    actual fun ecdh(myPrivateKey: BeamPrivateKey, otherPublicKeyEncoded: ByteArray): ByteArray {
        val otherRaw = spkiToRawPoint(otherPublicKeyEncoded).toNSData()
        val shared = BeamCryptoKit.ecdh(myPrivateKey.handle, otherPublicKeyRaw = otherRaw, error = null)
            ?: error("Fallo derivando ECDH")
        return shared.toByteArray()
    }

    actual fun sha256(data: ByteArray): ByteArray =
        BeamCryptoKit.sha256(data.toNSData()).toByteArray()

    actual fun sign(privateKey: BeamPrivateKey, data: ByteArray): ByteArray {
        val signature = BeamCryptoKit.sign(privateKey.handle, data = data.toNSData(), error = null)
            ?: error("Fallo al firmar")
        return signature.toByteArray()
    }

    actual fun verify(publicKeyEncoded: ByteArray, data: ByteArray, signatureBytes: ByteArray): Boolean {
        val raw = try {
            spkiToRawPoint(publicKeyEncoded)
        } catch (e: Exception) {
            return false
        }
        return BeamCryptoKit.verifyWithPublicKeyRaw(
            publicKeyRaw = raw.toNSData(),
            data = data.toNSData(),
            signature = signatureBytes.toNSData()
        )
    }

    actual fun numericFingerprint(sharedSecret: ByteArray, digits: Int): String {
        val hash = sha256(sharedSecret)
        val value = ((hash[0].toInt() and 0xFF) shl 16) or
            ((hash[1].toInt() and 0xFF) shl 8) or
            (hash[2].toInt() and 0xFF)
        val bound = intPow10(digits)
        return (value % bound).toString().padStart(digits, '0')
    }

    private fun intPow10(n: Int): Int {
        var result = 1
        repeat(n) { result *= 10 }
        return result
    }

    actual fun deviceIdFromPublicKey(publicKeyBytes: ByteArray): String {
        return sha256(publicKeyBytes).toHex().take(16)
    }
}
