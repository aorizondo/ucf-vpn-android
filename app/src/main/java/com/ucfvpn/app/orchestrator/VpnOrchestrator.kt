package com.ucfvpn.app.orchestrator

import android.content.Context
import com.ucfvpn.app.proxy.ProxyAuthService
import com.ucfvpn.app.proxy.ProxyAuthState
import com.ucfvpn.app.sstp.client.SstpState
import com.ucfvpn.app.sstp.client.SstpTunnel
import com.ucfvpn.app.state.ConnectionState
import com.ucfvpn.app.state.ReconnectManager
import com.ucfvpn.app.state.ReconnectState
import com.ucfvpn.app.state.VpnState
import com.ucfvpn.app.state.VpnStateMachine
import com.ucfvpn.app.vpn.VpnConfig
import com.ucfvpn.app.vpn.VpnGatewayService
import com.ucfvpn.app.vpn.WireGuardState
import com.ucfvpn.app.vpn.WireGuardManager
import com.ucfvpn.app.wg.WireGuardConfig
import com.ucfvpn.app.wg.WireGuardConfigRepository
import com.ucfvpn.app.wstunnel.WstunnelConfig
import com.ucfvpn.app.wstunnel.WstunnelManager
import com.ucfvpn.app.wstunnel.WstunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Instant

/**
 * Immutable configuration bundle for the full VPN stack.
 * Contains all credentials and configuration needed to establish the stacked VPN:
 * SSTP → Proxy Auth → wstunnel → WireGuard → VpnService
 */
data class AppConfig(
    val sstpServer: String = "npv.ucf.edu.cu",
    val sstpPort: Int = 443,
    val sstpUsername: String,
    val sstpPassword: String,
    val proxyUsername: String,
    val proxyPassword: String,
    val wgConfig: WireGuardConfig,
    val wstunnelConfig: WstunnelConfig = WstunnelConfig()
)

/**
 * A single log entry emitted by the orchestrator.
 */
data class LogEntry(
    val level: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Central orchestrator that coordinates the full VPN stack.
 *
 * ## Connection Sequence
 * ```
 * 1. VpnState.SstpConnecting → SstpTunnel.connect()
 * 2. VpnState.SstpConnected → wait for PPP IP assignment
 * 3. VpnState.ProxyAuthenticating → ProxyAuthService.login()
 * 4. VpnState.ProxyAuthenticated → verify session
 * 5. VpnState.WstunnelStarting → WstunnelManager.start()
 * 6. VpnState.WstunnelRunning → wait for UDP:51820
 * 7. VpnState.WireGuardConnecting → WireGuardManager.start()
 * 8. VpnState.WireGuardConnected → verify tunnel
 * 9. VpnState.VpnStarting → VpnGatewayService.establishTunInterface()
 * 10. VpnState.VpnRunning → all systems go!
 * ```
 *
 * ## Error Handling
 * - Each step wrapped in try/catch
 * - On error: set corresponding error state (SstpError, ProxyError, etc.)
 * - If ReconnectManager is active: trigger reconnect from first failed step
 * - On fatal error: stop all components
 *
 * ## Cleanup Sequence
 * ```
 * 1. WireGuardManager.stop()
 * 2. WstunnelManager.stop()
 * 3. ProxyAuthService.reset()
 * 4. SstpTunnel.disconnect()
 * 5. VpnGatewayService.shutdown()
 * 6. State = Disconnected
 * ```
 *
 * @param context Android context for wstunnel binary extraction
 * @param vpnService VpnGatewayService instance for TUN interface management
 * @param sstpTunnel SSTP tunnel implementation
 * @param proxyAuthService Proxy authentication service
 * @param wstunnelManager wstunnel process manager
 * @param wireGuardConfigRepository Repository for WireGuard configuration
 * @param stateMachine VPN state machine for state transitions
 * @param reconnectManager Optional reconnection manager with exponential backoff
 */
class VpnOrchestrator(
    private val context: Context,
    private val sstpTunnel: SstpTunnel,
    private val proxyAuthService: ProxyAuthService,
    private val wstunnelManager: WstunnelManager,
    private val wireGuardConfigRepository: WireGuardConfigRepository,
    private val stateMachine: VpnStateMachine = VpnStateMachine(),
    reconnectManager: ReconnectManager? = null

    /**
     * VpnGatewayService instance (nullable — set after service binding).
     * When null, WireGuard and VPN service steps are skipped gracefully.
     */
    var vpnService: VpnGatewayService? = null
) {
    // ── Public API ────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    /** Current VPN state from the state machine */
    val state: StateFlow<VpnState> = stateMachine.state

    /** Connection state mapped for UI consumption */
    val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected).also { flow ->
        scope.launch {
            stateMachine.state.collect { vpnState ->
                flow.value = mapToConnectionState(vpnState)
            }
        }
    }

    /** Circular log buffer of connection events for UI display */
    private val _connectionLog = MutableStateFlow<List<String>>(emptyList())
    val connectionLog: StateFlow<List<String>> = _connectionLog.asStateFlow()

    /** Flow of individual log entries for real-time UI updates */
    private val _logFlow = MutableSharedFlow<LogEntry>(replay = 0, extraBufferCapacity = 64)
    val logFlow: SharedFlow<LogEntry> = _logFlow.asSharedFlow()

    // ── Private fields ────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()
    private val logMutex = Mutex()

    private var config: AppConfig? = null
    private var isRunning = false
    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null

    // Track which component caused the last error for reconnection
    private var lastErrorStage: String? = null

    // Internal reconnect manager if not provided
    private val internalReconnectManager = reconnectManager ?: ReconnectManager(
        onReconnect = {
            val cfg = config
            if (cfg != null) performConnectionSequence(cfg)
        }
    )
    private val reconnectManager: ReconnectManager = internalReconnectManager

    // ── Initialization ────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    init {
        // Observe state machine and emit logs on state transitions
        scope.launch {
            stateMachine.state.collect { vpnState ->
                emitLog("INFO", "State: ${vpnState.displayName}")
            }
        }

        // Observe reconnect state
        scope.launch {
            reconnectManager.reconnectState.collect { reconnectState ->
                when (reconnectState) {
                    is ReconnectState.Waiting ->
                        emitLog("INFO", "Reconnect attempt ${reconnectState.attempt} in ${reconnectState.delayMs}ms")
                    is ReconnectState.Reconnecting ->
                        emitLog("INFO", "Reconnecting...")
                    is ReconnectState.Stopped ->
                        emitLog("INFO", "Reconnect stopped")
                    else -> { /* Idle */ }
                }
            }
        }
    }

    // ── Connection log helpers ────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    private suspend fun appendLog(message: String) = logMutex.withLock {
        val entry = "[${Instant.now()}] $message"
        val current = _connectionLog.value.toMutableList()
        if (current.size >= MAX_LOG_ENTRIES) {
            current.removeAt(0)
        }
        current.add(entry)
        _connectionLog.value = current
    }

    private suspend fun emitLog(level: String, message: String) {
        val entry = LogEntry(level = level, message = message)
        _logFlow.tryEmit(entry)
        appendLog("[$level] $message")
        Timber.tag(TAG).d("[$level] $message")
    }

    private fun clearLog() {
        _connectionLog.value = emptyList()
    }

    // ── Public methods ─────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    /**
     * Start the full VPN stack with the given configuration.
     *
     * This is a suspending function that performs the full connection sequence.
     * It returns when the connection is established (VpnRunning) or fails.
     *
     * @param appConfig Complete configuration for all VPN components
     * @throws Exception if the connection fails at any stage
     */
    suspend fun start(appConfig: AppConfig) = mutex.withLock {
        if (isRunning) {
            Timber.tag(TAG).w("VpnOrchestrator already running")
            return
        }

        isRunning = true
        config = appConfig
        clearLog()

        // Enable reconnect if manager is available
        stateMachine.setReconnectEnabled(true)

        // Start the connection sequence in a coroutine
        connectionJob = scope.launch {
            try {
                performConnectionSequence(appConfig)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Connection sequence failed")
                handleConnectionError(e)
            }
        }
    }

    /**
     * Start VPN connection (convenience method for UiConfig compatibility).
     */
    fun connect(uiConfig: com.ucfvpn.app.ui.viewmodel.UiConfig) {
        scope.launch {
            // Build WireGuardConfig from uiConfig
            val wgConfig = WireGuardConfig(
                privateKey = "", // Private key managed via Keystore
                address = uiConfig.wireGuardLocalIp,
                dns = uiConfig.wireGuardDns.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                peerEndpoint = uiConfig.wireGuardEndpoint,
                peerPublicKey = "", // Configured separately
                peerPresharedKey = null,
                allowedIps = listOf("0.0.0.0/0", "::/0")
            )

            val appConfig = AppConfig(
                sstpServer = uiConfig.sstpHost,
                sstpPort = uiConfig.sstpPort,
                sstpUsername = uiConfig.sstpUsername,
                sstpPassword = uiConfig.sstpPassword,
                proxyUsername = uiConfig.proxyUsername,
                proxyPassword = uiConfig.proxyPassword,
                wgConfig = wgConfig,
                wstunnelConfig = WstunnelConfig(
                    serverUrl = uiConfig.wstunnelUrl,
                    mode = when (uiConfig.wstunnelMode) {
                        com.ucfvpn.app.ui.viewmodel.WstunnelMode.FIXED -> WstunnelConfig.Mode.FIXED
                        com.ucfvpn.app.ui.viewmodel.WstunnelMode.DYNAMIC -> WstunnelConfig.Mode.DYNAMIC
                    }
                )
            )

            start(appConfig)
        }
    }

    /**
     * Stop the full VPN stack cleanly.
     *
     * This follows the cleanup sequence:
     * 1. WireGuardManager.stop()
     * 2. WstunnelManager.stop()
     * 3. ProxyAuthService.reset()
     * 4. SstpTunnel.disconnect()
     * 5. VpnGatewayService.shutdown()
     * 6. State = Disconnected
     */
    suspend fun stop() = mutex.withLock {
        if (!isRunning) {
            Timber.tag(TAG).d("VpnOrchestrator already stopped")
            return
        }

        Timber.tag(TAG).d("Stopping VPN stack...")
        emitLog("INFO", "Initiating clean shutdown...")

        // Cancel connection/reconnect jobs
        connectionJob?.cancel()
        reconnectJob?.cancel()

        // Stop reconnect manager
        reconnectManager.stop()

        try {
            // Cleanup sequence (in reverse order)
            cleanupVpnService()
            cleanupWireGuard()
            cleanupWstunnel()
            cleanupProxyAuth()
            cleanupSstp()

            emitLog("INFO", "VPN stack stopped cleanly")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during cleanup")
            emitLog("ERROR", "Shutdown error: ${e.message}")
        } finally {
            isRunning = false
            config = null
            lastErrorStage = null
            stateMachine.disconnect()
        }
    }

    /**
     * Disconnect alias for UI compatibility.
     */
    fun disconnect() {
        scope.launch { stop() }
    }

    /**
     * Check if the VPN stack is currently running.
     */
    fun isRunning(): Boolean = isRunning

    /**
     * Get the current WireGuard state.
     */
    fun getWireGuardState(): WireGuardState? = vpnService?.getWireGuardState()

    /**
     * Get the current wstunnel state.
     */
    fun getWstunnelState(): WstunnelState = wstunnelManager.state.value

    /**
     * Save configuration to repository.
     */
    fun saveConfig(uiConfig: com.ucfvpn.app.ui.viewmodel.UiConfig) {
        val wgConfig = WireGuardConfig(
            privateKey = "", // Private key managed via Keystore
            address = uiConfig.wireGuardLocalIp,
            dns = uiConfig.wireGuardDns.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            peerEndpoint = uiConfig.wireGuardEndpoint,
            peerPublicKey = "", // Configured separately
            peerPresharedKey = null,
            allowedIps = listOf("0.0.0.0/0", "::/0")
        )
        wireGuardConfigRepository.saveConfig(wgConfig)
    }

    /**
     * Report that the connection has fully succeeded (resets reconnect backoff).
     */
    suspend fun notifyConnectionSuccess() {
        reconnectManager.onSuccess()
        emitLog("INFO", "Connection established successfully")
    }

    /**
     * Report a connection error.
     */
    suspend fun notifyError(message: String) {
        emitLog("ERROR", message)
        if (stateMachine.isReconnectEnabled() && isRunning) {
            reconnectManager.start()
        }
    }

    /**
     * Shutdown the orchestrator and release all resources.
     */
    fun shutdown() {
        scope.launch {
            reconnectManager.stop()
            if (isRunning) {
                stop()
            }
        }
        scope.launch { 
            // Cancel after a brief delay to allow cleanup
            delay(100)
            this@VpnOrchestrator.scope.cancel() 
        }
    }

    /**
     * Clear all log entries.
     */
    fun clearLogs() {
        _connectionLog.value = emptyList()
    }

    // ── Connection sequence ────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    private suspend fun performConnectionSequence(appConfig: AppConfig) {
        emitLog("INFO", "Starting VPN connection sequence...")

        // Step 1: SSTP Connecting
        if (!stateMachine.transition(VpnState.SstpConnecting)) {
            throw IllegalStateException("Failed to transition to SstpConnecting")
        }
        emitLog("INFO", "SSTP: Connecting to ${appConfig.sstpServer}:${appConfig.sstpPort}...")

        try {
            // Setup SSTP callbacks
            setupSstpCallbacks()

            // CRITICAL: Protect the socket BEFORE connecting (prevents traffic loop)
            // The SstpHandshake internally creates and protects the socket
            sstpTunnel.connect(appConfig.sstpServer, appConfig.sstpPort)

            // Wait for SSTP connection
            waitForSstpConnection()

            emitLog("INFO", "SSTP: Connected successfully")
            lastErrorStage = null

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "SSTP connection failed")
            lastErrorStage = "SSTP"
            stateMachine.transition(VpnState.SstpError("SSTP connection failed: ${e.message}"))
            throw e
        }

        // Step 2: SSTP Connected - wait for PPP IP assignment
        if (!stateMachine.transition(VpnState.SstpConnected)) {
            throw IllegalStateException("Failed to transition to SstpConnected")
        }
        emitLog("INFO", "SSTP: Waiting for PPP IP assignment...")

        // Wait for PPP to negotiate IP (typically handled by SSTP internals)
        // The SstpTunnel.localAddress will be set when PPP succeeds
        waitForPppIpAssignment()

        // Step 3: Proxy Authentication
        if (!stateMachine.transition(VpnState.ProxyAuthenticating)) {
            throw IllegalStateException("Failed to transition to ProxyAuthenticating")
        }
        emitLog("INFO", "Proxy: Authenticating to captive portal...")

        try {
            val proxyResult = proxyAuthService.login(
                appConfig.proxyUsername,
                appConfig.proxyPassword
            )

            if (proxyResult.isFailure) {
                throw proxyResult.exceptionOrNull()
                    ?: Exception("Unknown proxy auth error")
            }

            emitLog("INFO", "Proxy: Authentication successful")
            lastErrorStage = null

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Proxy authentication failed")
            lastErrorStage = "Proxy"
            stateMachine.transition(VpnState.ProxyError("Proxy auth failed: ${e.message}"))
            throw e
        }

        // Step 4: Proxy Authenticated
        if (!stateMachine.transition(VpnState.ProxyAuthenticated)) {
            throw IllegalStateException("Failed to transition to ProxyAuthenticated")
        }
        emitLog("INFO", "Proxy: Session verified")

        // Step 5: Wstunnel Starting
        if (!stateMachine.transition(VpnState.WstunnelStarting)) {
            throw IllegalStateException("Failed to transition to WstunnelStarting")
        }
        emitLog("INFO", "wstunnel: Starting UDP tunnel to ${appConfig.wstunnelConfig.serverUrl}...")

        try {
            val wstunnelResult = wstunnelManager.start(appConfig.wstunnelConfig)

            if (wstunnelResult.isFailure) {
                throw wstunnelResult.exceptionOrNull()
                    ?: Exception("Unknown wstunnel error")
            }

            emitLog("INFO", "wstunnel: Process started")
            lastErrorStage = null

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "wstunnel start failed")
            lastErrorStage = "wstunnel"
            stateMachine.transition(VpnState.WstunnelError("wstunnel failed: ${e.message}"))
            throw e
        }

        // Step 6: Wstunnel Running - wait for UDP port
        if (!stateMachine.transition(VpnState.WstunnelRunning)) {
            throw IllegalStateException("Failed to transition to WstunnelRunning")
        }
        emitLog("INFO", "wstunnel: Waiting for UDP:51820 to be ready...")

        waitForWstunnelReady()

        // Step 7: WireGuard Connecting
        if (!stateMachine.transition(VpnState.WireGuardConnecting)) {
            throw IllegalStateException("Failed to transition to WireGuardConnecting")
        }
        emitLog("INFO", "WireGuard: Connecting to ${appConfig.wgConfig.peerEndpoint}...")

        try {
            // Build VPN config from WireGuard config
            val vpnConfig = buildVpnConfig(appConfig.wgConfig)

            // Use the internal WireGuardManager from VpnGatewayService
            val wgResult = vpnService?.startWithWireGuard(appConfig.wgConfig, vpnConfig)

            if (wgResult != null && wgResult.isFailure) {
                throw wgResult.exceptionOrNull()
                    ?: Exception("Unknown WireGuard error")
            }

            emitLog("INFO", "WireGuard: Tunnel established")
            lastErrorStage = null

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "WireGuard connection failed")
            lastErrorStage = "WireGuard"
            stateMachine.transition(VpnState.WireGuardError("WireGuard failed: ${e.message}"))
            throw e
        }

        // Step 8: WireGuard Connected
        if (!stateMachine.transition(VpnState.WireGuardConnected)) {
            throw IllegalStateException("Failed to transition to WireGuardConnected")
        }
        emitLog("INFO", "WireGuard: Connected, waiting for tunnel...")

        waitForWireGuardConnected()

        // Step 9: VPN Starting
        if (!stateMachine.transition(VpnState.VpnStarting)) {
            throw IllegalStateException("Failed to transition to VpnStarting")
        }
        emitLog("INFO", "VPN: Establishing TUN interface...")

        // Step 10: VPN Running
        if (!stateMachine.transition(VpnState.VpnRunning)) {
            throw IllegalStateException("Failed to transition to VpnRunning")
        }

        emitLog("INFO", "=== VPN CONNECTED ===")
        emitLog("INFO", "All systems operational")

        // Notify reconnect manager of success
        reconnectManager.onSuccess()
    }

    // ── SSTP helpers ───────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    private var sstpCurrentState: SstpState = SstpState.DISCONNECTED

    private fun setupSstpCallbacks() {
        sstpTunnel.onStateChanged = { sstpState ->
            scope.launch {
                sstpCurrentState = sstpState
                Timber.tag(TAG).d("SSTP state changed: $sstpState")
                when (sstpState) {
                    SstpState.CONNECTED -> emitLog("INFO", "SSTP: Tunnel ready")
                    SstpState.DISCONNECTED -> emitLog("INFO", "SSTP: Disconnected")
                    SstpState.ERROR -> emitLog("ERROR", "SSTP: Error")
                    else -> {}
                }
            }
        }

        sstpTunnel.onPppFrameReceived = { frame ->
            // PPP frames from SSTP are handled internally
            Timber.tag(TAG).d("SSTP: PPP frame received (${frame.size} bytes)")
        }
    }

    private suspend fun waitForSstpConnection() {
        val startTime = System.currentTimeMillis()
        val timeout = CONNECTION_TIMEOUT_MS

        while (sstpCurrentState != SstpState.CONNECTED && sstpCurrentState != SstpState.ERROR) {
            if (System.currentTimeMillis() - startTime > timeout) {
                throw Exception("SSTP connection timeout after ${timeout}ms")
            }
            delay(100)
        }

        if (sstpCurrentState == SstpState.ERROR) {
            throw Exception("SSTP entered error state")
        }
    }

    private suspend fun waitForPppIpAssignment() {
        val startTime = System.currentTimeMillis()
        val timeout = PPP_IP_TIMEOUT_MS

        while (sstpTunnel.localAddress == null) {
            if (System.currentTimeMillis() - startTime > timeout) {
                // PPP IP assignment might take time, log but continue
                Timber.tag(TAG).w("PPP IP assignment timeout, continuing anyway")
                break
            }
            delay(100)
        }

        sstpTunnel.localAddress?.let {
            emitLog("INFO", "SSTP: PPP IP assigned: $it")
        }
    }

    // ── Wstunnel helpers ───────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    private suspend fun waitForWstunnelReady() {
        val startTime = System.currentTimeMillis()
        val timeout = WSTUNNEL_TIMEOUT_MS

        while (wstunnelManager.state.value != WstunnelState.RUNNING) {
            if (wstunnelManager.state.value == WstunnelState.ERROR) {
                throw Exception("wstunnel entered error state")
            }
            if (System.currentTimeMillis() - startTime > timeout) {
                throw Exception("wstunnel startup timeout after ${timeout}ms")
            }
            delay(100)
        }
    }

    // ── WireGuard helpers ───────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    private suspend fun waitForWireGuardConnected() {
        val startTime = System.currentTimeMillis()
        val timeout = WIREGUARD_TIMEOUT_MS

        // Check internal WireGuard state via VpnService
        while (vpnService?.getWireGuardState() != WireGuardState.CONNECTED) {
            if (vpnService?.getWireGuardState() == WireGuardState.ERROR) {
                throw Exception("WireGuard entered error state")
            }
            if (System.currentTimeMillis() - startTime > timeout) {
                throw Exception("WireGuard connection timeout after ${timeout}ms")
            }
            delay(100)
        }
    }

    private fun buildVpnConfig(wgConfig: WireGuardConfig): VpnConfig {
        return VpnConfig(
            address = wgConfig.address.split("/").firstOrNull() ?: "10.0.0.1",
            prefixLength = wgConfig.address.split("/").getOrNull(1)?.toIntOrNull() ?: 24,
            mtu = wgConfig.mtu,
            dnsServers = wgConfig.dns
        )
    }

    // ── Error handling ─────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    private suspend fun handleConnectionError(error: Exception) {
        emitLog("ERROR", error.message ?: "Unknown error")

        // If reconnect is enabled, start reconnect loop
        if (stateMachine.isReconnectEnabled() && reconnectManager != null) {
            emitLog("INFO", "Reconnect enabled, starting reconnect loop...")

            reconnectJob = scope.launch {
                reconnectManager.start()
            }
        } else {
            // No reconnect, go to disconnected
            emitLog("INFO", "No reconnect enabled, shutting down...")
            stop()
        }
    }

    // ── Cleanup sequence ───────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    private suspend fun cleanupWireGuard() {
        try {
            emitLog("INFO", "WireGuard: Stopping tunnel...")
            vpnService?.shutdown()
            emitLog("INFO", "WireGuard: Stopped")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error stopping WireGuard")
            emitLog("ERROR", "WireGuard: Stop error - ${e.message}")
        }
    }

    private suspend fun cleanupWstunnel() {
        try {
            emitLog("INFO", "wstunnel: Stopping process...")
            wstunnelManager.stop()
            emitLog("INFO", "wstunnel: Stopped")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error stopping wstunnel")
            emitLog("ERROR", "wstunnel: Stop error - ${e.message}")
        }
    }

    private fun cleanupProxyAuth() {
        try {
            emitLog("INFO", "Proxy: Resetting session...")
            proxyAuthService.reset()
            emitLog("INFO", "Proxy: Reset complete")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error resetting proxy auth")
            emitLog("ERROR", "Proxy: Reset error - ${e.message}")
        }
    }

    private fun cleanupSstp() {
        try {
            emitLog("INFO", "SSTP: Disconnecting tunnel...")
            sstpTunnel.disconnect()
            emitLog("INFO", "SSTP: Disconnected")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error disconnecting SSTP")
            emitLog("ERROR", "SSTP: Disconnect error - ${e.message}")
        }
    }

    private fun cleanupVpnService() {
        try {
            emitLog("INFO", "VPN: Shutting down service...")
            vpnService?.shutdown()
            emitLog("INFO", "VPN: Shutdown complete")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error shutting down VPN service")
            emitLog("ERROR", "VPN: Shutdown error - ${e.message}")
        }
    }

    // ── State mapping ─────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    private fun mapToConnectionState(vpnState: VpnState): ConnectionState {
        return when (vpnState) {
            is VpnState.Disconnected -> ConnectionState.Disconnected
            is VpnState.VpnRunning -> ConnectionState.Connected
            is VpnState.SstpError,
            is VpnState.ProxyError,
            is VpnState.WstunnelError,
            is VpnState.WireGuardError -> ConnectionState.Error(
                when (vpnState) {
                    is VpnState.SstpError -> vpnState.message
                    is VpnState.ProxyError -> vpnState.message
                    is VpnState.WstunnelError -> vpnState.message
                    is VpnState.WireGuardError -> vpnState.message
                    else -> "Unknown error"
                }
            )
            // All intermediate states → Connecting
            is VpnState.SstpConnecting,
            is VpnState.SstpConnected,
            is VpnState.WstunnelStarting,
            is VpnState.WstunnelRunning,
            is VpnState.WireGuardConnecting,
            is VpnState.WireGuardConnected,
            is VpnState.VpnStarting -> ConnectionState.Connecting
            // Proxy auth states → Authenticating
            is VpnState.ProxyAuthenticating -> ConnectionState.Authenticating
            is VpnState.ProxyAuthenticated -> ConnectionState.Authenticating
        }
    }

    // ── Companion ──────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "VpnOrchestrator"
        private const val MAX_LOG_ENTRIES = 100

        // Timeouts
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        private const val PPP_IP_TIMEOUT_MS = 10_000L
        private const val WSTUNNEL_TIMEOUT_MS = 30_000L
        private const val WIREGUARD_TIMEOUT_MS = 30_000L
    }
}
