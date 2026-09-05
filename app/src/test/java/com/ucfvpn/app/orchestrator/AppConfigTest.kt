package com.ucfvpn.app.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {

    @Test
    fun `SplitTunnelConfig defaults`() {
        val config = SplitTunnelConfig()
        assertEquals(listOf("10.0.0.0/8", "192.168.0.0/16", "172.16.0.0/12"), config.privateNetworks)
        assertTrue(config.bypassApps.isEmpty())
        assertTrue(config.defaultViaProxy)
    }

    @Test
    fun `AppConfig defaults`() {
        val config = AppConfig(
            sstpUsername = "user",
            sstpPassword = "pass",
            proxyUsername = "puser",
            proxyPassword = "ppass"
        )
        assertEquals("npv.ucf.edu.cu", config.sstpServer)
        assertEquals(443, config.sstpPort)
        assertEquals(SplitTunnelConfig(), config.splitTunnelConfig)
        assertFalse(config.debugWstunnelSocks5)
    }

    // ── parseCsvList — valid input ────────────────────────────────

    @Test
    fun `parseCsvList parses plain csv`() {
        assertEquals(listOf("a", "b", "c"), parseCsvList("a,b,c"))
    }

    @Test
    fun `parseCsvList trims whitespace`() {
        assertEquals(listOf("a", "b", "c"), parseCsvList(" a , b ,c "))
    }

    @Test
    fun `parseCsvList filters blank entries`() {
        assertEquals(listOf("a", "b", "c"), parseCsvList("a,,b, ,c"))
    }

    // ── parseCsvList — empty input ────────────────────────────────

    @Test
    fun `parseCsvList empty string yields empty list`() {
        assertTrue(parseCsvList("").isEmpty())
    }

    @Test
    fun `parseCsvList only commas yields empty list`() {
        assertTrue(parseCsvList(",,,").isEmpty())
    }

    // ── parsePrivateNetworks ──────────────────────────────────────

    @Test
    fun `parsePrivateNetworks parses csv`() {
        assertEquals(
            listOf("10.0.0.0/8", "192.168.0.0/16"),
            parsePrivateNetworks("10.0.0.0/8, 192.168.0.0/16")
        )
    }

    @Test
    fun `parsePrivateNetworks empty input falls back to defaults`() {
        assertEquals(SplitTunnelConfig().privateNetworks, parsePrivateNetworks(""))
    }

    @Test
    fun `parsePrivateNetworks blank input falls back to defaults`() {
        assertEquals(SplitTunnelConfig().privateNetworks, parsePrivateNetworks(" , , "))
    }
}