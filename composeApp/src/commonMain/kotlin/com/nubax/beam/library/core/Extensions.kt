package com.nubax.beam.library.core

inline fun <T> BleResult<T>.onSuccess(action: (T) -> Unit): BleResult<T> {
    if (this is BleResult.Success) action(data)
    return this
}
inline fun <T> BleResult<T>.onFailure(action: (String) -> Unit): BleResult<T> {
    if (this is BleResult.Failure) action(message)
    return this
}
fun <T> BleResult<T>.getStateOrNull(): T? {
    return (this as BleResult.Success).data
}
fun <T> BleResult<T>.getErrorMessage(): String {
    return (this as BleResult.Failure).message
}

inline fun <T, R> BleResult<T>.map(transform: (T) -> R): BleResult<R> {
    return when (this) {
        is BleResult.Success -> BleResult.Success(transform(data))
        is BleResult.Failure -> this
    }
}