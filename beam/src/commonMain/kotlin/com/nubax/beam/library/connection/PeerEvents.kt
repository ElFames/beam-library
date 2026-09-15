package com.nubax.beam.library.connection

import com.nubax.beam.library.core.PeerKind

/** Mensaje ya descifrado, con el id del peer que lo mandó (imprescindible en cuanto hay más de dos nodos). */
data class IncomingMessage(val peerId: String, val bytes: ByteArray)

/**
 * Notifica a la app de cambios en el estado de vinculación, para que pueda
 * reflejarlo en la UI (pantalla *Devices*, historial...). Sustituye al antiguo
 * `PairingRequestEvent`/fingerprint-a-confirmar: ahora la confirmación la da el
 * código de Desktop, no un código a comparar en ambos lados.
 */
sealed class LinkEvent {
    data class Linked(val deviceId: String, val kind: PeerKind, val name: String) : LinkEvent()
    data class Unlinked(val deviceId: String) : LinkEvent()
}
