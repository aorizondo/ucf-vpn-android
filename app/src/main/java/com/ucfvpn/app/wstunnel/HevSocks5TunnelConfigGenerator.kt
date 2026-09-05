package com.ucfvpn.app.wstunnel

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * Generates the dynamic YAML configuration for hev-socks5-tunnel.
 *
 * The static asset `assets/hev_socks5_tunnel.yaml` is replaced at runtime by
 * this generator (Fase 4) so the SOCKS5 endpoint (wstunnel) and the DNS
 * behaviour can be configured per connection instead of being hard-coded.
 *
 * The generated document uses the legacy hev-socks5-tunnel schema
 * (`listen` / `socks5` / `dns` / `log`) — the schema understood by the
 * embedded `hev_socks5_tunnel_arm64` binary:
 *
 * ```yaml
 * listen:
 *   host: 0.0.0.0
 *   port: 5080
 * socks5:
 *   host: 127.0.0.1
 *   port: 1080
 * dns:
 *   address: 0.0.0.0
 *   port: 6000
 *   upstream:
 *     - 1.1.1.1
 *     - 8.8.8.8
 *   tcp: true
 * log:
 *   level: info
 *   buffer: 1024
 * ```
 *
 * `dns.tcp: true` forces DNS queries over TCP through the SOCKS5 proxy, which
 * is what makes DNS follow the tunnel when [dnsViaSocks5] is enabled.
 */
object HevSocks5TunnelConfigGenerator {

    private const val TAG = "HevSocks5Config"
    private const val CONFIG_FILE_NAME = "hev_socks5_tunnel_dynamic.yaml"
    private const val DEFAULT_LISTEN_PORT = 5080
    private const val DEFAULT_DNS_PORT = 6000

    /** Default upstream DNS servers used when none are provided. */
    val DEFAULT_DNS_UPSTREAMS = listOf("1.1.1.1", "8.8.8.8")

    /**
     * Builds the YAML document as a string (pure function, JVM-testable).
     *
     * @param socks5Host SOCKS5 proxy host (wstunnel listener)
     * @param socks5Port SOCKS5 proxy port (wstunnel listener)
     * @param dnsUpstreams Upstream DNS servers to resolve through the tunnel
     * @param dnsViaSocks5 When true, DNS queries are sent over TCP through the
     *   SOCKS5 proxy (`dns.tcp: true`); when false they go direct (`tcp: false`)
     * @param listenPort Local port where hev-socks5-tunnel listens for TUN traffic
     * @return The YAML document
     * @throws IllegalArgumentException if any parameter is invalid
     */
    fun buildYaml(
        socks5Host: String = "127.0.0.1",
        socks5Port: Int = 1080,
        dnsUpstreams: List<String> = DEFAULT_DNS_UPSTREAMS,
        dnsViaSocks5: Boolean = true,
        listenPort: Int = DEFAULT_LISTEN_PORT
    ): String {
        require(socks5Port in 1..65535) { "socks5Port must be in 1..65535, got $socks5Port" }
        require(listenPort in 1..65535) { "listenPort must be in 1..65535, got $listenPort" }
        require(dnsUpstreams.isNotEmpty()) { "dnsUpstreams must not be empty" }

        return buildString {
            appendLine("listen:")
            appendLine("  host: 0.0.0.0")
            appendLine("  port: $listenPort")
            appendLine()
            appendLine("socks5:")
            appendLine("  host: $socks5Host")
            appendLine("  port: $socks5Port")
            appendLine()
            appendLine("dns:")
            appendLine("  address: 0.0.0.0")
            appendLine("  port: $DEFAULT_DNS_PORT")
            appendLine("  upstream:")
            dnsUpstreams.forEach { appendLine("    - $it") }
            appendLine("  # Importante: resolver DNS via SOCKS5")
            appendLine("  tcp: $dnsViaSocks5")
            appendLine()
            appendLine("log:")
            appendLine("  level: info")
            appendLine("  buffer: 1024")
        }
    }

    /**
     * Writes the dynamic YAML to `context.filesDir/hev_socks5_tunnel_dynamic.yaml`
     * and returns the absolute path of the written file.
     *
     * @param context Android context used to resolve [android.content.Context.filesDir]
     * @return [Result.success] with the config file path, or [Result.failure] with the cause
     */
    fun generate(
        context: Context,
        socks5Host: String = "127.0.0.1",
        socks5Port: Int = 1080,
        dnsUpstreams: List<String> = DEFAULT_DNS_UPSTREAMS,
        dnsViaSocks5: Boolean = true,
        listenPort: Int = DEFAULT_LISTEN_PORT
    ): Result<String> {
        return try {
            val yaml = buildYaml(socks5Host, socks5Port, dnsUpstreams, dnsViaSocks5, listenPort)
            val dest = File(context.filesDir, CONFIG_FILE_NAME)
            dest.writeText(yaml)
            Timber.tag(TAG).d("Dynamic config written to ${dest.absolutePath}")
            Result.success(dest.absolutePath)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to write dynamic hev-socks5-tunnel config")
            Result.failure(e)
        }
    }
}