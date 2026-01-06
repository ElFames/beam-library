package com.nubax.beam.library.sdk

import com.nubax.beam.library.ble.BeamConnection
import com.nubax.beam.library.core.BeamSecurity
import com.nubax.beam.library.core.BeamState
import com.nubax.beam.library.core.BleResult
import com.nubax.beam.library.core.Log
import com.nubax.beam.library.core.onFailure
import com.nubax.beam.library.core.onSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class BeamApplication(
    val beamConnection: BeamConnection
) {
    private lateinit var ownToken: String

    private val _state = MutableStateFlow<BeamState>(BeamState.Offline)
    val state = _state.asStateFlow()

    init {
        // comprobar si el wifi esta activado en el dispositivo

    }

    /**
     * Inicializa el estado de la conexión (Online si el hardware está listo).
     */
    fun init(ownToken: String) {
        this.ownToken = ownToken
        Log.i("Own token establecido: $ownToken")
        // TODO: Check hardware is ready
        Log.i("Hardware is ready.")
        _state.value = BeamState.Online
    }

    /**
     * Inicia el proceso de emparejamiento.
     * @param targetToken El token del dispositivo al que nos queremos conectar.
     */
    suspend fun startPairing(targetToken: String? = null) {
        _state.value = BeamState.Connecting
        // Enviamos nuestro token y el del objetivo
        beamConnection.startPairing(ownToken, targetToken)
            .onSuccess { _state.value = it }
            .onFailure { _state.value = BeamState.Error(it) }
    }

    /**
     * Envía un objeto serializable.
     * Se encarga de serializar a JSON y enviarlo a través de la capa segura.
     */
    suspend inline fun <reified T> send(data: T): BleResult<Unit> {
        return try {
            val jsonString = Json.encodeToString(data)
            val bytes = jsonString.encodeToByteArray()

            // Enviamos los bytes. La implementación de sendRawData
            // dentro de la conexión usará BeamProtocol.sendRaw (que encripta).
            beamConnection.sendRawData(bytes)
        } catch (e: Exception) {
            BleResult.Failure("Error de envío o encriptación: ${e.message}")
        }
    }

    /**
     * Observa los datos entrantes.
     * Los bytes recibidos ya vienen desencriptados por la capa inferior (Connection/Protocol).
     */
    inline fun <reified T> observeIncoming(): Flow<T> {
        return beamConnection.incomingData
            .map { bytes ->
                runCatching {
                    // Los bytes que llegan aquí ya han pasado por BeamSecurity.decrypt
                    // gracias al loop de escucha en la clase Connection.
                    val jsonString = bytes.decodeToString()
                    Json.decodeFromString<T>(jsonString)
                }.getOrNull()
            }
            .filterNotNull()
    }

    /**
     * Cierra la conexión, limpia las claves de sesión y resetea el estado.
     */
    fun disconnect() {
        beamConnection.close()
        // Es vital limpiar las claves de sesión al desconectar para seguridad
        BeamSecurity.clearSession()
        _state.value = BeamState.Offline
    }
}