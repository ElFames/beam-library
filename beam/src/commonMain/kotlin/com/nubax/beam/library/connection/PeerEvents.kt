package com.nubax.beam.library.connection

/** Mensaje ya descifrado, con el id del peer que lo mandó (imprescindible en cuanto hay más de dos nodos). */
data class IncomingMessage(val peerId: String, val bytes: ByteArray)

/**
 * Un dispositivo desconocido completó el ECDH pero todavía no está en el trust store.
 * [fingerprint] es el código de verificación que la app debe mostrar en ambas pantallas:
 * si el usuario confirma que coinciden en los dos dispositivos, se llama a confirmPairing.
 */
data class PairingRequestEvent(val peerId: String, val name: String, val fingerprint: String)
