package com.nubax.beam.library.connection

import com.nubax.beam.library.core.PeerKind
import kotlinx.serialization.Serializable

/**
 * Anuncio UDP periódico: "aquí estoy, soy este id, soy de este tipo". Sin esto no
 * hay discovery. [kind] hace falta para saber qué flujo de emparejamiento aplica
 * (código de Desktop) y para la invariante de cardinalidad del historial (1 móvil
 * por Desktop, N Desktops por móvil — ver PROJECT.md §2.5).
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
