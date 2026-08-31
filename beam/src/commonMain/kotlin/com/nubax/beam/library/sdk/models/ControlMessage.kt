package com.nubax.beam.library.sdk.models

import com.nubax.beam.library.core.LinkState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mensajes de PROTOCOLO de Aircom (no de aplicación): viajan cifrados por el mismo
 * canal que cualquier mensaje normal, pero [MeshBeamConnection] los intercepta y
 * los gestiona internamente en vez de entregárselos a la app vía `observeIncoming`.
 * Se distinguen del payload de aplicación por el campo "type" (el nombre de
 * serialización de cada variante, ver cada @SerialName) — el pinganillo (C++) los
 * construye/parsea a mano por ese mismo campo, así que esos nombres son parte del
 * contrato de red, no solo detalle de implementación Kotlin.
 */
@Serializable
sealed class ControlMessage {

    /** "Ya no eres el dispositivo activo" — dispara auto-desvinculación en quien lo recibe. */
    @Serializable
    @SerialName("link_state_changed")
    data class LinkStateChanged(val state: LinkState) : ControlMessage()

    /** Android manda el código que ha leído en la pantalla del Desktop. */
    @Serializable
    @SerialName("desktop_pair_code_submit")
    data class DesktopPairCodeSubmit(val code: String) : ControlMessage()

    /** Desktop confirma o rechaza el código recibido. */
    @Serializable
    @SerialName("desktop_pair_code_result")
    data class DesktopPairCodeResult(val success: Boolean) : ControlMessage()
}
