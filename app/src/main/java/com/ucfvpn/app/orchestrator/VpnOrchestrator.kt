package com.ucfvpn.app.orchestrator

import android.content.Context
import com.ucfvpn.app.proxy.ProxyAuthService
import com.ucfvpn.app.proxy.ProxyAuthState
import com.ucfvpn.app.sstp.client.SstpState
import com.ucfvpn.app.sstp.client.SstpTunnel
import com.ucfvpn.app.sstp.client.SstpTunnelImpl
import com.ucfvpn.app.sstp.ppp.PppEvent
import com.ucfvpn.app.state.ConnectionState
import com.ucfvpn.app.state.ReconnectManager
import com.ucfvpn.app.state.ReconnectState
import com.ucfvpn.app.state.VpnState
import com.ucfvpn.app.state.VpnStateMachine
import com.ucfvpn.app.prefs.ConfigPreferences
import com.ucfvpn.app.vpn.VpnGatewayService
import com.ucfvpn.app.wstunnel.TunnelType
import com.ucfvpn.app.wstunnel.WstunnelConfig
import com.ucfvpn.app.wstunnel.WstunnelManager
import com.ucfvpn.app.wstunnel.WstunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
 * SSTP → PPP → Proxy Auth → wstunnel SOCKS5 → VpnService (split tunnel).
 */
data class AppConfig(
    val sstpServer: String = "npv.ucf.edu.cu",
    val sstpPort: Int = 443,
    val sstpUsername: String,
    val sstpPassword: String,
    val proxyUsername: String,
    val proxyPassword: String,
    val wstunnelConfig: WstunnelConfig = WstunnelConfig(),
    val splitTunnelConfig: SplitTunnelConfig = SplitTunnelConfig(),

    /** When false, a failed connection is not retried automatically. */
    val autoReconnect: Boolean = true,

    /** Debug flag: kept for UiConfig compatibility. The Fase 5 sequence always uses SOCKS5. */
    val debugWstunnelSocks5: Boolean = false,

    /**
     * When true (legacy default) the SSTP TLS handshake accepts any certificate
     * (VERIFY_NONE). When false the system default trust manager is used,
     * enforcing real certificate validation.
     */
    val ignoreSslErrors: Boolean = true
)

/**
 * Split-tunnel routing configuration (Fase 4/5).
 *
 * @param privateNetworks CIDR networks routed through the SSTP tunnel
 * @param bypassApps Package names excluded from the VPN (reserved for future use)
 * @param defaultViaProxy When true, the default route goes through the SOCKS5 proxy
 */
data class SplitTunnelConfig(
    val privateNetworks: List<String> = listOf("10.0.0.0/8", "192.168.0.0/16", "172.16.0.0/12"),
    val bypassApps: List<String> = emptyList(),
    val defaultViaProxy: Boolean = true
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
 * 1. VpnState.SstpConnecting → SstpTunnel.connect() (retry ×MAX_SSTP_RETRIES)
 * 2. VpnState.SstpConnected → PPP phases (LCP → AUTH → IPCP) → wait for IP (retry ×MAX_PPP_RETRIES)
 * 3. VpnState.ProxyAuthenticating → ProxyAuthService.login() (retry ×MAX_PROXY_RETRIES)
 * 4. VpnState.WstunnelStarting(SOCKS5) → WstunnelManager.start() + waitForSocks5Ready (retry ×MAX_WSTUNNEL_RETRIES)
 * 5. VpnState.VpnStarting → VpnGatewayService.startWithSplitTunnelSocks5()
 * 6. VpnState.VpnRunning → all systems go!
 * ```
 *
 * ## Error Handling
 * - Each layer is wrapped in [retryLayer] with a bounded number of retries.
 * - On retry exhaustion: set the corresponding error state (SstpError, ProxyError, etc.)
 *   and rethrow so [handleConnectionError] delegates to the [ReconnectManager].
 * - The VPN layer has no retry loop: a failure maps to [VpnState.WireGuardError]
 *   (the only error state available for the final stage) and is delegated to
 *   the [ReconnectManager] for a full restart.
 *
 * ## Cleanup Sequence (reverse order)
 * ```
 * 1. VpnGatewayService.shutdown() (TUN + tun2socks)
 * 2. WstunnelManager.stop()
 * 3. ProxyAuthService.reset()
 * 4. SstpTunnel.disconnect()
 * 5. State = Disconnected
 * ```
 *
 * @param context Android context for wstunnel binary extraction
 * @param vpnService VpnGatewayService instance for TUN interface management
 * @param sstpTunnel SSTP tunnel implementation
 * @param proxyAuthService Proxy authentication service
 * @param wstunnelManager wstunnel process manager
 * @param stateMachine VPN state machine for state transitions
 * @param reconnectManager Optional reconnection manager with exponential backoff
 */
class VpnOrchestrator(
    private val context: Context,
    private val sstpTunnel: SstpTunnel,
    private val proxyAuthService: ProxyAuthService,
    private val wstunnelManager: WstunnelManager,
    private val stateMachine: VpnStateMachine = VpnStateMachine(),
    reconnectManager: ReconnectManager? = null,
    var vpnService: VpnGatewayService? = null
) {
    // ── Public API ────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    // NOTE: `scope` is declared before every property whose initialiser uses it.
    // Kotlin runs property initialisers in declaration order, so referencing a
    // property declared further down (even from inside an inlined `also { }`)
    // reads it while it is still null and crashes the constructor.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Current VPN state from the state machine */
    val state: StateFlow<VpnState> = stateMachine.state

    /** Connection state mapped for UI consumption */
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * Wall-clock time the tunnel came up, or null when it is not running.
     *
     * The UI needs an anchor to show real uptime: without it the counter could
     * only start from zero whenever its composable entered composition.
     */
    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    /** Circular log buffer of connection events for UI display */
    private val _connectionLog = MutableStateFlow<List<String>>(emptyList())
    val connectionLog: StateFlow<List<String>> = _connectionLog.asStateFlow()

    /** Flow of individual log entries for real-time UI updates */
    private val _logFlow = MutableSharedFlow<LogEntry>(replay = 0, extraBufferCapacity = 64)
    val logFlow: SharedFlow<LogEntry> = _logFlow.asSharedFlow()

    // ── Private fields ────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

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
        // Observe state machine: map to the UI-facing ConnectionState and emit logs
        scope.launch {
            stateMachine.state.collect { vpnState ->
                _connectionState.value = mapToConnectionState(vpnState)
                _connectedSince.value = when {
                    vpnState !is VpnState.VpnRunning -> null
                    // Keep the original instant across repeated emissions.
                    _connectedSince.value != null -> _connectedSince.value
                    else -> System.currentTimeMillis()
                }
                emitLog("INFO", "State: ${vpnState.displayName}")
            }
        }

        // Observe reconnect state
        scope.launch {
            internalReconnectManager.reconnectState.collect { reconnectState ->
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

        // Honour the user's auto-reconnect preference instead of forcing it on.
        // With it forced, a wrong password retried forever — a real risk of
        // locking the institutional account.
        stateMachine.setReconnectEnabled(appConfig.autoReconnect)

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
            start(buildAppConfig(uiConfig))
        }
    }

    /**
     * Stop the full VPN stack cleanly.
     *
     * This follows the cleanup sequence (reverse of the start order):
     * 1. VpnGatewayService.shutdown()
     * 2. WstunnelManager.stop()
     * 3. ProxyAuthService.reset()
     * 4. SstpTunnel.disconnect()
     * 5. State = Disconnected
     */
    suspend fun stop() = mutex.withLock {
        if (!isRunning) {
            Timber.tag(TAG).d("VpnOrchestrator already stopped")
            return
        }

        Timber.tag(TAG).d("Stopping VPN stack...")
        emitLog("INFO", "Initiating clean shutdown...")

        // Stop the reconnect loop before cancelling jobs, so it cannot restart
        // the sequence underneath us.
        reconnectManager.stop()

        // Cancel connection/reconnect jobs. NOTE: stop() is often invoked from
        // INSIDE connectionJob (via handleConnectionError), so cancelling it
        // here cancels the very coroutine running this function. Everything
        // below must therefore be uncancellable, or the cleanup would abort at
        // its first suspension point and leak the wstunnel process and the TUN.
        connectionJob?.cancel()
        reconnectJob?.cancel()

        withContext(NonCancellable) {
            try {
                // Cleanup sequence (in reverse order)
                cleanupVpnService()
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
     * Get the current wstunnel state.
     */
    fun getWstunnelState(): WstunnelState = wstunnelManager.state.value

    /**
     * Save configuration to repository.
     *
     * Converts the [com.ucfvpn.app.ui.viewmodel.UiConfig] into an [AppConfig]
     * via [buildAppConfig]. WireGuard configuration is no longer persisted
     * (Fase 5 removed WireGuard from the stack).
     */
    fun saveConfig(uiConfig: com.ucfvpn.app.ui.viewmodel.UiConfig) {
        val appConfig = buildAppConfig(uiConfig)
        ConfigPreferences(context).save(uiConfig)
        Timber.tag(TAG).d(
            "Configuration saved: sstp=${appConfig.sstpServer}:${appConfig.sstpPort}, " +
                "wstunnel=${appConfig.wstunnelConfig.serverUrl}, " +
                "splitTunnel=${appConfig.splitTunnelConfig.privateNetworks}"
        )
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
            // Cancel only AFTER the cleanup has actually finished. The previous
            // version cancelled the scope on a fixed 100 ms timer, which cut the
            // shutdown short and left the wstunnel process and TUN behind.
            scope.cancel()
        }
    }

    /**
     * Clear all log entries.
     */
    fun clearLogs() {
        _connectionLog.value = emptyList()
    }

    // ── Config conversion ─────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    /**
     * Convert a [com.ucfvpn.app.ui.viewmodel.UiConfig] into an [AppConfig].
     *
     * The wstunnel config is built from the UiConfig fields; the SOCKS5
     * variant used by the connection sequence is derived at runtime by
     * [buildSocks5Config].
     */
    private fun buildAppConfig(uiConfig: com.ucfvpn.app.ui.viewmodel.UiConfig): AppConfig {
        return AppConfig(
            sstpServer = uiConfig.sstpHost,
            sstpPort = uiConfig.sstpPort,
            sstpUsername = uiConfig.sstpUsername,
            sstpPassword = uiConfig.sstpPassword,
            proxyUsername = uiConfig.proxyUsername,
            proxyPassword = uiConfig.proxyPassword,
            wstunnelConfig = uiConfig.toWstunnelConfig(),
            splitTunnelConfig = SplitTunnelConfig(
                privateNetworks = parsePrivateNetworks(uiConfig.privateNetworks),
                bypassApps = parseCsvList(uiConfig.bypassApps),
                defaultViaProxy = uiConfig.defaultViaProxy
            ),
            autoReconnect = uiConfig.autoReconnect,
            debugWstunnelSocks5 = uiConfig.debugWstunnelSocks5,
            ignoreSslErrors = uiConfig.ignoreSslErrors
        )
    }

    /**
     * Build the SOCKS5 wstunnel config used by the connection sequence.
     *
     * The Fase 5 sequence always tunnels through a local SOCKS5 proxy
     * (hev-socks5-tunnel → wstunnel SOCKS5), so the config is derived from
     * the [AppConfig.wstunnelConfig] transport fields.
     */
    private fun buildSocks5Config(appConfig: AppConfig): WstunnelConfig {
        // copy() rather than a fresh WstunnelConfig: building one from scratch
        // silently dropped retryMaxBackoff and websocketPingFrequency, so those
        // fields in the settings screen had no effect on the real connection.
        // Only the SOCKS5-specific bits are overridden here.
        return appConfig.wstunnelConfig.copy(
            tunnelType = TunnelType.SOCKS5,
            localPort = WSTUNNEL_SOCKS5_PORT
        )
    }

    // ── Connection sequence ────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    private suspend fun performConnectionSequence(appConfig: AppConfig) {
        emitLog("INFO", "Starting VPN connection sequence...")

        // ── Layer 1: SSTP ──
        if (!stateMachine.transition(VpnState.SstpConnecting)) {
            throw IllegalStateException("Failed to transition to SstpConnecting")
        }
        emitLog("INFO", "SSTP: Connecting to ${appConfig.sstpServer}:${appConfig.sstpPort}...")

        retryLayer("SSTP", MAX_SSTP_RETRIES, { VpnState.SstpError(it) }) {
            // Setup SSTP callbacks
            setupSstpCallbacks()

            // Configure the concrete tunnel: TLS certificate validation flag and
            // the socket protector. The protector delegates to
            // VpnGatewayService.protectSocket() and is invoked inside
            // SstpHandshake.tcpConnect() BEFORE the socket connects (prevents the
            // VPN traffic loop). A safe cast is used because the tunnel is injected
            // as SstpTunnel; non-SstpTunnelImpl implementations are left untouched.
            (sstpTunnel as? SstpTunnelImpl)?.configure(
                ignoreSslErrors = appConfig.ignoreSslErrors,
                socketProtector = { socket -> vpnService?.protectSocket(socket) == true }
            )

            // CRITICAL: Protect the socket BEFORE connecting (prevents traffic loop).
            // The protector runs inside SstpHandshake.tcpConnect() before socket.connect().
            sstpTunnel.connect(appConfig.sstpServer, appConfig.sstpPort)

            // Wait for SSTP connection
            waitForSstpConnection()

            emitLog("INFO", "SSTP: Connected successfully")
        }

        if (!stateMachine.transition(VpnState.SstpConnected)) {
            throw IllegalStateException("Failed to transition to SstpConnected")
        }

        // ── Layer 2: PPP ──
        emitLog("INFO", "SSTP: Waiting for PPP negotiation...")

        retryLayer("PPP", MAX_PPP_RETRIES, { VpnState.SstpError(it) }) {
            // The negotiation itself runs inside SstpTunnelImpl. The phase
            // transitions below are driven by the real PppEvent callbacks
            // installed in setupSstpCallbacks(), so what the UI shows matches
            // where the negotiation actually is. Emitting all three phases up
            // front made the progress display fiction.
            stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
            emitLog("INFO", "PPP: LCP negotiation...")

            val localIp = waitForPppIpAssignment()
            stateMachine.transition(VpnState.PppAuthenticated(localIp))
            emitLog("INFO", "PPP: Authenticated, IP assigned: $localIp")
        }

        // ── Layer 3: Proxy Authentication ──
        if (!stateMachine.transition(VpnState.ProxyAuthenticating)) {
            throw IllegalStateException("Failed to transition to ProxyAuthenticating")
        }
        emitLog("INFO", "Proxy: Authenticating to captive portal...")

        retryLayer("Proxy", MAX_PROXY_RETRIES, { VpnState.ProxyError(it) }) {
            val proxyResult = proxyAuthService.login(
                appConfig.proxyUsername,
                appConfig.proxyPassword
            )

            if (proxyResult.isFailure) {
                throw proxyResult.exceptionOrNull()
                    ?: Exception("Unknown proxy auth error")
            }

            emitLog("INFO", "Proxy: Authentication successful")
        }

        if (!stateMachine.transition(VpnState.ProxyAuthenticated)) {
            throw IllegalStateException("Failed to transition to ProxyAuthenticated")
        }
        emitLog("INFO", "Proxy: Session verified")

        // ── Layer 4: wstunnel SOCKS5 ──
        val socks5Config = buildSocks5Config(appConfig)
        if (!stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))) {
            throw IllegalStateException("Failed to transition to WstunnelStarting")
        }
        emitLog("INFO", "wstunnel: Starting SOCKS5 tunnel to ${socks5Config.serverUrl}...")

        retryLayer("wstunnel", MAX_WSTUNNEL_RETRIES, { VpnState.WstunnelError(it) }) {
            val wstunnelResult = wstunnelManager.start(socks5Config)

            if (wstunnelResult.isFailure) {
                throw wstunnelResult.exceptionOrNull()
                    ?: Exception("Unknown wstunnel error")
            }

            emitLog("INFO", "wstunnel: Process started")

            // Wait for the local SOCKS5 listener to accept a real handshake
            if (!wstunnelManager.waitForSocks5Ready(
                    port = WSTUNNEL_SOCKS5_PORT,
                    timeoutMs = WSTUNNEL_SOCKS5_READY_TIMEOUT_MS
                )
            ) {
                throw Exception("wstunnel SOCKS5 not ready on 127.0.0.1:$WSTUNNEL_SOCKS5_PORT")
            }
            emitLog("INFO", "wstunnel: SOCKS5 :$WSTUNNEL_SOCKS5_PORT ready")
        }

        if (!stateMachine.transition(VpnState.WstunnelRunning(WSTUNNEL_SOCKS5_PORT))) {
            throw IllegalStateException("Failed to transition to WstunnelRunning")
        }

        // ── Layer 5: VPN Split Tunnel ──
        if (!stateMachine.transition(VpnState.VpnStarting)) {
            throw IllegalStateException("Failed to transition to VpnStarting")
        }
        emitLog("INFO", "VPN: Establishing split TUN interface...")

        try {
            val vpnService = vpnService
                ?: throw IllegalStateException("VpnGatewayService not available")

            val vpnResult = vpnService.startWithSplitTunnelSocks5(
                privateNetworks = appConfig.splitTunnelConfig.privateNetworks,
                socks5Proxy = "127.0.0.1:$WSTUNNEL_SOCKS5_PORT",
                bypassApps = appConfig.splitTunnelConfig.bypassApps
            )

            if (vpnResult.isFailure) {
                throw vpnResult.exceptionOrNull()
                    ?: Exception("Unknown VPN error")
            }

            emitLog("INFO", "VPN: Split tunnel established")
            lastErrorStage = null

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "VPN start failed")
            lastErrorStage = "VPN"
            stateMachine.transition(VpnState.WireGuardError("VPN failed: ${e.message}"))
            throw e
        }

        // ── Connected ──
        if (!stateMachine.transition(VpnState.VpnRunning)) {
            throw IllegalStateException("Failed to transition to VpnRunning")
        }

        emitLog("INFO", "=== VPN CONNECTED ===")
        emitLog("INFO", "All systems operational")

        // Notify reconnect manager of success
        reconnectManager.onSuccess()
    }

    // ── Layer retry helper ────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    /**
     * Execute [block] with a bounded number of retries for a single stack layer.
     *
     * On failure the layer is retried up to [maxRetries] times with
     * [LAYER_RETRY_DELAY_MS] between attempts. When retries are exhausted the
     * [errorState] transition is applied and the exception is rethrown so the
     * caller can delegate to the [ReconnectManager] for a full restart.
     */
    private suspend fun retryLayer(
        layer: String,
        maxRetries: Int,
        errorState: (String) -> VpnState,
        block: suspend () -> Unit
    ) {
        var attempt = 0
        while (true) {
            try {
                block()
                lastErrorStage = null
                return
            } catch (e: Exception) {
                attempt++
                lastErrorStage = layer
                if (attempt > maxRetries) {
                    val message = "$layer failed after ${attempt} attempts: ${e.message}"
                    Timber.tag(TAG).e(e, "$layer failed after ${attempt} attempts")
                    stateMachine.transition(errorState(message))
                    throw e
                }
                emitLog("WARN", "$layer attempt $attempt failed (${e.message}), retrying in ${LAYER_RETRY_DELAY_MS}ms...")
                delay(LAYER_RETRY_DELAY_MS)
            }
        }
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

        // Real PPP milestones drive the phase display.
        sstpTunnel.onPppEvent = { event ->
            scope.launch {
                when (event) {
                    is PppEvent.LcpOpened -> {
                        emitLog("INFO", "PPP: LCP opened, authenticating (PAP)...")
                        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
                    }
                    is PppEvent.AuthSuccess -> {
                        emitLog("INFO", "PPP: Authenticated, negotiating IPCP...")
                        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
                    }
                    is PppEvent.IpAssigned -> {
                        emitLog(
                            "INFO",
                            "PPP: IP ${event.localIp} (gw ${event.gateway}, dns ${event.dns1} ${event.dns2})"
                        )
                    }
                }
            }
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

    /**
     * Wait for the PPP IP assignment on the SSTP tunnel.
     *
     * @return the assigned local IP address
     * @throws Exception on timeout or when the SSTP tunnel enters the error state
     */
    private suspend fun waitForPppIpAssignment(): String {
        val startTime = System.currentTimeMillis()
        val timeout = PPP_IP_TIMEOUT_MS

        while (sstpTunnel.localAddress == null) {
            if (sstpCurrentState == SstpState.ERROR) {
                throw Exception("SSTP entered error state during PPP negotiation")
            }
            if (System.currentTimeMillis() - startTime > timeout) {
                throw Exception("PPP IP assignment timeout after ${timeout}ms")
            }
            delay(100)
        }

        val localIp = sstpTunnel.localAddress
            ?: throw Exception("PPP IP assignment failed")
        emitLog("INFO", "SSTP: PPP IP assigned: $localIp")
        return localIp
    }

    // ── Error handling ─────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    private suspend fun handleConnectionError(error: Exception) {
        emitLog("ERROR", error.message ?: "Unknown error")

        // Credentials are not going to fix themselves by retrying, and hammering
        // the server with a wrong password risks locking the account.
        if (isAuthenticationFailure(error)) {
            emitLog("ERROR", "Authentication rejected — not retrying. Check your credentials.")
            stop()
            return
        }

        if (stateMachine.isReconnectEnabled()) {
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

    /**
     * True when [error] means the server rejected our credentials, as opposed to
     * a transient network fault. Retrying the former is useless and harmful.
     */
    private fun isAuthenticationFailure(error: Exception): Boolean {
        val message = error.message ?: return false
        return message.contains("PAP authentication failed", ignoreCase = true) ||
            message.contains("MS-CHAPv2", ignoreCase = true) ||
            lastErrorStage == "Proxy"
    }

    // ── Cleanup sequence ───────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

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

    private suspend fun cleanupProxyAuth() {
        try {
            emitLog("INFO", "Proxy: Resetting session...")
            proxyAuthService.reset()
            emitLog("INFO", "Proxy: Reset complete")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error resetting proxy auth")
            emitLog("ERROR", "Proxy: Reset error - ${e.message}")
        }
    }

    private suspend fun cleanupSstp() {
        try {
            emitLog("INFO", "SSTP: Disconnecting tunnel...")
            sstpTunnel.disconnect()
            emitLog("INFO", "SSTP: Disconnected")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error disconnecting SSTP")
            emitLog("ERROR", "SSTP: Disconnect error - ${e.message}")
        }
    }

    private suspend fun cleanupVpnService() {
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
            is VpnState.PppNegotiating,
            is VpnState.PppAuthenticated,
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

        // Layer retry policy (Fase 5)
        private const val MAX_SSTP_RETRIES = 2
        private const val MAX_PPP_RETRIES = 2
        private const val MAX_PROXY_RETRIES = 2
        private const val MAX_WSTUNNEL_RETRIES = 2
        private const val LAYER_RETRY_DELAY_MS = 2_000L

        // wstunnel SOCKS5 (Fase 5)
        private const val WSTUNNEL_SOCKS5_PORT = 1080
        private const val WSTUNNEL_SOCKS5_READY_TIMEOUT_MS = 10_000L
    }
}

/**
 * Parse a comma-separated list of values, trimming whitespace and
 * dropping empty entries. Pure JVM helper (no Android APIs) so it can be
 * unit-tested on the JVM (pattern: [com.ucfvpn.app.vpn.parseCidr]).
 */
internal fun parseCsvList(input: String): List<String> =
    input.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/**
 * Parse the private-networks CSV, falling back to the default CIDR list
 * when the input contains no usable entries.
 */
internal fun parsePrivateNetworks(input: String): List<String> {
    val parsed = parseCsvList(input)
    return if (parsed.isEmpty()) SplitTunnelConfig().privateNetworks else parsed
}