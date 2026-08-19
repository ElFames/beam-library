package com.nubax.beam.library.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sesión cifrada de UNA conexión con UN peer.
 *
 * Antes esto era estado global compartido (un único `sessionKey` en un `object`),
 * lo que rompía en cuanto se hablaba con más de un dispositivo a la vez (el segundo
 * pairing pisaba la clave del primero). Ahora cada [com.nubax.beam.library.connection.PeerSession]
 * tiene su propia instancia, así que Desktop puede tener una sesión con el móvil y
 * otra con el pinganillo sin que se interfieran.
 */
internal class SecureChannel(private val sharedSecretRaw: ByteArray) {

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

    /** Código de verificación humana (numeric comparison) derivado del secreto crudo, antes del KDF. */
    val fingerprint: String = BeamCrypto.numericFingerprint(sharedSecretRaw)

    fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey, GCMParameterSpec(TAG_LENGTH, iv))
        return iv + cipher.doFinal(data)
    }

    fun decrypt(encryptedDataWithIv: ByteArray): ByteArray {
        if (encryptedDataWithIv.size < IV_LENGTH) throw Exception("Datos corruptos")
        val iv = encryptedDataWithIv.sliceArray(0 until IV_LENGTH)
        val ciphertext = encryptedDataWithIv.sliceArray(IV_LENGTH until encryptedDataWithIv.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, sessionKey, GCMParameterSpec(TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }
}
