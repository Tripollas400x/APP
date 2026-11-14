package com.phantompad.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro circular de eventos de conexión, visible en la pantalla de
 * diagnóstico. Sirve de base para el asistente de problemas.
 */
class ConnectionLog {

    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries

    fun add(message: String) {
        val line = "${format.format(Date())}  $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            _entries.value = lines.toList()
        }
    }

    companion object {
        private const val MAX_LINES = 250
    }
}
