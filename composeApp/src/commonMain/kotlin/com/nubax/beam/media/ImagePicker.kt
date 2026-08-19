package com.nubax.beam.media

data class PickedFile(val name: String, val mimeType: String, val bytes: ByteArray)

/** Cada plataforma sabe abrir su propio selector nativo (Fotos en Android, FileDialog en Desktop). */
interface ImagePicker {
    suspend fun pick(): PickedFile?
}

/** Dónde se guarda lo que llega por Aircom, para poder comprobar que de verdad se recibió. */
interface ReceivedFileSaver {
    fun save(name: String, bytes: ByteArray): String
}

/** Unirse por código a una red WiFi (el hotspot de reencuentro del móvil). Solo tiene sentido en Desktop. */
interface WifiJoiner {
    suspend fun join(ssid: String, password: String): Result<Unit>
}
