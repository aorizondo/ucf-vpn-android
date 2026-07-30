package com.ucfvpn.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.ucfvpn.app.state.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiConfig(
    // SSTP
    val sstpHost: String = "npv.ucf.edu.cu",
    val sstpPort: Int = 443,
    val sstpUsername: String = "",
    val sstpPassword: String = "",
    // Proxy
    val proxyHost: String = "10.14.0.13",
    val proxyPort: Int = 3128,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    // wstunnel
    val wstunnelUrl: String = "wss://solverius-ws.zpwhqo.easypanel.host",
    val wstunnelMode: WstunnelMode = WstunnelMode.FIXED,
    // WireGuard
    val wireGuardEndpoint: String = "127.0.0.1:51820",
    val wireGuardLocalIp: String = "10.8.0.2/24",
    val wireGuardDns: String = "1.1.1.1",
    // General
    val ignoreSslErrors: Boolean = true,
    val autoReconnect: Boolean = true
)

enum class WstunnelMode(val displayName: String) {
    FIXED("Fixed"),
    DYNAMIC("Dynamic")
}

class VpnViewModel : ViewModel() {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val logBuffer = ArrayDeque<String>(100)
    private val _connectionLog = MutableStateFlow<String>("")
    val connectionLog: StateFlow<String> = _connectionLog.asStateFlow()

    private val _uiConfig = MutableStateFlow(UiConfig())
    val uiConfig: StateFlow<UiConfig> = _uiConfig.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun connect() {
        appendLog("INFO", "Connect requested")
        _connectionState.value = ConnectionState.Connecting
        appendLog("INFO", "State: Connecting → Authenticating")
        _connectionState.value = ConnectionState.Authenticating
        appendLog("INFO", "State: Authenticating → Connected")
        _connectionState.value = ConnectionState.Connected
    }

    fun disconnect() {
        appendLog("INFO", "Disconnect requested")
        _connectionState.value = ConnectionState.Disconnected
        appendLog("INFO", "State: Disconnected")
    }

    fun updateConfig(config: UiConfig) {
        _uiConfig.value = config
        appendLog("INFO", "Configuration updated")
    }

    fun clearLogs() {
        logBuffer.clear()
        _connectionLog.value = ""
    }

    fun appendLog(level: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val entry = "$timestamp [$level] $message"
        if (logBuffer.size >= 100) {
            logBuffer.removeFirst()
        }
        logBuffer.addLast(entry)
        _connectionLog.value = logBuffer.joinToString("\n")
    }
}
