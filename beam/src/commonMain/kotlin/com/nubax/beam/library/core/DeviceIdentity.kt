package com.nubax.beam.library.core

import kotlin.io.encoding.Base64

/**
 * Identidad estable de ESTE dispositivo. Se genera una única vez (primer arranque)
 * y se persiste vía [BeamStorage]; a partir de ahí el id y las claves no cambian,
 * lo que permite que otros dispositivos lo recuerden como "de confianza" entre
 * reinicios y reconexiones, en vez de tener que re-emparejar cada vez.
 */
internal class DeviceIdentity(
    val id: String,
    val privateKey: BeamPrivateKey,
    val publicKeyEncoded: ByteArray
) {
    companion object {
        private const val KEY_PRIVATE = "beam.identity.private"
        private const val KEY_PUBLIC = "beam.identity.public"

        fun loadOrCreate(storage: BeamStorage): DeviceIdentity {
            val storedPrivate = storage.readString(KEY_PRIVATE)
            val storedPublic = storage.readString(KEY_PUBLIC)

            if (storedPrivate != null && storedPublic != null) {
                val privateKey = BeamCrypto.decodePrivateKey(Base64.decode(storedPrivate))
                val publicKeyEncoded = Base64.decode(storedPublic)
                val id = BeamCrypto.deviceIdFromPublicKey(publicKeyEncoded)
                return DeviceIdentity(id, privateKey, publicKeyEncoded)
            }

            val keyPair = BeamCrypto.generateKeyPair()
            storage.writeString(KEY_PRIVATE, Base64.encode(BeamCrypto.encodePrivateKey(keyPair.privateKey)))
            storage.writeString(KEY_PUBLIC, Base64.encode(keyPair.publicKeyEncoded))
            val id = BeamCrypto.deviceIdFromPublicKey(keyPair.publicKeyEncoded)
            return DeviceIdentity(id, keyPair.privateKey, keyPair.publicKeyEncoded)
        }
    }
}
