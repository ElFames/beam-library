package com.nubax.beam.library.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

object Log {
    val logs = MutableStateFlow<List<String>>(emptyList())

    @OptIn(ExperimentalTime::class)
    fun i(message: String) {
        val now = Clock.System.now().toString().replace("Z", "").replace("T", " ")
        logs.update {
            val list = it.toMutableList()
            list.add("[INFO] - $now - $message")
            list.toList()
        }
    }

}