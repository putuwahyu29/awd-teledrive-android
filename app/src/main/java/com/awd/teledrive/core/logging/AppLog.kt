package com.awd.teledrive.core.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

object AppLog {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
        addLog("DEBUG", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        android.util.Log.e(tag, message, throwable)
        addLog("ERROR", tag, message + (throwable?.let { "\n${it.stackTraceToString()}" } ?: ""))
    }

    fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
        addLog("INFO", tag, message)
    }

    private fun addLog(level: String, tag: String, message: String) {
        val time = dateFormat.format(Date())
        val logEntry = "[$time] $level/$tag: $message"
        val currentList = _logs.value.toMutableList()
        currentList.add(0, logEntry)
        if (currentList.size > 500) {
            currentList.removeAt(currentList.size - 1)
        }
        _logs.value = currentList
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
