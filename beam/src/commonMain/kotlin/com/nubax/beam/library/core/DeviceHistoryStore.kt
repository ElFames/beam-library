package com.nubax.beam.library.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Estado de una vinculación, compartido por los tres lados (pinganillo, Android,
 * Desktop) — ver PROJECT.md §2.1.
 */
enum class LinkState { FABRICA, DESCUBRIENDO, VINCULANDO, VINCULADO, DESVINCULADO }

/** De qué tipo es el otro extremo — determina qué flujo de emparejamiento aplica. */
enum class PeerKind { PINGANILLO, DESKTOP, ANDROID }

@Serializable
data class LinkedDevice(
    val deviceId: String,
    val kind: PeerKind,
    val name: String,
    val publicKeyBase64: String,
    val state: LinkState,
    val active: Boolean
)

/**
 * Sustituye a TrustStore: en vez de solo "confío en este id", guarda un HISTÓRICO
 * de todos los peers con los que se ha intentado/logrado vincular, cada uno con su
 * propio estado. Invariante de `active` (ver PROJECT.md §2.1):
 * - como mucho un `active=true` POR CADA [PeerKind] (así Android puede tener a la
 *   vez un pinganillo activo y un desktop activo, pero pinganillo/desktop, que solo
 *   almacenan un kind, terminan con "como mucho uno activo en total" gratis).
 */
internal class DeviceHistoryStore(private val storage: BeamStorage) {

    private val json = Json { ignoreUnknownKeys = true }
    private var cache: MutableMap<String, LinkedDevice> = load()

    private fun load(): MutableMap<String, LinkedDevice> {
        val raw = storage.readString(KEY) ?: return mutableMapOf()
        return try {
            json.decodeFromString<List<LinkedDevice>>(raw).associateBy { it.deviceId }.toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun persist() {
        storage.writeString(KEY, json.encodeToString(cache.values.toList()))
    }

    @Synchronized
    fun get(deviceId: String): LinkedDevice? = cache[deviceId]

    @Synchronized
    fun isActive(deviceId: String): Boolean = cache[deviceId]?.active == true

    @Synchronized
    fun activeDevice(kind: PeerKind): LinkedDevice? = cache.values.firstOrNull { it.kind == kind && it.active }

    @Synchronized
    fun all(): List<LinkedDevice> = cache.values.toList()

    /**
     * Marca [deviceId] como vinculado y activo, desactivando cualquier otro activo
     * del MISMO [kind] (la invariante de arriba). Se llama solo desde un flujo de
     * emparejamiento EXPLÍCITO (credenciales de pinganillo verificadas al unirse a
     * su WiFi, o código de Desktop confirmado) — nunca automáticamente por el
     * discovery pasivo.
     */
    @Synchronized
    fun link(deviceId: String, kind: PeerKind, name: String, publicKeyBase64: String) {
        cache.values.filter { it.kind == kind && it.active }.forEach {
            cache[it.deviceId] = it.copy(active = false, state = LinkState.DESVINCULADO)
        }
        cache[deviceId] = LinkedDevice(deviceId, kind, name, publicKeyBase64, LinkState.VINCULADO, active = true)
        persist()
    }

    @Synchronized
    fun unlink(deviceId: String) {
        cache[deviceId]?.let { cache[deviceId] = it.copy(active = false, state = LinkState.DESVINCULADO) }
        persist()
    }

    private companion object {
        const val KEY = "aircom.device_history"
    }
}
