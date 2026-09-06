package com.ucfvpn.app.wstunnel

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the wstunnel wrapper — configuration, command building,
 * and URL validation.
 *
 * These tests run on the JVM without Android framework dependencies.
 * WstunnelManager's process-management methods (start/stop) are not
 * exercised here because they require a real Android [android.content.Context]
 * and a wstunnel binary.  They are covered by instrumented tests instead.
 */
class WstunnelManagerTest {

    // ── Constants ─────────────────────────────────────────────────

    private val binaryPath = "/data/data/com.ucfvpn.app/files/wstunnel"

    private val defaultConfig = WstunnelConfig()

    // ── WstunnelConfig — default values ───────────────────────────

    @Test
    fun `default config is FIXED mode`() {
        assertEquals(WstunnelConfig.Mode.FIXED, defaultConfig.mode)
    }

    @Test
    fun `default config is UDP tunnel type`() {
        assertEquals(TunnelType.UDP, defaultConfig.tunnelType)
    }

    @Test
    fun `default config has correct local port`() {
        assertEquals(51820, defaultConfig.localPort)
    }

    @Test
    fun `default config server URL starts with wss`() {
        assertEquals("wss://solverius-ws.zpwhqo.easypanel.host", defaultConfig.serverUrl)
    }

    @Test
    fun `default config proxy is 10_14_0_13_3128`() {
        assertEquals("10.14.0.13", defaultConfig.proxyHost)
        assertEquals(3128, defaultConfig.proxyPort)
    }

    @Test
    fun `default config retry and ping are 10s`() {
        assertEquals("10s", defaultConfig.retryMaxBackoff)
        assertEquals("10s", defaultConfig.websocketPingFrequency)
    }

    @Test
    fun `default config log level is INFO`() {
        assertEquals("INFO", defaultConfig.logLevel)
    }

    @Test
    fun `dynamic factory creates DYNAMIC mode`() {
        val dyn = WstunnelConfig.dynamic()
        assertEquals(WstunnelConfig.Mode.DYNAMIC, dyn.mode)
    }

    @Test
    fun `dynamic factory preserves default tunnel type`() {
        val dyn = WstunnelConfig.dynamic()
        assertEquals(TunnelType.UDP, dyn.tunnelType)
    }

    // ── connection pool ───────────────────────────────────────────

    @Test
    fun `socks5 keeps idle connections ready by default`() {
        // wstunnel's own default is 0, i.e. no pool, and in SOCKS5 mode every
        // TCP connection is a separate tunnel: a page opening dozens at once
        // pays a full TCP + TLS + proxy CONNECT + WebSocket handshake for each,
        // which is what left pages partially loaded.
        val cmd = WstunnelConfig.socks5().buildCommand(binaryPath)

        val idx = cmd.indexOf("--connection-min-idle")
        assertTrue("SOCKS5 must request a connection pool", idx >= 0)
        assertEquals(WstunnelConfig.DEFAULT_SOCKS5_MIN_IDLE.toString(), cmd[idx + 1])
    }

    @Test
    fun `the pool flag is omitted when no pool is wanted`() {
        // UDP multiplexes everything into one flow, so a pool buys nothing and
        // the flag would only add noise.
        val cmd = defaultConfig.buildCommand(binaryPath)

        assertFalse(
            "UDP mode must not request a pool",
            cmd.contains("--connection-min-idle")
        )
    }

    @Test
    fun `an explicit pool size is honoured`() {
        val cmd = WstunnelConfig.socks5(connectionMinIdle = 3).buildCommand(binaryPath)

        val idx = cmd.indexOf("--connection-min-idle")
        assertTrue(idx >= 0)
        assertEquals("3", cmd[idx + 1])
    }

    // ── socks5 factory ────────────────────────────────────────────

    @Test
    fun `socks5 factory creates SOCKS5 tunnel type`() {
        val s5 = WstunnelConfig.socks5()
        assertEquals(TunnelType.SOCKS5, s5.tunnelType)
    }

    @Test
    fun `socks5 factory defaults to port 1080`() {
        val s5 = WstunnelConfig.socks5()
        assertEquals(1080, s5.localPort)
    }

    @Test
    fun `socks5 factory uses correct server URL`() {
        val s5 = WstunnelConfig.socks5()
        assertEquals("wss://solverius-ws.zpwhqo.easypanel.host", s5.serverUrl)
    }

    @Test
    fun `socks5 factory uses correct proxy settings`() {
        val s5 = WstunnelConfig.socks5()
        assertEquals("10.14.0.13", s5.proxyHost)
        assertEquals(3128, s5.proxyPort)
    }

    @Test
    fun `socks5 factory defaults to INFO log level`() {
        val s5 = WstunnelConfig.socks5()
        assertEquals("INFO", s5.logLevel)
    }

    @Test
    fun `socks5 factory accepts custom parameters`() {
        val s5 = WstunnelConfig.socks5(
            localPort = 9999,
            serverUrl = "wss://custom.example.com",
            proxyHost = "proxy.custom",
            proxyPort = 8080,
            logLevel = "DEBUG"
        )
        assertEquals(9999, s5.localPort)
        assertEquals("wss://custom.example.com", s5.serverUrl)
        assertEquals("proxy.custom", s5.proxyHost)
        assertEquals(8080, s5.proxyPort)
        assertEquals("DEBUG", s5.logLevel)
    }

    // ── buildCommand — FIXED mode ─────────────────────────────────

    @Test
    fun `buildCommand FIXED mode produces correct argument list`() {
        val cmd = defaultConfig.buildCommand(binaryPath)

        val expected = listOf(
            binaryPath,
            "client",
            "-L", "udp://51820:72.62.160.61:51820?timeout_sec=0",
            "-p", "http://10.14.0.13:3128",
            "wss://solverius-ws.zpwhqo.easypanel.host",
            "--connection-retry-max-backoff", "10s",
            "--websocket-ping-frequency", "10s",
            "--log-lvl", "INFO"
        )

        assertEquals(expected, cmd)
    }

    @Test
    fun `buildCommand FIXED mode respects custom remote host and port`() {
        val config = defaultConfig.copy(
            remoteHost = "192.168.1.100",
            remotePort = 12345,
            localPort = 9999
        )

        val cmd = config.buildCommand(binaryPath)

        assertEquals("udp://9999:192.168.1.100:12345?timeout_sec=0", cmd[3])
    }

    @Test
    fun `buildCommand FIXED mode respects custom proxy settings`() {
        val config = defaultConfig.copy(
            proxyHost = "proxy.example.com",
            proxyPort = 8080
        )

        val cmd = config.buildCommand(binaryPath)

        assertEquals("-p", cmd[4])
        assertEquals("http://proxy.example.com:8080", cmd[5])
    }

    @Test
    fun `buildCommand FIXED mode respects custom server URL`() {
        val config = defaultConfig.copy(serverUrl = "wss://my-tunnel.example.com:8443")

        val cmd = config.buildCommand(binaryPath)

        assertEquals("wss://my-tunnel.example.com:8443", cmd[6])
    }

    @Test
    fun `buildCommand FIXED mode respects custom retry and ping values`() {
        val config = defaultConfig.copy(
            retryMaxBackoff = "30s",
            websocketPingFrequency = "5s"
        )

        val cmd = config.buildCommand(binaryPath)

        assertEquals("--connection-retry-max-backoff", cmd[7])
        assertEquals("30s", cmd[8])
        assertEquals("--websocket-ping-frequency", cmd[9])
        assertEquals("5s", cmd[10])
    }

    @Test
    fun `buildCommand FIXED mode respects custom log level`() {
        val config = defaultConfig.copy(logLevel = "DEBUG")

        val cmd = config.buildCommand(binaryPath)

        assertEquals("--log-lvl", cmd[11])
        assertEquals("DEBUG", cmd[12])
    }

    @Test
    fun `buildCommand element count is 13 for FIXED mode`() {
        val cmd = defaultConfig.buildCommand(binaryPath)
        // binary, client, -L, listen, -p, proxy, url,
        // --connection-retry-max-backoff, value, --websocket-ping-frequency,
        // value, --log-lvl, value = 13. The previous expectation of 15
        // contradicted the neighbouring test, which indexes --log-lvl at cmd[11].
        assertEquals(13, cmd.size)
    }

    // ── buildCommand — DYNAMIC mode ───────────────────────────────

    @Test
    fun `buildCommand DYNAMIC mode omits remote host and port from listen arg`() {
        val config = WstunnelConfig(mode = WstunnelConfig.Mode.DYNAMIC)

        val cmd = config.buildCommand(binaryPath)

        // DYNAMIC: udp://51820?timeout_sec=0 (no remote:port)
        assertEquals("udp://51820?timeout_sec=0", cmd[3])
    }

    @Test
    fun `buildCommand DYNAMIC mode with custom local port`() {
        val config = WstunnelConfig(
            mode = WstunnelConfig.Mode.DYNAMIC,
            localPort = 7777
        )

        val cmd = config.buildCommand(binaryPath)

        assertEquals("udp://7777?timeout_sec=0", cmd[3])
    }

    @Test
    fun `buildCommand DYNAMIC mode still includes proxy and retry options`() {
        val config = WstunnelConfig.dynamic()

        val cmd = config.buildCommand(binaryPath)

        assertEquals("-p", cmd[4])
        assertEquals("http://10.14.0.13:3128", cmd[5])
        assertEquals("--connection-retry-max-backoff", cmd[7])
        assertEquals("--websocket-ping-frequency", cmd[9])
    }

    // ── buildCommand — SOCKS5 mode ───────────────────────────────

    @Test
    fun `buildCommand SOCKS5 mode uses socks5 listen arg`() {
        val s5 = WstunnelConfig.socks5()

        val cmd = s5.buildCommand(binaryPath)

        assertEquals("socks5://0.0.0.0:1080", cmd[3])
    }

    @Test
    fun `buildCommand SOCKS5 mode with custom port`() {
        val s5 = WstunnelConfig.socks5(localPort = 9999)

        val cmd = s5.buildCommand(binaryPath)

        assertEquals("socks5://0.0.0.0:9999", cmd[3])
    }

    // ── buildCommand — HTTP mode ──────────────────────────────────

    @Test
    fun `buildCommand HTTP mode uses http listen arg`() {
        val http = WstunnelConfig(tunnelType = TunnelType.HTTP)

        val cmd = http.buildCommand(binaryPath)

        assertEquals("http://0.0.0.0:51820", cmd[3])
    }

    // ── buildCommand — TCP mode ───────────────────────────────────

    @Test
    fun `buildCommand TCP FIXED mode uses tcp listen arg`() {
        val tcp = WstunnelConfig(tunnelType = TunnelType.TCP)

        val cmd = tcp.buildCommand(binaryPath)

        assertEquals("tcp://51820:72.62.160.61:51820", cmd[3])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildCommand TCP DYNAMIC mode throws IllegalArgumentException`() {
        val tcp = WstunnelConfig(
            tunnelType = TunnelType.TCP,
            mode = WstunnelConfig.Mode.DYNAMIC
        )
        tcp.buildCommand(binaryPath)
    }

    // ── URL validation ────────────────────────────────────────────

    @Test
    fun `isServerUrlValid returns true for wss URL`() {
        assertTrue(defaultConfig.isServerUrlValid)
    }

    @Test
    fun `isServerUrlValid returns true for ws URL`() {
        val config = defaultConfig.copy(serverUrl = "ws://localhost:8080")
        assertTrue(config.isServerUrlValid)
    }

    @Test
    fun `isServerUrlValid returns false for http URL`() {
        val config = defaultConfig.copy(serverUrl = "http://example.com")
        assertFalse(config.isServerUrlValid)
    }

    @Test
    fun `isServerUrlValid returns false for empty string`() {
        val config = defaultConfig.copy(serverUrl = "")
        assertFalse(config.isServerUrlValid)
    }

    @Test
    fun `isServerUrlValid returns false for random string`() {
        val config = defaultConfig.copy(serverUrl = "not-a-url")
        assertFalse(config.isServerUrlValid)
    }

    // ── WstunnelState enum ────────────────────────────────────────

    @Test
    fun `WstunnelState has all five values`() {
        val values = WstunnelState.entries
        assertEquals(5, values.size)
        assertTrue(values.contains(WstunnelState.STOPPED))
        assertTrue(values.contains(WstunnelState.STARTING))
        assertTrue(values.contains(WstunnelState.RUNNING))
        assertTrue(values.contains(WstunnelState.STOPPING))
        assertTrue(values.contains(WstunnelState.ERROR))
    }

    @Test
    fun `WstunnelState valueOf works for all values`() {
        assertEquals(WstunnelState.STOPPED, WstunnelState.valueOf("STOPPED"))
        assertEquals(WstunnelState.STARTING, WstunnelState.valueOf("STARTING"))
        assertEquals(WstunnelState.RUNNING, WstunnelState.valueOf("RUNNING"))
        assertEquals(WstunnelState.STOPPING, WstunnelState.valueOf("STOPPING"))
        assertEquals(WstunnelState.ERROR, WstunnelState.valueOf("ERROR"))
    }

    // ── TunnelType enum ──────────────────────────────────────────

    @Test
    fun `TunnelType has all four values`() {
        val values = TunnelType.entries
        assertEquals(4, values.size)
        assertEquals(TunnelType.UDP, TunnelType.valueOf("UDP"))
        assertEquals(TunnelType.SOCKS5, TunnelType.valueOf("SOCKS5"))
        assertEquals(TunnelType.HTTP, TunnelType.valueOf("HTTP"))
        assertEquals(TunnelType.TCP, TunnelType.valueOf("TCP"))
    }

    // ── WstunnelConfig Mode enum ─────────────────────────────────

    @Test
    fun `Mode has FIXED and DYNAMIC`() {
        val modes = WstunnelConfig.Mode.entries
        assertEquals(2, modes.size)
        assertEquals(WstunnelConfig.Mode.FIXED, WstunnelConfig.Mode.valueOf("FIXED"))
        assertEquals(WstunnelConfig.Mode.DYNAMIC, WstunnelConfig.Mode.valueOf("DYNAMIC"))
    }

    // ── binary path logic ─────────────────────────────────────────

    @Test
    fun `buildCommand first element is the binary path`() {
        val customPath = "/custom/path/to/wstunnel"
        val cmd = defaultConfig.buildCommand(customPath)
        assertEquals(customPath, cmd.first())
    }

    // ── WstunnelConfig equality ──────────────────────────────────

    @Test
    fun `config equality — identical configs are equal`() {
        val a = WstunnelConfig()
        val b = WstunnelConfig()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `config equality — different tunnel types are not equal`() {
        val udp = WstunnelConfig(tunnelType = TunnelType.UDP)
        val socks5 = WstunnelConfig(tunnelType = TunnelType.SOCKS5)
        assertNotEquals(udp, socks5)
    }

    @Test
    fun `config equality — different modes are not equal`() {
        val fixed = WstunnelConfig(mode = WstunnelConfig.Mode.FIXED)
        val dynamic = WstunnelConfig(mode = WstunnelConfig.Mode.DYNAMIC)
        assertNotEquals(fixed, dynamic)
    }

    @Test
    fun `config copy preserves equality for same values`() {
        val original = WstunnelConfig()
        val copied = original.copy()
        assertEquals(original, copied)
    }
}
