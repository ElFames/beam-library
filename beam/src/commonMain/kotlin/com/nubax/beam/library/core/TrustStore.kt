package com.nubax.beam.library.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class PairedDevice(
    val id: String,
    val publicKeyBase64: String,
    val name: String
)

/**
 * Dispositivos con los que ya hubo un emparejamiento confirmado por el usuario.
 * El discovery automático solo se conecta a ids presentes aquí; cualquier otro
 * UDIS/pinganillo que se anuncie en la misma red simplemente se ignora.
 */
internal class TrustStore(private val storage: BeamStorage) {

    private val json = Json { ignoreUnknownKeys = true }
    private var cache: MutableMap<String, PairedDevice> = load()

    private fun load(): MutableMap<String, PairedDevice> {
        val raw = storage.readString(KEY) ?: return mutableMapOf()
        return try {
            json.decodeFromString<List<PairedDevice>>(raw).associateBy { it.id }.toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun persist() {
        storage.writeString(KEY, json.encodeToString(cache.values.toList()))
    }

    @Synchronized
    fun isTrusted(id: String): Boolean = cache.containsKey(id)

    @Synchronized
    fun get(id: String): PairedDevice? = cache[id]

    @Synchronized
    fun add(device: PairedDevice) {
        cache[device.id] = device
        persist()
    }

    @Synchronized
    fun remove(id: String) {
        cache.remove(id)
        persist()
    }

    @Synchronized
    fun all(): List<PairedDevice> = cache.values.toList()

    private companion object {
        const val KEY = "beam.trusted_devices"
    }
}
