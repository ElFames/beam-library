package com.nubax.beam.library.sdk

import kotlinx.serialization.Serializable

@Serializable
data class PairingRequest(
    val androidToken: String,
    val targetDesktopToken: String,
    val androidPublicKey: ByteArray // Clave pública ECDH de Android
)

@Serializable
data class PairingResponse(
    val success: Boolean,
    val desktopPublicKey: ByteArray?, // Clave pública ECDH de Desktop (null si falla)
    val message: String
)