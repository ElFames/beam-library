package com.nubax.beam.library.core

/**
 * Sesión cifrada de UNA conexión con UN peer.
 *
 * Antes esto era estado global compartido (un único `sessionKey` en un `object`),
 * lo que rompía en cuanto se hablaba con más de un dispositivo a la vez (el segundo
 * pairing pisaba la clave del primero). Ahora cada [com.nubax.beam.library.connection.PeerSession]
 * tiene su propia instancia, así que un móvil puede tener una sesión con cada uno
 * de sus N Desktops vinculados (PROJECT.md §2.5) sin que se interfieran entre sí.
 */
expect class SecureChannel(sharedSecretRaw: ByteArray) {
    /** Código de verificación humana (numeric comparison) derivado del secreto crudo, antes del KDF. */
    val fingerprint: String

    fun encrypt(data: ByteArray): ByteArray
    fun decrypt(encryptedDataWithIv: ByteArray): ByteArray
}
