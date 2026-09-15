@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.nubax.beam.media

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.darwin.NSObject
import kotlin.coroutines.resume

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

/** Selector nativo de fotos (PHPickerViewController, iOS 14+) — corre fuera de proceso, no pide permiso de galería. */
class IosImagePicker : ImagePicker {
    override suspend fun pick(): PickedFile? = suspendCancellableCoroutine { cont ->
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController
        if (root == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val config = PHPickerConfiguration()
        config.filter = PHPickerFilter.imagesFilter()
        config.selectionLimit = 1L

        val picker = PHPickerViewController(configuration = config)
        val delegate = PickerDelegate { picked ->
            picker.dismissViewControllerAnimated(true, completion = null)
            if (cont.isActive) cont.resume(picked)
        }
        picker.delegate = delegate
        root.presentViewController(picker, animated = true, completion = null)

        cont.invokeOnCancellation {
            picker.dismissViewControllerAnimated(true, completion = null)
        }
    }
}

private class PickerDelegate(
    private val onResult: (PickedFile?) -> Unit
) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        val result = didFinishPicking.firstOrNull() as? PHPickerResult
        val provider = result?.itemProvider
        if (provider == null || !provider.hasItemConformingToTypeIdentifier("public.image")) {
            onResult(null)
            return
        }
        provider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
            if (data == null) {
                onResult(null)
            } else {
                val bytes = data.toByteArrayCopy()
                val name = (provider.suggestedName ?: "imagen") + ".jpg"
                onResult(PickedFile(name = name, mimeType = "image/jpeg", bytes = bytes))
            }
        }
    }
}

private fun NSData.toByteArrayCopy(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val result = ByteArray(len)
    result.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), bytes, len.toULong())
    }
    return result
}

/**
 * Guarda lo recibido por Aircom en el directorio Documents de la app, para poder
 * comprobarlo. Usa E/S POSIX directa (fopen/fwrite) en vez de NSData.write(...) — evita
 * depender de cómo Kotlin/Native nombra en cada versión los métodos de Foundation
 * anotados con NS_SWIFT_NAME.
 */
class IosReceivedFileSaver : ReceivedFileSaver {
    override fun save(name: String, bytes: ByteArray): String {
        val docsDir = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String
            ?: return "(no se pudo resolver Documents)"
        val path = "$docsDir/$name"
        val file = platform.posix.fopen(path, "wb")
            ?: return "(no se pudo abrir $path para escritura)"
        try {
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    platform.posix.fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file)
                }
            }
        } finally {
            platform.posix.fclose(file)
        }
        return path
    }
}
