package com.nubax.beam.library.connection

import kotlinx.serialization.Serializable

/** Anuncio UDP periódico: "aquí estoy, soy este id". Sin esto no hay discovery. */
@Serializable
internal data class Beacon(
    val id: String,
    val name: String,
    val tcpPort: Int
)

data class DiscoveredDevice(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val trusted: Boolean
)
