package com.nubax.beam.library.sdk.models

import kotlinx.serialization.Serializable

/**
 * Primer mensaje del handshake TCP: cada lado se presenta con su identidad estable.
 * No lleva secretos, solo la clave pública (que es pública por definición).
 */
@Serializable
internal data class HandshakeHello(
    val id: String,
    val name: String,
    val publicKeyBase64: String
)

/**
 * Clave efímera ECDH de esta sesión, firmada con la clave de identidad de quien la envía.
 * La firma evita que alguien intercepte la conexión y sustituya la clave efímera por la suya:
 * solo el dueño de la identidad anunciada en el [HandshakeHello] pudo haber firmado esto.
 */
@Serializable
internal data class EphemeralOffer(
    val publicKeyBase64: String,
    val signatureBase64: String
)
