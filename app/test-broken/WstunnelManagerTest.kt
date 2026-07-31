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
    fun `dynamic factory creates DYNAMIC mode`() {
        val dyn = WstunnelConfig.dynamic()
        assertEquals(WstunnelConfig.Mode.DYNAMIC, dyn.mode)
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
            "--websocket-ping-frequency", "10s"
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
    fun `buildCommand element count is 13 for FIXED mode`() {
        val cmd = defaultConfig.buildCommand(binaryPath)
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
