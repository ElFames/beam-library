package com.nubax.beam.library.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

object Log {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    fun i(message: String) {
       log(tag = "INFO", message = message)
    }

    fun e(message: String) {
        log(tag = "ERROR", message = message)
    }

    @OptIn(ExperimentalTime::class)
    private fun log(tag: String, message: String) {
        val now = Clock.System.now().toString().replace("Z", "").replace("T", " ")
        _logs.update {
            val list = it.toMutableList()
            list.add("[$tag] - $now - $message")
            list.toList()
        }
    }

}