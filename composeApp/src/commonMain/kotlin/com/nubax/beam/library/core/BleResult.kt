package com.nubax.beam.library.core

sealed class BleResult<out T> {
    data class Success<out T>(val data: T) : BleResult<T>()
    data class Failure(val message: String) : BleResult<Nothing>()
}