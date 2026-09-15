@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nubax.beam.library.core

import com.nubax.beam.library.native.cryptokit.BeamCryptoKit

actual class SecureChannel actual constructor(sharedSecretRaw: ByteArray) {

    private companion object {
        const val SHARED_SALT = "8Gf9xY3sP8aR5jP3vH11H9qC0yJ6nN8z"
    }

    /** Mismo KDF que la actual de JVM (SecureChannel.kt en jvmCommon): sha256(secreto || salt). */
    private val sessionKey: ByteArray = BeamCrypto.sha256(sharedSecretRaw + SHARED_SALT.encodeToByteArray())

    actual val fingerprint: String = BeamCrypto.numericFingerprint(sharedSecretRaw)

    actual fun encrypt(data: ByteArray): ByteArray {
        val result = BeamCryptoKit.aesGcmEncryptWithKey(key = sessionKey.toNSData(), plaintext = data.toNSData(), error = null)
            ?: error("Fallo cifrando con AES-GCM")
        return result.toByteArray()
    }

    actual fun decrypt(encryptedDataWithIv: ByteArray): ByteArray {
        val result = BeamCryptoKit.aesGcmDecryptWithKey(
            key = sessionKey.toNSData(),
            ivCiphertextTag = encryptedDataWithIv.toNSData(),
            error = null
        ) ?: error("Fallo descifrando con AES-GCM")
        return result.toByteArray()
    }
}
