package com.nubax.beam.library.sdk

import com.nubax.beam.library.connection.BeamConnection
import com.nubax.beam.library.core.BeamSecurity
import com.nubax.beam.library.core.Locator
import com.nubax.beam.library.core.Log
import com.nubax.beam.library.sdk.models.BeamResult
import com.nubax.beam.library.sdk.models.BeamState
import com.nubax.beam.library.sdk.models.onFailure
import com.nubax.beam.library.sdk.models.onSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

class BeamApplication(
    private val beamConnection: BeamConnection = Locator.beamConnection!!
) {
    private lateinit var ownToken: String

    private val _state = MutableStateFlow<BeamState>(BeamState.Disabled)
    val state = _state.asStateFlow()

    fun init(token: String) {
        ownToken = token
        beamConnection.startDiscovery()
        _state.value = BeamState.Activated
    }

    suspend fun startPairing(targetToken: String? = null) {
        _state.value = BeamState.Connecting

        beamConnection.startPairing(ownToken, targetToken)
            .onSuccess { _state.value = it }
            .onFailure { _state.value = BeamState.Error(it) }
    }

    fun disconnect() {
        beamConnection.close()
        BeamSecurity.clearSession()
        _state.value = BeamState.Disabled
    }

    suspend fun <T> send(data: T, serializer: KSerializer<T>): BeamResult<Unit> {
        return try {
            val jsonString = Json.encodeToString(serializer, data)
            beamConnection.sendRawData(jsonString.encodeToByteArray())
        } catch (e: Exception) {
            BeamResult.Failure(e.message ?: "Error de envío")
        }
    }

    fun <T> observeIncoming(serializer: KSerializer<T>): Flow<T> {
        return beamConnection.incomingData
            .mapNotNull {
                runCatching {
                    Json.decodeFromString(serializer, it.decodeToString())
                }.getOrNull()
            }
    }
}