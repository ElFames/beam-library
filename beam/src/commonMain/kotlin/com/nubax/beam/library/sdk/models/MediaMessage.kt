package com.nubax.beam.library.sdk.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64

/**
 * Serializa un ByteArray como string base64 en vez del array JSON de números que usa
 * kotlinx.serialization por defecto (ese formato por defecto infla el tamaño varias veces).
 */
object ByteArrayAsBase64Serializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ByteArrayBase64", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.encode(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray {
        return Base64.decode(decoder.decodeString())
    }
}

/**
 * Formato para pasar un archivo/imagen entre dispositivos CON CONTEXTO: no son bytes sueltos,
 * llevan ya el nombre y una descripción, que es lo que más adelante permitirá buscar
 * "la última foto con mi madre" en vez de tener que navegar carpetas.
 *
 * Es una clase normal (no `data class`) con equals/hashCode/toString manuales a propósito:
 * un `data class` con un ByteArray dentro genera comparaciones por referencia, no por contenido,
 * lo cual habría sido un bug silencioso el día que alguien compare dos MediaMessage esperando
 * que compare las fotos, no las direcciones de memoria del array.
 */
@Serializable
class MediaMessage(
    val id: String,
    val name: String,
    val description: String,
    val mimeType: String,
    @Serializable(with = ByteArrayAsBase64Serializer::class)
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaMessage) return false
        return id == other.id &&
            name == other.name &&
            description == other.description &&
            mimeType == other.mimeType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "MediaMessage(id=$id, name=$name, description=$description, mimeType=$mimeType, bytes=${bytes.size}B)"
}
