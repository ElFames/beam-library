package com.nubax.beam.media

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import java.io.File

class AndroidImagePicker(private val activity: ComponentActivity) : ImagePicker {

    private var pending: CompletableDeferred<PickedFile?>? = null

    // Debe registrarse antes de que la Activity llegue a STARTED, por eso se hace aquí
    // (esta clase se instancia en onCreate, antes de setContent).
    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val result = uri?.let { readPickedFile(it) }
        pending?.complete(result)
        pending = null
    }

    private fun readPickedFile(uri: Uri): PickedFile? {
        val resolver = activity.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: uri.lastPathSegment ?: "imagen"
        return PickedFile(name, mime, bytes)
    }

    override suspend fun pick(): PickedFile? {
        val deferred = CompletableDeferred<PickedFile?>()
        pending = deferred
        launcher.launch("image/*")
        return deferred.await()
    }
}

class AndroidReceivedFileSaver(private val activity: ComponentActivity) : ReceivedFileSaver {
    override fun save(name: String, bytes: ByteArray): String {
        val dir = File(activity.getExternalFilesDir(null), "received").apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(bytes)
        return file.absolutePath
    }
}
