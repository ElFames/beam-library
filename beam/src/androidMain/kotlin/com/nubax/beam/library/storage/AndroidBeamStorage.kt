package com.nubax.beam.library.storage

import android.content.Context
import com.nubax.beam.library.core.BeamStorage

class AndroidBeamStorage(context: Context) : BeamStorage {

    private val prefs = context.applicationContext
        .getSharedPreferences("udis_beam_storage", Context.MODE_PRIVATE)

    override fun readString(key: String): String? = prefs.getString(key, null)

    override fun writeString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
