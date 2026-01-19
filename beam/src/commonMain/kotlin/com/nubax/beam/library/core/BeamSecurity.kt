package com.nubax.beam.library.core

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import javax.crypto.KeyAgreement

internal object BeamSecurity {

    private const val SHARED_SALT = "8Gf9xY3sP8aR5jP3vH11H9qC0yJ6nN8z"
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH = 128
    private const val IV_LENGTH = 12

    private var sessionKey: SecretKeySpec? = null
    const val APP_SECRET = "3Kf9xL2sP8aR4bT9vG1wH7qZ0yJ6nM8d"

    /**
     * Generar par de claves locales (Pública/Privada)
     */
    fun generateKeyPair(): KeyPair {
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(256)
        return keyPairGen.generateKeyPair()
    }

    /**
     * Calcular el secreto compartido (ECDH)
     * Se mezcla la clave privada propia con la pública recibida del otro extremo.
     */
    fun computeSharedSecret(myPrivateKey: PrivateKey, otherPublicKeyBytes: ByteArray) {
        val keyFactory = KeyFactory.getInstance("EC")
        val otherPublicKey = keyFactory.generatePublic(
            java.security.spec.X509EncodedKeySpec(otherPublicKeyBytes)
        )

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(myPrivateKey)
        keyAgreement.doPhase(otherPublicKey, true)

        val sharedSecret = keyAgreement.generateSecret()

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sharedSecret)
        digest.update(SHARED_SALT.encodeToByteArray())
        val aesKeyBytes = digest.digest()
        this.sessionKey = SecretKeySpec(aesKeyBytes, "AES")
    }

    /**
     * Encriptar datos usando AES-GCM
     */
    fun encrypt(data: ByteArray): ByteArray {
        val key = sessionKey ?: throw Exception("Sesión no segura: Clave de sesión no generada")

        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(iv) // IV aleatorio para cada mensaje

        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(data)

        // El IV es necesario para desencriptar, lo pegamos al principio del paquete
        return iv + ciphertext
    }

    /**
     * Desencriptar datos usando AES-GCM
     */
    fun decrypt(encryptedDataWithIv: ByteArray): ByteArray {
        val key = sessionKey ?: throw Exception("Sesión no segura: Clave de sesión no generada")

        if (encryptedDataWithIv.size < IV_LENGTH) throw Exception("Datos corruptos")

        // Extraemos el IV (primeros 12 bytes) y el mensaje (el resto)
        val iv = encryptedDataWithIv.sliceArray(0 until IV_LENGTH)
        val ciphertext = encryptedDataWithIv.sliceArray(IV_LENGTH until encryptedDataWithIv.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))

        return cipher.doFinal(ciphertext)
    }

    /**
     * Limpiar la sesión al desconectar
     */
    fun clearSession() {
        sessionKey = null
    }
}