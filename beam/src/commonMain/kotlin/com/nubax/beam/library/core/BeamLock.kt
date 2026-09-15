package com.nubax.beam.library.core

/**
 * Exclusión mutua simple para estado compartido entre hilos (p. ej. [DeviceHistoryStore]).
 * `kotlin.jvm.Synchronized` es JVM-only y no compila en iOS — este es el reemplazo
 * portable mínimo, sin introducir una dependencia nueva (kotlinx-atomicfu) para un uso
 * tan puntual.
 */
internal expect class BeamLock() {
    fun <T> withLock(block: () -> T): T
}
