package com.nubax.beam.library.core

/**
 * Persistencia simple clave-valor que cada plataforma implementa sobre su
 * propio almacenamiento privado (filesDir en Android, carpeta de usuario en Desktop).
 * Beam nunca decide dónde vive el fichero: eso lo aporta quien inicializa el SDK.
 */
interface BeamStorage {
    fun readString(key: String): String?
    fun writeString(key: String, value: String)
}
