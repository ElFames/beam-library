package com.nubax.beam.library.core

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

actual class BeamPrivateKey(internal val jcaKey: PrivateKey)

private const val EC_CURVE_BITS = 256

private fun decodePublicKey(bytes: ByteArray): PublicKey {
    val keyFactory = KeyFactory.getInstance("EC")
    return keyFactory.generatePublic(X509EncodedKeySpec(bytes))
}

actual object BeamCrypto {

    actual fun generateKeyPair(): BeamKeyPair {
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(EC_CURVE_BITS)
        val keyPair = keyPairGen.generateKeyPair()
        return BeamKeyPair(BeamPrivateKey(keyPair.private), keyPair.public.encoded)
    }

    actual fun encodePrivateKey(privateKey: BeamPrivateKey): ByteArray = privateKey.jcaKey.encoded

    actual fun decodePrivateKey(bytes: ByteArray): BeamPrivateKey {
        val keyFactory = KeyFactory.getInstance("EC")
        return BeamPrivateKey(keyFactory.generatePrivate(PKCS8EncodedKeySpec(bytes)))
    }

    actual fun ecdh(myPrivateKey: BeamPrivateKey, otherPublicKeyEncoded: ByteArray): ByteArray {
        val otherPublicKey = decodePublicKey(otherPublicKeyEncoded)
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(myPrivateKey.jcaKey)
        keyAgreement.doPhase(otherPublicKey, true)
        return keyAgreement.generateSecret()
    }

    actual fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    actual fun sign(privateKey: BeamPrivateKey, data: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey.jcaKey)
        signature.update(data)
        return signature.sign()
    }

    actual fun verify(publicKeyEncoded: ByteArray, data: ByteArray, signatureBytes: ByteArray): Boolean {
        return try {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(decodePublicKey(publicKeyEncoded))
            signature.update(data)
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
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
        return sha256(publicKeyBytes).joinToString("") { "%02x".format(it) }.take(16)
    }
}
