package com.vt.starry.sync

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CommunicationLogEntry(val timestamp: Long, val level: String, val direction: String, val message: String) {
    fun displayText(): String = TIME_FORMAT.get()!!.format(Date(timestamp)) + " " + level + " " + direction + " " + message
    companion object { private val TIME_FORMAT = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) } }
}

object CommunicationLog {
    private const val TAG = "StarryCommunication"
    private const val MAX_ENTRIES = 300
    private const val DUPLICATE_WINDOW_MS = 2_000L
    private val lock = Any()
    private val _entries = MutableStateFlow<List<CommunicationLogEntry>>(emptyList())
    val entries: StateFlow<List<CommunicationLogEntry>> = _entries.asStateFlow()
    private var lastEntryKey = ""
    private var lastEntryAt = 0L

    fun info(direction: String, message: String) = append("I", direction, message)
    fun warn(direction: String, message: String) = append("W", direction, message)
    fun error(direction: String, message: String, error: Throwable? = null) = append("E", direction, if (error == null) message else message + ": " + error.javaClass.simpleName + ": " + error.message.orEmpty())
    fun clear() { synchronized(lock) { _entries.value = emptyList() }; Log.i(TAG, "LOG cleared") }

    private fun append(level: String, direction: String, message: String) {
        val sanitized = message.replace(Regex("[\\r\\n]+"), " ").take(500)
        val entry = CommunicationLogEntry(System.currentTimeMillis(), level, direction, sanitized)
        val now = entry.timestamp
        val key = level + "|" + direction + "|" + sanitized
        synchronized(lock) {
            if (key == lastEntryKey && now - lastEntryAt < DUPLICATE_WINDOW_MS) return
            lastEntryKey = key
            lastEntryAt = now
            _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        }
        val line = direction + " " + sanitized
        when (level) { "E" -> Log.e(TAG, line); "W" -> Log.w(TAG, line); else -> Log.i(TAG, line) }
    }
}
