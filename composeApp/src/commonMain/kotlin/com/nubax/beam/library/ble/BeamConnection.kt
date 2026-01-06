package com.nubax.beam.library.ble

import com.nubax.beam.library.core.BeamState
import com.nubax.beam.library.core.BleResult
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable

interface BeamConnection {
    val incomingData: SharedFlow<ByteArray>
    suspend fun startPairing(ownToken: String, targetToken: String? = null): BleResult<BeamState>
    suspend fun sendRawData(data: ByteArray): BleResult<Unit>
    fun close()
}

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