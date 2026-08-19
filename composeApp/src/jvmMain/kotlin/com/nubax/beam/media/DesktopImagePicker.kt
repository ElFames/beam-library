package com.nubax.beam.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

class DesktopImagePicker : ImagePicker {
    override suspend fun pick(): PickedFile? = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, "Elegir imagen", FileDialog.LOAD)
        dialog.isVisible = true
        val directory = dialog.directory ?: return@withContext null
        val fileName = dialog.file ?: return@withContext null
        val file = File(directory, fileName)
        if (!file.exists()) return@withContext null

        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            else -> "application/octet-stream"
        }
        PickedFile(file.name, mime, file.readBytes())
    }
}

class DesktopReceivedFileSaver(
    private val baseDir: File = File(System.getProperty("user.home"), ".udis-beam/received")
) : ReceivedFileSaver {
    override fun save(name: String, bytes: ByteArray): String {
        baseDir.mkdirs()
        val file = File(baseDir, name)
        file.writeBytes(bytes)
        return file.absolutePath
    }
}
