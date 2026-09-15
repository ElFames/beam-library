package com.nubax.beam.library.core

internal actual class BeamLock actual constructor() {
    actual fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}
