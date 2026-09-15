package com.nubax.beam.library.storage

import com.nubax.beam.library.core.BeamStorage
import platform.Foundation.NSUserDefaults

class IosBeamStorage(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults
) : BeamStorage {

    override fun readString(key: String): String? = defaults.stringForKey(key)

    override fun writeString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }
}
