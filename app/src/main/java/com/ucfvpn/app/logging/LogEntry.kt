package com.ucfvpn.app.logging

import com.ucfvpn.app.state.VpnState

/**
 * Log severity levels.
 */
enum class LogLevel {
    DEBUG, INFO, WARN, ERROR, FATAL
}

/**
 * Component category for filtering logs.
 */
enum class LogCategory(val displayName: String) {
    SSTP("SSTP"),
    PROXY("Proxy"),
    WSTUNNEL("Wstunnel"),
    WIREGUARD("WireGuard"),
    VPN("VPN"),
    SYSTEM("System")
}

/**
 * Connection stages with their timeout thresholds.
 * Each stage maps to a specific VPN connection phase.
 */
enum class ConnectionStage(
    val timeoutMs: Long,
    val displayName: String
) {
    SSTP_CONNECTING(15_000L, "SSTP Connection"),
    PROXY_AUTH(10_000L, "Proxy Authentication"),
    WSTUNNEL_START(10_000L, "Wstunnel Startup"),
    WIREGUARD_CONNECTING(15_000L, "WireGuard Connection"),
    VPN_STARTING(10_000L, "VPN Initialization");

    /** Map this stage to the corresponding error state in the state machine. */
    fun toErrorState(message: String): VpnState = when (this) {
        SSTP_CONNECTING -> VpnState.SstpError(message)
        PROXY_AUTH -> VpnState.ProxyError(message)
        WSTUNNEL_START -> VpnState.WstunnelError(message)
        WIREGUARD_CONNECTING -> VpnState.WireGuardError(message)
        VPN_STARTING -> VpnState.WireGuardError(message)
    }

    /** Map this stage to the corresponding log category. */
    fun toLogCategory(): LogCategory = when (this) {
        SSTP_CONNECTING -> LogCategory.SSTP
        PROXY_AUTH -> LogCategory.PROXY
        WSTUNNEL_START -> LogCategory.WSTUNNEL
        WIREGUARD_CONNECTING -> LogCategory.WIREGUARD
        VPN_STARTING -> LogCategory.VPN
    }
}

/**
 * A single log entry stored in the circular buffer.
 */
data class LogEntry(
    val level: LogLevel,
    val category: LogCategory,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val error: VpnError? = null,
    val exception: Throwable? = null
) {
    val formattedTimestamp: String
        get() {
            val totalSeconds = timestamp / 1000
            val hours = (totalSeconds % 86400) / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            val millis = timestamp % 1000
            return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
        }

    val levelTag: String
        get() = "[${level.name.padEnd(5)}]"

    override fun toString(): String =
        "$formattedTimestamp $levelTag [${category.displayName}] $message"
}
