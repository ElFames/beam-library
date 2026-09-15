package com.nubax.beam.library.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Estado de una vinculación, compartido por los dos lados (móvil, Desktop) —
 * ver PROJECT.md §2.1.
 */
enum class LinkState { FABRICA, DESCUBRIENDO, VINCULANDO, VINCULADO, DESVINCULADO }

/** De qué tipo es el otro extremo — determina qué flujo de emparejamiento aplica. */
enum class PeerKind { DESKTOP, MOBILE }

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
 * propio estado. Invariante de `active` (ver PROJECT.md §2.1 y §2.5):
 * - Un móvil puede tener a la vez **N Desktops activos** (sin límite) — la
 *   cardinalidad "1 Desktop por móvil" de la primera versión queda levantada.
 * - Un Desktop sigue teniendo como mucho **1 móvil activo**: cada Desktop solo
 *   puede estar vinculado con un móvil a la vez (esto no cambia).
 * - Ya no existe `PeerKind.PINGANILLO`: el puente de red (antes "pinganillo") no
 *   es un peer de Aircom, no aparece en ningún historial.
 */
internal class DeviceHistoryStore(private val storage: BeamStorage) {

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = BeamLock()
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

    fun get(deviceId: String): LinkedDevice? = lock.withLock { cache[deviceId] }

    fun isActive(deviceId: String): Boolean = lock.withLock { cache[deviceId]?.active == true }

    /** Único activo de este [kind] — solo tiene sentido para [PeerKind.MOBILE] (invariante 1:1 desde un Desktop). */
    fun activeDevice(kind: PeerKind): LinkedDevice? = lock.withLock { cache.values.firstOrNull { it.kind == kind && it.active } }

    /** Todos los activos de este [kind] — para [PeerKind.DESKTOP] puede haber más de uno (cardinalidad abierta). */
    fun activeDevices(kind: PeerKind): List<LinkedDevice> = lock.withLock { cache.values.filter { it.kind == kind && it.active } }

    fun all(): List<LinkedDevice> = lock.withLock { cache.values.toList() }

    /**
     * Marca [deviceId] como vinculado y activo. Se llama solo desde un flujo de
     * emparejamiento EXPLÍCITO (código de Desktop confirmado) — nunca automáticamente
     * por el discovery pasivo.
     *
     * Para [PeerKind.MOBILE] desactiva cualquier otro móvil activo (invariante "1 móvil
     * por Desktop", sin cambios). Para [PeerKind.DESKTOP] NO desactiva otros Desktops
     * activos — un móvil puede acumular tantos Desktops vinculados como quiera.
     */
    fun link(deviceId: String, kind: PeerKind, name: String, publicKeyBase64: String) = lock.withLock {
        if (kind != PeerKind.DESKTOP) {
            cache.values.filter { it.kind == kind && it.active }.forEach {
                cache[it.deviceId] = it.copy(active = false, state = LinkState.DESVINCULADO)
            }
        }
        cache[deviceId] = LinkedDevice(deviceId, kind, name, publicKeyBase64, LinkState.VINCULADO, active = true)
        persist()
    }

    fun unlink(deviceId: String) = lock.withLock {
        cache[deviceId]?.let { cache[deviceId] = it.copy(active = false, state = LinkState.DESVINCULADO) }
        persist()
    }

    private companion object {
        const val KEY = "aircom.device_history"
    }
}
