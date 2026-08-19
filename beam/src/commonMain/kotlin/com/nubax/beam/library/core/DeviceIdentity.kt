package com.nubax.beam.library.core

import java.security.KeyPair

/**
 * Identidad estable de ESTE dispositivo. Se genera una única vez (primer arranque)
 * y se persiste vía [BeamStorage]; a partir de ahí el id y las claves no cambian,
 * lo que permite que otros dispositivos lo recuerden como "de confianza" entre
 * reinicios y reconexiones, en vez de tener que re-emparejar cada vez.
 */
internal data class DeviceIdentity(
    val id: String,
    val keyPair: KeyPair
) {
    val publicKeyEncoded: ByteArray get() = keyPair.public.encoded

    companion object {
        private const val KEY_PRIVATE = "beam.identity.private"
        private const val KEY_PUBLIC = "beam.identity.public"

        fun loadOrCreate(storage: BeamStorage): DeviceIdentity {
            val storedPrivate = storage.readString(KEY_PRIVATE)
            val storedPublic = storage.readString(KEY_PUBLIC)

            if (storedPrivate != null && storedPublic != null) {
                val privateKey = BeamCrypto.decodePrivateKey(BeamCrypto.fromBase64(storedPrivate))
                val publicKey = BeamCrypto.decodePublicKey(BeamCrypto.fromBase64(storedPublic))
                val id = BeamCrypto.deviceIdFromPublicKey(publicKey.encoded)
                return DeviceIdentity(id, KeyPair(publicKey, privateKey))
            }

            val keyPair = BeamCrypto.generateKeyPair()
            storage.writeString(KEY_PRIVATE, BeamCrypto.toBase64(keyPair.private.encoded))
            storage.writeString(KEY_PUBLIC, BeamCrypto.toBase64(keyPair.public.encoded))
            val id = BeamCrypto.deviceIdFromPublicKey(keyPair.public.encoded)
            return DeviceIdentity(id, keyPair)
        }
    }
}
