package com.ucfvpn.app.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlin.math.min

/**
 * Reconnection state definitions.
 */
sealed class ReconnectState {
    object Idle : ReconnectState()
    data class Waiting(val attempt: Int, val delayMs: Long) : ReconnectState()
    object Reconnecting : ReconnectState()
    object Stopped : ReconnectState()
}

/**
 * Auto-reconnect manager with exponential backoff.
 * Manages the reconnection loop and backoff timing.
 */
class ReconnectManager(
    private val onReconnect: suspend () -> Unit
) {

    private val _reconnectState = MutableStateFlow<ReconnectState>(ReconnectState.Idle)
    val reconnectState: StateFlow<ReconnectState> = _reconnectState.asStateFlow()

    private val mutex = Mutex()
    private var currentAttempt = 0
    private var currentBackoffMs = INITIAL_DELAY_MS

    /** Read by the reconnect loop outside the mutex, written by [stop] from another coroutine. */
    @Volatile
    private var isRunning = false

    companion object {
        const val INITIAL_DELAY_MS = 1_000L       // 1 second
        const val MAX_DELAY_MS = 32_000L          // 32 seconds
        const val MAX_ATTEMPTS = 0                 // 0 = infinite

        /** Cap for the exponential shift; 2^6 * 1s already exceeds [MAX_DELAY_MS]. */
        private const val MAX_SHIFT = 6
    }

    /**
     * Start the reconnect loop.
     *
     * The loop runs OUTSIDE the mutex: it is potentially infinite
     * ([MAX_ATTEMPTS] = 0), so holding the lock across it would starve [stop]
     * and [onSuccess] forever. The mutex only guards the entry check and the
     * counter mutations, which is all that needs to be serialised.
     */
    suspend fun start() {
        mutex.withLock {
            if (isRunning) return
            isRunning = true
            currentAttempt = 0
            currentBackoffMs = INITIAL_DELAY_MS
        }

        _reconnectState.value = ReconnectState.Idle

        while (isRunning) {
            // Update state to waiting with current attempt info
            _reconnectState.value = ReconnectState.Waiting(currentAttempt + 1, currentBackoffMs)

            // Wait for backoff delay
            delay(currentBackoffMs)

            if (!isRunning) break

            // Attempt reconnection
            _reconnectState.value = ReconnectState.Reconnecting

            try {
                onReconnect()
                // If we get here without exception, reconnect was successful
                // The caller should call onSuccess() if the connection actually succeeded
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Reconnection attempt failed, will retry with backoff
                Timber.w(e, "Reconnect attempt ${currentAttempt + 1} failed: ${e.message}")
            }

            if (!isRunning) break

            // Increment backoff for next attempt
            mutex.withLock {
                currentAttempt++
                currentBackoffMs = calculateNextBackoff(currentAttempt)
            }

            // Check if we've exceeded max attempts
            if (MAX_ATTEMPTS > 0 && currentAttempt >= MAX_ATTEMPTS) {
                _reconnectState.value = ReconnectState.Stopped
                break
            }
        }
    }

    /**
     * Stop the reconnect loop immediately.
     */
    suspend fun stop() = mutex.withLock {
        isRunning = false
        _reconnectState.value = ReconnectState.Stopped
    }

    /**
     * Call this when connection succeeds to reset backoff.
     */
    suspend fun onSuccess() = mutex.withLock {
        currentAttempt = 0
        currentBackoffMs = INITIAL_DELAY_MS
        _reconnectState.value = ReconnectState.Idle
    }

    /**
     * Reset backoff to initial delay.
     */
    fun resetBackoff() {
        currentAttempt = 0
        currentBackoffMs = INITIAL_DELAY_MS
    }

    /**
     * Calculate the next backoff delay using exponential backoff.
     * Formula: min(initialDelay * 2^attempt, maxDelay)
     *
     * The shift is capped at [MAX_SHIFT] because reconnection is unbounded
     * ([MAX_ATTEMPTS] = 0): without the cap, `1L shl attempt` overflows to a
     * negative value around attempt 63, `min` picks it, and `delay()` returns
     * immediately — turning the backoff into a hot reconnect loop.
     */
    private fun calculateNextBackoff(attempt: Int): Long {
        val exponentialDelay = INITIAL_DELAY_MS * (1L shl min(attempt, MAX_SHIFT))
        return min(exponentialDelay, MAX_DELAY_MS)
    }

    /**
     * Get current backoff in milliseconds.
     */
    fun getCurrentBackoffMs(): Long = currentBackoffMs

    /**
     * Get current attempt number.
     */
    fun getCurrentAttempt(): Int = currentAttempt + 1

    /**
     * Check if reconnect loop is running.
     */
    fun isRunning(): Boolean = isRunning
}
