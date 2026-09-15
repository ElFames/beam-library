package com.nubax.beam.library.sdk

import com.nubax.beam.library.connection.BeamConnection
import com.nubax.beam.library.connection.MeshBeamConnection

internal actual fun createDefaultBeamConnection(): BeamConnection = MeshBeamConnection()
