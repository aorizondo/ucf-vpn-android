package com.ucfvpn.app.wstunnel

/** Supported tunnel forwarding types for the wstunnel subprocess. */
enum class TunnelType { UDP, SOCKS5, HTTP, TCP }

/**
 * Immutable configuration for the wstunnel subprocess.
 *
 * Supports multiple tunnel types via [TunnelType]:
 * - [TunnelType.UDP]: upstream target is hardcoded (udp://local:remote_host:remote_port) — used for WireGuard
 * - [TunnelType.SOCKS5]: local SOCKS5 proxy (socks5://0.0.0.0:port)
 * - [TunnelType.HTTP]: local HTTP proxy (http://0.0.0.0:port)
 * - [TunnelType.TCP]: TCP forward (tcp://local:remote_host:remote_port)
 *
 * Within UDP and TCP, [Mode] controls whether the upstream target is fixed or dynamic.
 */
data class WstunnelConfig(
    /** Tunnel forwarding type. */
    val tunnelType: TunnelType = TunnelType.UDP,

    /** Tunnel forwarding mode (only relevant for UDP and TCP). */
    val mode: Mode = Mode.FIXED,

    /** Local port that wstunnel listens on. */
    val localPort: Int = 51820,

    /** Remote peer host (only used in FIXED mode for UDP/TCP). */
    val remoteHost: String = "72.62.160.61",

    /** Remote peer port (only used in FIXED mode for UDP/TCP). */
    val remotePort: Int = 51820,

    /** WebSocket server URL (must start with ws:// or wss://). */
    val serverUrl: String = "wss://solverius-ws.zpwhqo.easypanel.host",

    /** HTTP proxy host for outbound connections. */
    val proxyHost: String = "10.14.0.13",

    /** HTTP proxy port for outbound connections. */
    val proxyPort: Int = 3128,

    /**
     * Optional HTTP proxy credentials in `user:pass` format.
     *
     * wstunnel v10.5.1 supports proxy authentication directly in the `-p`
     * flag as `http://user:pass@host:port`. Verified in the v10.5.1 source
     * (repo: https://github.com/erebe/wstunnel):
     * - `wstunnel/src/config.rs` declares `-p, --http-proxy` with
     *   `value_name = "USER:PASS@HOST:PORT"`.
     * - `wstunnel/src/lib.rs` `mk_http_proxy()` parses the value as a URL
     *   when it starts with `http://` (and `Url::parse` accepts userinfo),
     *   otherwise it is wrapped as `http://<value>`.
     * - Dedicated `--http-proxy-login` / `--http-proxy-password` flags
     *   override the credentials embedded in the URL.
     *
     * When null (default) the proxy is used without credentials.
     */
    val proxyAuth: String? = null,

    /** Maximum backoff between reconnection attempts (e.g. "10s"). */
    val retryMaxBackoff: String = "10s",

    /** Websocket ping interval to keep the connection alive (e.g. "10s"). */
    val websocketPingFrequency: String = "10s",

    /** Log verbosity level (TRACE, DEBUG, INFO, WARN, ERROR, OFF). */
    val logLevel: String = "INFO"
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
        val listenArg = when (tunnelType) {
            TunnelType.UDP -> when (mode) {
                Mode.FIXED ->
                    "udp://${localPort}:${remoteHost}:${remotePort}?timeout_sec=0"
                Mode.DYNAMIC ->
                    "udp://${localPort}?timeout_sec=0"
            }
            TunnelType.SOCKS5 -> "socks5://0.0.0.0:${localPort}"
            TunnelType.HTTP -> "http://0.0.0.0:${localPort}"
            TunnelType.TCP -> when (mode) {
                Mode.FIXED -> "tcp://${localPort}:${remoteHost}:${remotePort}"
                Mode.DYNAMIC -> throw IllegalArgumentException("TCP requires FIXED mode")
            }
        }

        val proxyArg = if (proxyAuth.isNullOrEmpty()) {
            "http://${proxyHost}:${proxyPort}"
        } else {
            "http://${proxyAuth}@${proxyHost}:${proxyPort}"
        }

        return listOf(
            binaryPath,
            "client",
            "-L", listenArg,
            "-p", proxyArg,
            serverUrl,
            "--connection-retry-max-backoff", retryMaxBackoff,
            "--websocket-ping-frequency", websocketPingFrequency,
            "--log-lvl", logLevel
        )
    }

    companion object {
        /**
         * Factory for a [Mode.DYNAMIC] configuration with all defaults except the mode.
         * The server determines the upstream target at runtime.
         */
        fun dynamic(): WstunnelConfig = WstunnelConfig(mode = Mode.DYNAMIC)

        /**
         * Factory for a [TunnelType.SOCKS5] configuration.
         * Creates a local SOCKS5 proxy for manual browser/app configuration.
         */
        fun socks5(
            localPort: Int = 1080,
            serverUrl: String = "wss://solverius-ws.zpwhqo.easypanel.host",
            proxyHost: String = "10.14.0.13",
            proxyPort: Int = 3128,
            proxyAuth: String? = null,
            logLevel: String = "INFO"
        ): WstunnelConfig = WstunnelConfig(
            tunnelType = TunnelType.SOCKS5,
            localPort = localPort,
            serverUrl = serverUrl,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyAuth = proxyAuth,
            logLevel = logLevel
        )
    }
}
