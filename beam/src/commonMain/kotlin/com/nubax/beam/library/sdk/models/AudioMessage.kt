package com.nubax.beam.library.sdk.models

import kotlinx.serialization.Serializable

/**
 * Un trozo de audio PCM crudo capturado por un dispositivo de captación (p. ej. el
 * pinganillo). El audio de un stream llega troceado en varios mensajes: [seq] permite
 * reordenar/detectar huecos si algún trozo llega tarde o fuera de orden, e [isFinal]
 * marca el último trozo de [streamId] para que quien escucha sepa cuándo cerrar el
 * buffer y pasar el audio acumulado a texto en vez de seguir esperando más datos.
 *
 * Es una clase normal (no `data class`) con equals/hashCode/toString manuales, igual que
 * [MediaMessage]: un `data class` con un ByteArray dentro compara por referencia, no por
 * contenido.
 */
@Serializable
class AudioMessage(
    val streamId: String,
    val seq: Int,
    val isFinal: Boolean,
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int,
    @Serializable(with = ByteArrayAsBase64Serializer::class)
    val pcm: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioMessage) return false
        return streamId == other.streamId &&
            seq == other.seq &&
            isFinal == other.isFinal &&
            sampleRateHz == other.sampleRateHz &&
            channels == other.channels &&
            bitsPerSample == other.bitsPerSample &&
            pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int {
        var result = streamId.hashCode()
        result = 31 * result + seq
        result = 31 * result + isFinal.hashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + channels
        result = 31 * result + bitsPerSample
        result = 31 * result + pcm.contentHashCode()
        return result
    }

    override fun toString(): String =
        "AudioMessage(streamId=$streamId, seq=$seq, isFinal=$isFinal, pcm=${pcm.size}B)"
}
