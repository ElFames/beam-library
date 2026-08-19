package com.nubax.beam.library.core

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

/**
 * Utilidades criptográficas SIN estado. Cada conexión mantiene su propio
 * [SecureChannel]; esta clase nunca guarda claves de sesión, así que es segura
 * de compartir entre varias conexiones simultáneas (varios peers a la vez).
 */
internal object BeamCrypto {

    private const val EC_CURVE_BITS = 256

    fun generateKeyPair(): KeyPair {
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(EC_CURVE_BITS)
        return keyPairGen.generateKeyPair()
    }

    fun decodePublicKey(bytes: ByteArray): PublicKey {
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(X509EncodedKeySpec(bytes))
    }

    fun decodePrivateKey(bytes: ByteArray): PrivateKey {
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    /** Deriva el secreto compartido ECDH crudo entre mi clave privada y la pública del otro extremo. */
    fun ecdh(myPrivateKey: PrivateKey, otherPublicKeyBytes: ByteArray): ByteArray {
        val otherPublicKey = decodePublicKey(otherPublicKeyBytes)
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(myPrivateKey)
        keyAgreement.doPhase(otherPublicKey, true)
        return keyAgreement.generateSecret()
    }

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    fun verify(publicKey: PublicKey, data: ByteArray, signatureBytes: ByteArray): Boolean {
        return try {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publicKey)
            signature.update(data)
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }

    /** Código numérico corto para comparación visual humana (estilo "numeric comparison" de BLE). */
    fun numericFingerprint(sharedSecret: ByteArray, digits: Int = 6): String {
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

    /** ID corto y estable derivado de la clave pública: identifica al dispositivo sin depender de tokens manuales. */
    fun deviceIdFromPublicKey(publicKeyBytes: ByteArray): String {
        return sha256(publicKeyBytes).joinToString("") { "%02x".format(it) }.take(16)
    }

    fun toBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun fromBase64(text: String): ByteArray = Base64.getDecoder().decode(text)
}
