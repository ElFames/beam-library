package com.nubax.beam.library.core

sealed class BeamState {
    data object Offline : BeamState()
    data object Online : BeamState()
    data object Connecting : BeamState()
    data class Connected(val deviceToken: String) : BeamState()
    data class Error(val message: String) : BeamState()
}