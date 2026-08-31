package com.nubax.beam.library.connection

import com.nubax.beam.library.core.PeerKind
import kotlinx.serialization.Serializable

/**
 * Anuncio UDP periódico: "aquí estoy, soy este id, soy de este tipo". Sin esto no
 * hay discovery. [kind] hace falta para saber, sin necesidad de handshake, si el
 * anunciante es un pinganillo (que nunca conecta hacia fuera — hay que ir siempre
 * a buscarlo) o un desktop (simétrico, aplica el desempate por id de siempre).
 */
@Serializable
internal data class Beacon(
    val id: String,
    val name: String,
    val kind: PeerKind,
    val tcpPort: Int
)

data class DiscoveredDevice(
    val id: String,
    val name: String,
    val kind: PeerKind,
    val address: String,
    val port: Int,
    val trusted: Boolean
)
