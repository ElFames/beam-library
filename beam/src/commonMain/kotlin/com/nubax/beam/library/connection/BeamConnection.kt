package com.nubax.beam.library.connection

import com.nubax.beam.library.sdk.models.BeamResult
import com.nubax.beam.library.sdk.models.BeamState
import kotlinx.coroutines.flow.SharedFlow

interface BeamConnection {
    val incomingData: SharedFlow<ByteArray>
    suspend fun startPairing(ownToken: String, targetToken: String? = null): BeamResult<BeamState>
    suspend fun sendRawData(data: ByteArray): BeamResult<Unit>
    fun close()
    fun stopDiscovery()
    fun startDiscovery()
}