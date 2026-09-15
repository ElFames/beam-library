package com.nubax.beam.library.core

/**
 * Handle opaco a la clave privada EC de este dispositivo (o de un par efímero).
 * Su representación real (java.security.PrivateKey en JVM/Android, SecKeyRef en
 * iOS...) vive solo en el `actual` de cada plataforma — el resto del módulo nunca
 * necesita saber qué hay dentro, solo pasarla de vuelta a [BeamCrypto].
 */
expect class BeamPrivateKey

/** Par de claves recién generado: el handle opaco de la privada + la pública ya codificada (DER/SPKI). */
class BeamKeyPair(val privateKey: BeamPrivateKey, val publicKeyEncoded: ByteArray)

/**
 * Utilidades criptográficas SIN estado. Cada conexión mantiene su propio
 * [SecureChannel]; esta clase nunca guarda claves de sesión, así que es segura
 * de compartir entre varias conexiones simultáneas (varios peers a la vez).
 */
expect object BeamCrypto {
    fun generateKeyPair(): BeamKeyPair

    /** Para persistir la clave privada (p. ej. en [BeamStorage]) y recuperarla luego. */
    fun encodePrivateKey(privateKey: BeamPrivateKey): ByteArray
    fun decodePrivateKey(bytes: ByteArray): BeamPrivateKey

    /** Deriva el secreto compartido ECDH crudo entre mi clave privada y la pública (encoded) del otro extremo. */
    fun ecdh(myPrivateKey: BeamPrivateKey, otherPublicKeyEncoded: ByteArray): ByteArray

    fun sha256(data: ByteArray): ByteArray

    fun sign(privateKey: BeamPrivateKey, data: ByteArray): ByteArray

    /** [publicKeyEncoded] en formato DER/SPKI, igual que el que produce [BeamKeyPair.publicKeyEncoded]. */
    fun verify(publicKeyEncoded: ByteArray, data: ByteArray, signatureBytes: ByteArray): Boolean

    /** Código numérico corto para comparación visual humana (estilo "numeric comparison" de BLE). */
    fun numericFingerprint(sharedSecret: ByteArray, digits: Int = 6): String

    /** ID corto y estable derivado de la clave pública: identifica al dispositivo sin depender de tokens manuales. */
    fun deviceIdFromPublicKey(publicKeyBytes: ByteArray): String
}
