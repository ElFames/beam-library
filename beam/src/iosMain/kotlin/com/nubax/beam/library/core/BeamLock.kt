package com.nubax.beam.library.core

import platform.Foundation.NSLock

internal actual class BeamLock actual constructor() {
    private val lock = NSLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
