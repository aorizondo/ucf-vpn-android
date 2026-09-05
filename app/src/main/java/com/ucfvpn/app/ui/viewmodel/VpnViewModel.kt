package com.ucfvpn.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ucfvpn.app.orchestrator.VpnOrchestrator
import com.ucfvpn.app.state.ConnectionState
import com.ucfvpn.app.state.VpnState
import com.ucfvpn.app.prefs.ConfigPreferences

import com.ucfvpn.app.wstunnel.WstunnelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiConfig(
    // SSTP
    val sstpHost: String = "npv.ucf.edu.cu",
    val sstpPort: Int = 443,
    val sstpUsername: String = "",
    val sstpPassword: String = "",
    // Proxy
    val proxyType: ProxyType = ProxyType.HTTP,
    val proxyHost: String = "10.14.0.13",
    val proxyPort: Int = 3128,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    // wstunnel
    val wstunnelUrl: String = "wss://solverius-ws.zpwhqo.easypanel.host",
    val wstunnelMode: WstunnelMode = WstunnelMode.FIXED,
    val wstunnelLocalPort: Int = 51820,
    val wstunnelRemoteHost: String = "72.62.160.61",
    val wstunnelRemotePort: Int = 51820,
    val wstunnelWsPingFrequency: String = "10s",
    val wstunnelRetryMaxBackoff: String = "10s",
    // Split Tunnel
    val privateNetworks: String = "10.0.0.0/8,192.168.0.0/16,172.16.0.0/12",
    val bypassApps: String = "",
    val defaultViaProxy: Boolean = true,
    // WireGuard
    val wireGuardEndpoint: String = "127.0.0.1:51820",
    val wireGuardLocalIp: String = "10.8.0.2/24",
    val wireGuardDns: String = "1.1.1.1",
    // General
    val ignoreSslErrors: Boolean = true,
    val autoReconnect: Boolean = true,
    // Debug
    val debugWstunnelSocks5: Boolean = false
) {
    /**
     * Convert UI config to the wstunnel domain model.
     * [WstunnelMode.FIXED] uses hardcoded defaults from the original
     * pre-up.sh command. [WstunnelMode.DYNAMIC] forwards all
     * user-customised parameters.
     *
     * Proxy credentials are forwarded as [WstunnelConfig.proxyAuth]
     * (`user:pass`). Note: wstunnel v10.5.1 `-p` only supports HTTP proxy
     * (`http://user:pass@host:port`); [ProxyType.SOCKS5] is persisted and
     * displayed in the UI but the real connection always uses the HTTP
     * proxy format (binary limitation, documented in Fase 3 of the notepad).
     */
    fun toWstunnelConfig(): WstunnelConfig {
        val mode = if (wstunnelMode == WstunnelMode.DYNAMIC)
            WstunnelConfig.Mode.DYNAMIC else WstunnelConfig.Mode.FIXED

        return WstunnelConfig(
            mode = mode,
            localPort = wstunnelLocalPort,
            remoteHost = wstunnelRemoteHost,
            remotePort = wstunnelRemotePort,
            serverUrl = wstunnelUrl,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyAuth = if (proxyUsername.isNotBlank()) "$proxyUsername:$proxyPassword" else null,
            retryMaxBackoff = wstunnelRetryMaxBackoff,
            websocketPingFrequency = wstunnelWsPingFrequency
        )
    }
}

enum class WstunnelMode(val displayName: String) {
    FIXED("Fixed"),
    DYNAMIC("Dynamic")
}

/** Proxy protocol type for the outbound HTTP proxy (Fase 6). */
enum class ProxyType(val displayName: String) {
    HTTP("HTTP"),
    SOCKS5("SOCKS5")
}
class VpnViewModel(
    application: Application,
    private val orchestrator: VpnOrchestrator
) : AndroidViewModel(application) {

    /** Simplified connection state for UI (Connected, Disconnected, Error, etc.). */
    val connectionState: StateFlow<ConnectionState> = orchestrator.connectionState

    /** Full VPN state-machine state for detailed stack visualization. */
    val vpnState: StateFlow<VpnState> = orchestrator.state

    /** Epoch millis when the tunnel came up, or null while it is down. */
    val connectedSince: StateFlow<Long?> = orchestrator.connectedSince

    /** Connection log as a newline-separated string for LogScreen. */
    val connectionLog: StateFlow<String> = MutableStateFlow("").also { flow ->
        viewModelScope.launch {
            orchestrator.connectionLog.collect { entries ->
                flow.value = entries.joinToString("\n")
            }
        }
    }

    /** Editable UI configuration form state — loaded from persisted preferences. */
    private val _uiConfig = MutableStateFlow(ConfigPreferences(application).load())
    val uiConfig: StateFlow<UiConfig> = _uiConfig.asStateFlow()

    /**
     * Invoked when the UI asks to connect. Set by the Activity, which owns the
     * VPN consent dialog and the service binding; it calls [connectNow] once the
     * [com.ucfvpn.app.vpn.VpnGatewayService] is available.
     */
    var onConnectRequested: (() -> Unit)? = null

    /**
     * Initiate VPN connection with the current configuration.
     *
     * Delegates to the Activity first: without VPN consent and a bound service
     * the orchestrator cannot establish a TUN interface.
     */
    fun connect() {
        val requester = onConnectRequested
        if (requester != null) {
            requester()
        } else {
            connectNow()
        }
    }

    /**
     * Start the connection sequence. Only call once the VPN service is bound —
     * [connect] is the entry point for the UI.
     * Persists config to DataStore + Keystore before connecting.
     */
    fun connectNow() {
        val config = _uiConfig.value
        orchestrator.saveConfig(config)
        orchestrator.connect(config)
    }

    /** Surface an error raised outside the orchestrator (e.g. consent denied). */
    fun reportError(message: String) {
        viewModelScope.launch { orchestrator.notifyError(message) }
    }

    /** Disconnect the VPN and stop all services. */
    fun disconnect() {
        orchestrator.disconnect()
    }

    /** Update the UI configuration form state and persist it. */
    fun updateConfig(config: UiConfig) {
        _uiConfig.value = config
        orchestrator.saveConfig(config)
    }

    /** Clear all log entries. */
    fun clearLogs() {
        orchestrator.clearLogs()
    }


    override fun onCleared() {
        super.onCleared()
        orchestrator.shutdown()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                throw IllegalStateException(
                    "VpnViewModel requires a VpnOrchestrator. Use custom factory.")
            }
        }
    }
}
