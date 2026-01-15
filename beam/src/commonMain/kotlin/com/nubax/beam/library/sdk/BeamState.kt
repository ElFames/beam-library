package com.nubax.beam.library.sdk

sealed class BeamState {
    data object Disabled : BeamState()
    data object Activated : BeamState()
    data object Connecting : BeamState()
    data class Connected(val deviceToken: String) : BeamState()
    data class Error(val message: String) : BeamState()
}