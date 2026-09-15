package com.nubax.beam.library.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

actual class SecureChannel actual constructor(private val sharedSecretRaw: ByteArray) {

    private companion object {
        const val SHARED_SALT = "8Gf9xY3sP8aR5jP3vH11H9qC0yJ6nN8z"
        const val ALGORITHM = "AES/GCM/NoPadding"
        const val TAG_LENGTH = 128
        const val IV_LENGTH = 12
    }

    private val sessionKey: SecretKeySpec = run {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(sharedSecretRaw)
        digest.update(SHARED_SALT.encodeToByteArray())
        SecretKeySpec(digest.digest(), "AES")
    }

    actual val fingerprint: String = BeamCrypto.numericFingerprint(sharedSecretRaw)

    actual fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey, GCMParameterSpec(TAG_LENGTH, iv))
        return iv + cipher.doFinal(data)
    }

    actual fun decrypt(encryptedDataWithIv: ByteArray): ByteArray {
        if (encryptedDataWithIv.size < IV_LENGTH) throw Exception("Datos corruptos")
        val iv = encryptedDataWithIv.sliceArray(0 until IV_LENGTH)
        val ciphertext = encryptedDataWithIv.sliceArray(IV_LENGTH until encryptedDataWithIv.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, sessionKey, GCMParameterSpec(TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }
}
