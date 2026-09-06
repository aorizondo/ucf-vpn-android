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
            is VpnState.SstpConnected -> to is VpnState.PppNegotiating
            is VpnState.PppNegotiating ->
                to is VpnState.PppNegotiating || to is VpnState.PppAuthenticated || to is VpnState.SstpError
            // The TUN comes up right after PPP, before the captive portal and
            // wstunnel: away from the campus those are reachable only through
            // the tunnel, so they cannot precede it.
            is VpnState.PppAuthenticated -> to == VpnState.VpnStarting
            is VpnState.VpnStarting -> to == VpnState.ProxyAuthenticating || to is VpnState.WireGuardError
            is VpnState.ProxyAuthenticating -> to == VpnState.ProxyAuthenticated || to is VpnState.ProxyError
            is VpnState.ProxyAuthenticated -> to is VpnState.WstunnelStarting
            is VpnState.WstunnelStarting -> to is VpnState.WstunnelRunning || to is VpnState.WstunnelError
            // Last step bridges Internet traffic; the tunnel is already up.
            is VpnState.WstunnelRunning -> to == VpnState.VpnRunning ||
                to == VpnState.WireGuardConnecting || to is VpnState.WireGuardError
            is VpnState.WireGuardConnecting -> to == VpnState.WireGuardConnected || to is VpnState.WireGuardError
            is VpnState.WireGuardConnected -> to == VpnState.VpnRunning
            // A live tunnel can drop: it must be able to report the failure and
            // to restart the sequence. Allowing only Disconnected here made
            // auto-reconnect impossible (ReconnectManager restarts from
            // SstpConnecting) and left the UI showing "connected" after a drop.
            is VpnState.VpnRunning -> to == VpnState.SstpConnecting || to.isError
            is VpnState.SstpError ->
                to == VpnState.SstpConnecting || to == VpnState.Disconnected || to is VpnState.PppNegotiating
            is VpnState.ProxyError ->
                to == VpnState.SstpConnecting || to == VpnState.Disconnected || to == VpnState.ProxyAuthenticating
            is VpnState.WstunnelError ->
                to == VpnState.SstpConnecting || to == VpnState.Disconnected || to is VpnState.WstunnelStarting
            is VpnState.WireGuardError -> to == VpnState.SstpConnecting || to == VpnState.Disconnected
        }
    }

    /**
     * Get current state synchronously (for non-coroutine contexts).
     */
    fun getCurrentState(): VpnState = _state.value
}
