package com.nubax.beam.library.sdk

sealed class BeamResult<out T> {
    data class Success<out T>(val data: T) : BeamResult<T>()
    data class Failure(val message: String) : BeamResult<Nothing>()
}

inline fun <T> BeamResult<T>.onSuccess(action: (T) -> Unit): BeamResult<T> {
    if (this is BeamResult.Success) action(data)
    return this
}

inline fun <T> BeamResult<T>.onFailure(action: (String) -> Unit): BeamResult<T> {
    if (this is BeamResult.Failure) action(message)
    return this
}

fun <T> BeamResult<T>.getStateOrNull(): T? {
    return (this as BeamResult.Success).data
}

fun <T> BeamResult<T>.getErrorMessage(): String {
    return (this as BeamResult.Failure).message
}

inline fun <T, R> BeamResult<T>.map(transform: (T) -> R): BeamResult<R> {
    return when (this) {
        is BeamResult.Success -> BeamResult.Success(transform(data))
        is BeamResult.Failure -> this
    }
}