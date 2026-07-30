package com.ucfvpn.app.wstunnel

/**
 * Immutable configuration for the wstunnel subprocess.
 *
 * Two modes are supported:
 * - [Mode.FIXED]: upstream target is hardcoded (udp://local:remote_host:remote_port)
 * - [Mode.DYNAMIC]: upstream target is determined by the wstunnel server at runtime
 */
data class WstunnelConfig(
    /** Tunnel forwarding mode. */
    val mode: Mode = Mode.FIXED,

    /** Local UDP port that wstunnel listens on (WireGuard connects here). */
    val localPort: Int = 51820,

    /** Remote WireGuard peer host (only used in FIXED mode). */
    val remoteHost: String = "72.62.160.61",

    /** Remote WireGuard peer port (only used in FIXED mode). */
    val remotePort: Int = 51820,

    /** WebSocket server URL (must start with ws:// or wss://). */
    val serverUrl: String = "wss://solverius-ws.zpwhqo.easypanel.host",

    /** HTTP proxy host for outbound connections. */
    val proxyHost: String = "10.14.0.13",

    /** HTTP proxy port for outbound connections. */
    val proxyPort: Int = 3128,

    /** Maximum backoff between reconnection attempts (e.g. "10s"). */
    val retryMaxBackoff: String = "10s",

    /** Websocket ping interval to keep the connection alive (e.g. "10s"). */
    val websocketPingFrequency: String = "10s"
) {
    /** Forwarding mode: FIXED (hardcoded target) or DYNAMIC (server-determined target). */
    enum class Mode { FIXED, DYNAMIC }

    /** Returns true when [serverUrl] starts with a valid WebSocket scheme. */
    val isServerUrlValid: Boolean
        get() = serverUrl.startsWith("ws://") || serverUrl.startsWith("wss://")

    /**
     * Build the command-line argument list that will be passed to
     * [ProcessBuilder] to launch the wstunnel subprocess.
     *
     * The first element is always the path to the extracted binary.
     */
    fun buildCommand(binaryPath: String): List<String> {
        val listenArg = when (mode) {
            Mode.FIXED ->
                "udp://${localPort}:${remoteHost}:${remotePort}?timeout_sec=0"
            Mode.DYNAMIC ->
                "udp://${localPort}?timeout_sec=0"
        }

        return listOf(
            binaryPath,
            "client",
            "-L", listenArg,
            "-p", "http://${proxyHost}:${proxyPort}",
            serverUrl,
            "--connection-retry-max-backoff", retryMaxBackoff,
            "--websocket-ping-frequency", websocketPingFrequency
        )
    }

    companion object {
        /**
         * Factory for a [Mode.DYNAMIC] configuration with all defaults except the mode.
         * The server determines the upstream target at runtime.
         */
        fun dynamic(): WstunnelConfig = WstunnelConfig(mode = Mode.DYNAMIC)
    }
}
