package com.ucfvpn.app.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe circular buffer for in-memory log storage.
 * Default capacity is 200 entries.
 */
class LogRepository(private val maxSize: Int = DEFAULT_MAX_SIZE) {

    private val buffer = ArrayDeque<LogEntry>(maxSize)
    private val mutex = Mutex()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val _entryCount = MutableStateFlow(0)
    val entryCount: StateFlow<Int> = _entryCount.asStateFlow()

    /**
     * Add a log entry. If the buffer is full, the oldest entry is evicted.
     */
    suspend fun add(entry: LogEntry) = mutex.withLock {
        if (buffer.size >= maxSize) {
            buffer.removeFirst()
        }
        buffer.addLast(entry)
        publishSnapshot()
    }

    /**
     * Add an entry synchronously (for callers not in a coroutine context).
     * Uses runBlocking internally — prefer [add] when possible.
     */
    fun addBlocking(entry: LogEntry) {
        synchronized(buffer) {
            if (buffer.size >= maxSize) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
        }
        _entries.value = buffer.toList()
        _entryCount.value = _entryCount.value + 1
    }

    /**
     * Get a copy of all current entries.
     */
    fun getEntries(): List<LogEntry> = synchronized(buffer) {
        buffer.toList()
    }

    /**
     * Get the N most recent entries.
     */
    fun getRecent(n: Int): List<LogEntry> = synchronized(buffer) {
        buffer.takeLast(n.coerceAtMost(buffer.size))
    }

    /**
     * Get entries filtered by category.
     */
    fun getByCategory(category: LogCategory): List<LogEntry> = synchronized(buffer) {
        buffer.filter { it.category == category }
    }

    /**
     * Get entries at or above the given severity level.
     */
    fun getByMinLevel(minLevel: LogLevel): List<LogEntry> = synchronized(buffer) {
        buffer.filter { it.level.ordinal >= minLevel.ordinal }
    }

    /**
     * Clear all entries.
     */
    fun clear() {
        synchronized(buffer) {
            buffer.clear()
        }
        _entries.value = emptyList()
        _entryCount.value = 0
    }

    /**
     * Number of entries currently stored.
     */
    fun size(): Int = synchronized(buffer) { buffer.size }

    /**
     * Dump all entries as a newline-separated string for sharing / clipboard.
     */
    fun dumpToString(): String = synchronized(buffer) {
        buffer.joinToString("\n") { it.toString() }
    }

    private fun publishSnapshot() {
        val snapshot = buffer.toList()
        _entries.value = snapshot
        _entryCount.value = snapshot.size
    }

    companion object {
        const val DEFAULT_MAX_SIZE = 200
    }
}
