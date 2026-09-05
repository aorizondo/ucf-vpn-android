package com.ucfvpn.app.wstunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [HevSocks5TunnelConfigGenerator.buildYaml].
 *
 * These tests run on the JVM without Android framework dependencies.
 * [HevSocks5TunnelConfigGenerator.generate] is not exercised here because it
 * requires a real [android.content.Context] (filesDir); it is covered by
 * instrumented tests instead.
 */
class HevSocks5TunnelConfigGeneratorTest {

    // ── Default YAML ──────────────────────────────────────────────

    @Test
    fun `default YAML contains listen section on port 5080`() {
        val yaml = HevSocks5TunnelConfigGenerator.buildYaml()
        assertTrue(yaml.contains("listen:"))
        assertTrue(yaml.contains("  host: 0.0.0.0"))
        assertTrue(yaml.contains("  port: 5080"))
    }

    @Test
    fun `default YAML points socks5 at 127_0_0_1_1080`() {
        val yaml = HevSocks5TunnelConfigGenerator.buildYaml()
        assertTrue(yaml.contains("socks5:"))
        assertTrue(yaml.contains("  host: 127.0.0.1"))
        assertTrue(yaml.contains("  port: 1080"))
    }

    @Test
    fun `default YAML lists default DNS upstreams`() {
        val yaml = HevSocks5TunnelConfigGenerator.buildYaml()
        assertTrue(yaml.contains("    - 1.1.1.1"))
        assertTrue(yaml.contains("    - 8.8.8.8"))
    }

    @Test
    fun `default YAML enables DNS via SOCKS5`() {
        val yaml = HevSocks5TunnelConfigGenerator.buildYaml()
        assertTrue(yaml.contains("  tcp: true"))
    }

    @Test
    fun `default YAML has info log level`() {
        val yaml = HevSocks5TunnelConfigGenerator.buildYaml()
        assertTrue(yaml.contains("log:"))
        assertTrue(yaml.contains("  level: info"))
        assertTrue(yaml.contains("  buffer: 1024"))
    }

    // ── Custom parameters ─────────────────────────────────────────

    @Test
    fun `custom socks5 host and port are reflected`() {
        val yaml = HevSocks5TunnelConfigGenerator.buildYaml(
            socks5Host = "10.0.0.5",
            socks5Port = 9999
        )
        assertTrue(yaml.contains("  host: 10.0.0.5"))
        assertTrue(yaml.contains("  port: 9999"))
    }

    @Test
    fun `dnsViaSocks5 false sets tcp false`() {
        val yaml = HevSocks5TunnelConfigGenerator.buildYaml(dnsViaSocks5 = false)
        assertTrue(yaml.contains("  tcp: false"))
    }

    @Test
    fun `custom DNS upstreams are listed`() {
        val yaml = HevSocks5TunnelConfigGenerator.buildYaml(
            dnsUpstreams = listOf("9.9.9.9", "1.0.0.1")
        )
        assertTrue(yaml.contains("    - 9.9.9.9"))
        assertTrue(yaml.contains("    - 1.0.0.1"))
        assertFalse(yaml.contains("1.1.1.1"))
    }

    @Test
    fun `custom listen port is reflected`() {
        val yaml = HevSocks5TunnelConfigGenerator.buildYaml(listenPort = 8080)
        assertTrue(yaml.contains("  port: 8080"))
    }

    // ── Validation ────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `socks5 port zero throws`() {
        HevSocks5TunnelConfigGenerator.buildYaml(socks5Port = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `socks5 port above 65535 throws`() {
        HevSocks5TunnelConfigGenerator.buildYaml(socks5Port = 65536)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `listen port zero throws`() {
        HevSocks5TunnelConfigGenerator.buildYaml(listenPort = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty DNS upstreams throw`() {
        HevSocks5TunnelConfigGenerator.buildYaml(dnsUpstreams = emptyList())
    }

    // ── YAML structure ────────────────────────────────────────────

    @Test
    fun `YAML has all four top-level sections`() {
        val yaml = HevSocks5TunnelConfigGenerator.buildYaml()
        assertTrue(yaml.contains("listen:"))
        assertTrue(yaml.contains("socks5:"))
        assertTrue(yaml.contains("dns:"))
        assertTrue(yaml.contains("log:"))
    }

    @Test
    fun `YAML DNS section has address and port 6000`() {
        val yaml = HevSocks5TunnelConfigGenerator.buildYaml()
        assertTrue(yaml.contains("  address: 0.0.0.0"))
        assertTrue(yaml.contains("  port: 6000"))
    }

    @Test
    fun `generated YAML equals expected document`() {
        val expected = """
            |listen:
            |  host: 0.0.0.0
            |  port: 5080
            |
            |socks5:
            |  host: 127.0.0.1
            |  port: 1080
            |
            |dns:
            |  address: 0.0.0.0
            |  port: 6000
            |  upstream:
            |    - 1.1.1.1
            |    - 8.8.8.8
            |  # Importante: resolver DNS via SOCKS5
            |  tcp: true
            |
            |log:
            |  level: info
            |  buffer: 1024
            |
        """.trimMargin()

        assertEquals(expected, HevSocks5TunnelConfigGenerator.buildYaml())
    }
}