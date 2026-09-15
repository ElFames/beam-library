package com.nubax.beam.library.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verificación del actual real de iOS (BeamCryptoKit.swift + CryptoKit), corriendo de
 * verdad en el simulador — no solo "compila", sino que el par de claves, la firma, el
 * ECDH y el AES-GCM producen resultados correctos y consistentes entre sí, incluyendo
 * el wrapping SubjectPublicKeyInfo DER que hace falta para hablar con Kotlin/JVM y con
 * el firmware ESP32 (mbedTLS). Ver PROJECT.md/plan: Fase C, verificación a nivel de librería.
 */
class BeamCryptoIosTest {

    @Test
    fun `genera par de claves con clave publica en formato SubjectPublicKeyInfo DER`() {
        val keyPair = BeamCrypto.generateKeyPair()
        // 26 bytes de prefijo SPKI fijo de P-256 + 65 bytes de punto sin comprimir (0x04||X||Y)
        assertEquals(91, keyPair.publicKeyEncoded.size)
        assertEquals(0x30, keyPair.publicKeyEncoded[0].toInt() and 0xFF)
    }

    @Test
    fun `firma y verifica correctamente y rechaza una firma alterada`() {
        val keyPair = BeamCrypto.generateKeyPair()
        val message = "hola beam".encodeToByteArray()

        val signature = BeamCrypto.sign(keyPair.privateKey, message)
        assertTrue(BeamCrypto.verify(keyPair.publicKeyEncoded, message, signature))

        val tampered = signature.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFalse(BeamCrypto.verify(keyPair.publicKeyEncoded, message, tampered))
    }

    @Test
    fun `ECDH deriva el mismo secreto en ambos lados`() {
        val a = BeamCrypto.generateKeyPair()
        val b = BeamCrypto.generateKeyPair()

        val secretFromA = BeamCrypto.ecdh(a.privateKey, b.publicKeyEncoded)
        val secretFromB = BeamCrypto.ecdh(b.privateKey, a.publicKeyEncoded)

        assertContentEquals(secretFromA, secretFromB)
        assertEquals(32, secretFromA.size)
    }

    @Test
    fun `round-trip de persistencia de clave privada`() {
        val original = BeamCrypto.generateKeyPair()
        val encoded = BeamCrypto.encodePrivateKey(original.privateKey)
        val reloaded = BeamCrypto.decodePrivateKey(encoded)

        // Si es la misma clave, debe firmar de forma verificable con la MISMA clave pública original.
        val message = "round-trip".encodeToByteArray()
        val signature = BeamCrypto.sign(reloaded, message)
        assertTrue(BeamCrypto.verify(original.publicKeyEncoded, message, signature))
    }

    @Test
    fun `AES-256-GCM cifra y descifra y detecta manipulacion del texto cifrado`() {
        val channel = SecureChannel(ByteArray(32) { it.toByte() })
        val plaintext = "mensaje secreto de aircom".encodeToByteArray()

        val encrypted = channel.encrypt(plaintext)
        val decrypted = channel.decrypt(encrypted)
        assertContentEquals(plaintext, decrypted)

        val tampered = encrypted.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        var threw = false
        try {
            channel.decrypt(tampered)
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw, "Descifrar un texto cifrado manipulado debería fallar (tag GCM inválido)")
    }

    @Test
    fun `dos SecureChannel con el mismo secreto compartido producen el mismo fingerprint`() {
        val secret = ByteArray(32) { (it * 7).toByte() }
        val a = SecureChannel(secret)
        val b = SecureChannel(secret)
        assertEquals(a.fingerprint, b.fingerprint)
    }
}
