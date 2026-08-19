package com.nubax.beam.library.storage

import com.nubax.beam.library.core.BeamStorage
import java.io.File
import java.util.Properties

class DesktopBeamStorage(
    baseDir: File = File(System.getProperty("user.home"), ".udis-beam")
) : BeamStorage {

    private val file = File(baseDir, "storage.properties")
    private val properties = Properties()

    init {
        baseDir.mkdirs()
        if (file.exists()) {
            file.inputStream().use { properties.load(it) }
        }
    }

    @Synchronized
    override fun readString(key: String): String? = properties.getProperty(key)

    @Synchronized
    override fun writeString(key: String, value: String) {
        properties.setProperty(key, value)
        file.outputStream().use { properties.store(it, null) }
    }
}
