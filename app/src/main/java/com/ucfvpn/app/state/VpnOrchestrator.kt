package com.ucfvpn.app.state

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Connection step result returned by injected handlers.
 */
sealed class ConnectionResult {
    object Success : ConnectionResult()
    data class Failure(val message: String, val retryable: Boolean = true) : ConnectionResult()
}

/**
 * Orchestrator that ties VpnStateMachine and ReconnectManager together
 * to manage the full VPN connection lifecycle with auto-reconnection.
 *
 * Connection steps are injectable via the [handlers] parameter, enabling
 * full testability without real network dependencies.
 */
class VpnOrchestrator(
    val stateMachine: VpnStateMachine = VpnStateMachine(),
    private val handlers: ConnectionHandlers = ConnectionHandlers(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val mutex = Mutex()
    private var reconnectJob: Job? = null
    private var connectJob: Job? = null
    private var isConnecting = false

    private val _reconnectManager: ReconnectManager by lazy {
        ReconnectManager(onReconnect = { attemptReconnect() })
    }
    val reconnectManager: ReconnectManager get() = _reconnectManager

    private val _connectionResult = MutableStateFlow<ConnectionResult?>(null)
    val connectionResult: StateFlow<ConnectionResult?> = _connectionResult.asStateFlow()

    /**
     * Start the full VPN connection sequence.
     */
    fun connect() {
        scope.launch {
            mutex.withLock {
                if (isConnecting) return@launch
                isConnecting = true
            }
            try {
                executeConnectionSequence()
            } finally {
                mutex.withLock { isConnecting = false }
            }
        }
    }

    /**
     * Disconnect and stop auto-reconnection.
     */
    fun disconnect() {
        scope.launch {
            mutex.withLock {
                reconnectJob?.cancel()
                reconnectJob = null
                connectJob?.cancel()
                connectJob = null
                isConnecting = false
            }
            reconnectManager.stop()
            stateMachine.setReconnectEnabled(false)
            stateMachine.disconnect()
            _connectionResult.value = null
        }
    }

    /**
     * Simulate an external disconnection event (e.g., WiFi drop, network change).
     * Triggers appropriate state transition and starts auto-reconnect if enabled.
     */
    fun simulateExternalDisconnect(reason: String) {
        scope.launch {
            mutex.withLock {
                if (connectJob?.isActive == true) {
                    connectJob?.cancel()
                    connectJob = null
                }
                isConnecting = false
            }

            val currentState = stateMachine.getCurrentState()

            // VpnRunning and VpnStarting can only transition to Disconnected
            if (currentState == VpnState.VpnRunning || currentState == VpnState.VpnStarting) {
                stateMachine.disconnect()
                if (stateMachine.isReconnectEnabled()) {
                    stateMachine.connect() // Starts fresh connection from Disconnected
                    executeConnectionSequence()
                }
                return@launch
            }

            // Map other states to their error equivalents
            val errorState = when {
                currentState == VpnState.SstpConnecting ||
                currentState == VpnState.SstpConnected -> VpnState.SstpError(reason)
                currentState == VpnState.ProxyAuthenticating ||
                currentState == VpnState.ProxyAuthenticated -> VpnState.ProxyError(reason)
                currentState == VpnState.WstunnelStarting ||
                currentState == VpnState.WstunnelRunning -> VpnState.WstunnelError(reason)
                currentState == VpnState.WireGuardConnecting ||
                currentState == VpnState.WireGuardConnected -> VpnState.WireGuardError(reason)
                else -> VpnState.SstpError(reason)
            }

            stateMachine.transition(errorState)

            if (stateMachine.isReconnectEnabled()) {
                startReconnect()
            }
        }
    }

    /**
     * Enable or disable auto-reconnect.
     */
    fun setReconnectEnabled(enabled: Boolean) {
        stateMachine.setReconnectEnabled(enabled)
    }

    private suspend fun executeConnectionSequence() {
        try {
            // Step 1: Connect (state machine handles transition to SstpConnecting)
            if (!stateMachine.connect()) {
                _connectionResult.value = ConnectionResult.Failure("Cannot connect from current state")
                return
            }

            // Step 2: SSTP connection
            val sstpResult = withTimeoutOrNull(handlers.sstpTimeoutMs) {
                handlers.connectSstp()
            } ?: ConnectionResult.Failure("SSTP connection timed out")

            if (sstpResult !is ConnectionResult.Success) {
                stateMachine.transition(VpnState.SstpError((sstpResult as ConnectionResult.Failure).message))
                startReconnectIfEnabled()
                _connectionResult.value = sstpResult
                return
            }
            stateMachine.transition(VpnState.SstpConnected)

            // Step 3: Proxy authentication
            stateMachine.transition(VpnState.ProxyAuthenticating)
            val proxyResult = withTimeoutOrNull(handlers.proxyTimeoutMs) {
                handlers.authenticateProxy()
            } ?: ConnectionResult.Failure("Proxy authentication timed out")

            if (proxyResult !is ConnectionResult.Success) {
                stateMachine.transition(VpnState.ProxyError((proxyResult as ConnectionResult.Failure).message))
                startReconnectIfEnabled()
                _connectionResult.value = proxyResult
                return
            }
            stateMachine.transition(VpnState.ProxyAuthenticated)

            // Step 4: wstunnel startup
            stateMachine.transition(VpnState.WstunnelStarting)
            val wsResult = withTimeoutOrNull(handlers.wstunnelTimeoutMs) {
                handlers.startWstunnel()
            } ?: ConnectionResult.Failure("wstunnel startup timed out")

            if (wsResult !is ConnectionResult.Success) {
                stateMachine.transition(VpnState.WstunnelError((wsResult as ConnectionResult.Failure).message))
                startReconnectIfEnabled()
                _connectionResult.value = wsResult
                return
            }
            stateMachine.transition(VpnState.WstunnelRunning)

            // Step 5: WireGuard configuration and connection
            stateMachine.transition(VpnState.WireGuardConnecting)
            val wgResult = withTimeoutOrNull(handlers.wgTimeoutMs) {
                handlers.connectWireGuard()
            } ?: ConnectionResult.Failure("WireGuard configuration timed out")

            if (wgResult !is ConnectionResult.Success) {
                stateMachine.transition(VpnState.WireGuardError((wgResult as ConnectionResult.Failure).message))
                startReconnectIfEnabled()
                _connectionResult.value = wgResult
                return
            }
            stateMachine.transition(VpnState.WireGuardConnected)

            // Step 6: Start VPN service
            stateMachine.transition(VpnState.VpnStarting)
            val vpnResult = withTimeoutOrNull(handlers.vpnTimeoutMs) {
                handlers.startVpn()
            } ?: ConnectionResult.Failure("VPN service startup timed out")

            if (vpnResult !is ConnectionResult.Success) {
                // VPN start failure → treat as WireGuard error for reconnect purposes
                stateMachine.transition(VpnState.WireGuardError((vpnResult as ConnectionResult.Failure).message))
                startReconnectIfEnabled()
                _connectionResult.value = vpnResult
                return
            }
            stateMachine.transition(VpnState.VpnRunning)

            // Connection successful!
            _connectionResult.value = ConnectionResult.Success
            reconnectManager.onSuccess()

        } catch (e: CancellationException) {
            // Connection was cancelled (e.g., disconnect called)
        } catch (e: Exception) {
            val currentState = stateMachine.getCurrentState()
            val errorState = mapToErrorState(currentState, e.message ?: "Unknown error")
            stateMachine.transition(errorState)
            _connectionResult.value = ConnectionResult.Failure(e.message ?: "Unknown error")
            startReconnectIfEnabled()
        }
    }

    private fun mapToErrorState(currentState: VpnState, message: String): VpnState {
        return when (currentState) {
            is VpnState.SstpConnecting -> VpnState.SstpError(message)
            is VpnState.ProxyAuthenticating -> VpnState.ProxyError(message)
            is VpnState.WstunnelStarting -> VpnState.WstunnelError(message)
            is VpnState.WireGuardConnecting -> VpnState.WireGuardError(message)
            is VpnState.VpnStarting -> VpnState.WireGuardError(message)
            else -> VpnState.SstpError(message)
        }
    }

    private suspend fun startReconnectIfEnabled() {
        if (stateMachine.isReconnectEnabled()) {
            startReconnect()
        }
    }

    private fun startReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectManager.start()
        }
    }

    private suspend fun attemptReconnect() {
        try {
            val currentState = stateMachine.getCurrentState()
            if (currentState.isError) {
                stateMachine.transition(VpnState.SstpConnecting)
            } else {
                return
            }

            // Retry SSTP
            val sstpResult = withTimeoutOrNull(handlers.sstpTimeoutMs) {
                handlers.connectSstp()
            } ?: ConnectionResult.Failure("SSTP reconnection timed out")

            if (sstpResult !is ConnectionResult.Success) {
                val msg = (sstpResult as ConnectionResult.Failure).message
                // Stay in SstpConnecting or transition back to error?
                // The ReconnectManager will retry automatically
                throw RuntimeException(msg)
            }
            stateMachine.transition(VpnState.SstpConnected)

            // Retry proxy
            stateMachine.transition(VpnState.ProxyAuthenticating)
            val proxyResult = withTimeoutOrNull(handlers.proxyTimeoutMs) {
                handlers.authenticateProxy()
            } ?: ConnectionResult.Failure("Proxy re-auth timed out")

            if (proxyResult !is ConnectionResult.Success) {
                throw RuntimeException((proxyResult as ConnectionResult.Failure).message)
            }
            stateMachine.transition(VpnState.ProxyAuthenticated)

            // Retry wstunnel
            stateMachine.transition(VpnState.WstunnelStarting)
            val wsResult = withTimeoutOrNull(handlers.wstunnelTimeoutMs) {
                handlers.startWstunnel()
            } ?: ConnectionResult.Failure("wstunnel restart timed out")

            if (wsResult !is ConnectionResult.Success) {
                throw RuntimeException((wsResult as ConnectionResult.Failure).message)
            }
            stateMachine.transition(VpnState.WstunnelRunning)

            // Retry WireGuard
            stateMachine.transition(VpnState.WireGuardConnecting)
            val wgResult = withTimeoutOrNull(handlers.wgTimeoutMs) {
                handlers.connectWireGuard()
            } ?: ConnectionResult.Failure("WireGuard reconnection timed out")

            if (wgResult !is ConnectionResult.Success) {
                throw RuntimeException((wgResult as ConnectionResult.Failure).message)
            }
            stateMachine.transition(VpnState.WireGuardConnected)

            // Retry VPN service
            stateMachine.transition(VpnState.VpnStarting)
            val vpnResult = withTimeoutOrNull(handlers.vpnTimeoutMs) {
                handlers.startVpn()
            } ?: ConnectionResult.Failure("VPN service restart timed out")

            if (vpnResult !is ConnectionResult.Success) {
                throw RuntimeException((vpnResult as ConnectionResult.Failure).message)
            }
            stateMachine.transition(VpnState.VpnRunning)

            // Reconnection successful
            reconnectManager.onSuccess()
            _connectionResult.value = ConnectionResult.Success

        } catch (e: CancellationException) {
            // Reconnection cancelled
        } catch (e: Exception) {
            val currentState = stateMachine.getCurrentState()
            val errorState = mapToErrorState(currentState, e.message ?: "Reconnection failed")
            stateMachine.transition(errorState)
            _connectionResult.value = ConnectionResult.Failure(e.message ?: "Reconnection failed")
            // ReconnectManager will handle the retry automatically
        }
    }
}

/**
 * Injectable connection handlers for each VPN stack component.
 * Each handler returns [ConnectionResult.Success] or [ConnectionResult.Failure].
 */
data class ConnectionHandlers(
    val connectSstp: suspend () -> ConnectionResult = { ConnectionResult.Success },
    val authenticateProxy: suspend () -> ConnectionResult = { ConnectionResult.Success },
    val startWstunnel: suspend () -> ConnectionResult = { ConnectionResult.Success },
    val connectWireGuard: suspend () -> ConnectionResult = { ConnectionResult.Success },
    val startVpn: suspend () -> ConnectionResult = { ConnectionResult.Success },
    val sstpTimeoutMs: Long = 30_000L,
    val proxyTimeoutMs: Long = 15_000L,
    val wstunnelTimeoutMs: Long = 15_000L,
    val wgTimeoutMs: Long = 10_000L,
    val vpnTimeoutMs: Long = 5_000L
)
