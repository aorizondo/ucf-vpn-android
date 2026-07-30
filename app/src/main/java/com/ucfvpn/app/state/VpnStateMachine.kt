package com.ucfvpn.app.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * State transition record with timestamp.
 */
data class StateTransition(
    val from: VpnState,
    val to: VpnState,
    val timestamp: Instant = Instant.now()
)

/**
 * VPN State Machine that manages state transitions and history.
 * Thread-safe implementation using Mutex for concurrent access.
 */
class VpnStateMachine {

    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _stateHistory = MutableStateFlow<List<StateTransition>>(emptyList())
    val stateHistory: StateFlow<List<StateTransition>> = _stateHistory.asStateFlow()

    private val mutex = Mutex()
    private var reconnectEnabled = false

    companion object {
        private const val MAX_HISTORY_SIZE = 20
    }

    /**
     * Attempt a state transition. Returns true if successful, false if invalid.
     */
    suspend fun transition(newState: VpnState): Boolean = mutex.withLock {
        val currentState = _state.value
        if (!isValidTransition(currentState, newState)) {
            return@withLock false
        }

        val transition = StateTransition(currentState, newState)
        _state.value = newState

        // Update history, keeping only last 20 transitions
        val newHistory = (_stateHistory.value + transition).takeLast(MAX_HISTORY_SIZE)
        _stateHistory.value = newHistory

        true
    }

    /**
     * Start the connection sequence from Disconnected state.
     */
    suspend fun connect(): Boolean {
        val currentState = _state.value
        return when (currentState) {
            is VpnState.Disconnected -> transition(VpnState.SstpConnecting)
            is VpnState.VpnRunning -> true // Already connected
            else -> {
                // Can only connect from Disconnected
                false
            }
        }
    }

    /**
     * Initiate disconnection from any state.
     */
    suspend fun disconnect(): Boolean {
        val currentState = _state.value
        return when {
            currentState == VpnState.Disconnected -> true // Already disconnected
            currentState.isError -> transition(VpnState.Disconnected)
            else -> transition(VpnState.Disconnected)
        }
    }

    /**
     * Enable or disable auto-reconnect functionality.
     */
    fun setReconnectEnabled(enabled: Boolean) {
        reconnectEnabled = enabled
    }

    /**
     * Check if reconnect is enabled.
     */
    fun isReconnectEnabled(): Boolean = reconnectEnabled

    /**
     * Validate if a state transition is allowed.
     */
    fun isValidTransition(from: VpnState, to: VpnState): Boolean {
        // Any state can go to Disconnected (manual stop)
        if (to == VpnState.Disconnected) return true

        // Error states can transition to SstpConnecting (for auto-reconnect)
        if (from.isError && to == VpnState.SstpConnecting) return true

        // Error states can go to Disconnected (manual stop)
        if (from.isError && to == VpnState.Disconnected) return true

        return when (from) {
            is VpnState.Disconnected -> to == VpnState.SstpConnecting
            is VpnState.SstpConnecting -> to == VpnState.SstpConnected || to is VpnState.SstpError
            is VpnState.SstpConnected -> to == VpnState.ProxyAuthenticating
            is VpnState.ProxyAuthenticating -> to == VpnState.ProxyAuthenticated || to is VpnState.ProxyError
            is VpnState.ProxyAuthenticated -> to == VpnState.WstunnelStarting
            is VpnState.WstunnelStarting -> to == VpnState.WstunnelRunning || to is VpnState.WstunnelError
            is VpnState.WstunnelRunning -> to == VpnState.WireGuardConnecting
            is VpnState.WireGuardConnecting -> to == VpnState.WireGuardConnected || to is VpnState.WireGuardError
            is VpnState.WireGuardConnected -> to == VpnState.VpnStarting
            is VpnState.VpnStarting -> to == VpnState.VpnRunning
            is VpnState.VpnRunning -> false // Must disconnect explicitly
            is VpnState.SstpError -> to == VpnState.SstpConnecting || to == VpnState.Disconnected
            is VpnState.ProxyError -> to == VpnState.SstpConnecting || to == VpnState.Disconnected
            is VpnState.WstunnelError -> to == VpnState.SstpConnecting || to == VpnState.Disconnected
            is VpnState.WireGuardError -> to == VpnState.SstpConnecting || to == VpnState.Disconnected
        }
    }

    /**
     * Get current state synchronously (for non-coroutine contexts).
     */
    fun getCurrentState(): VpnState = _state.value
}
