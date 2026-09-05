package com.ucfvpn.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the top-level helpers used by the split-tunnel path:
 * [parseCidr] and [parseSocks5Proxy].
 *
 * These tests run on the JVM without Android framework dependencies.
 */
class VpnGatewayServiceHelpersTest {

    // ── parseCidr — valid input ───────────────────────────────────

    @Test
    fun `parseCidr parses 10_0_0_0_8`() {
        assertEquals("10.0.0.0" to 8, parseCidr("10.0.0.0/8"))
    }

    @Test
    fun `parseCidr parses 192_168_0_0_16`() {
        assertEquals("192.168.0.0" to 16, parseCidr("192.168.0.0/16"))
    }

    @Test
    fun `parseCidr parses 172_16_0_0_12`() {
        assertEquals("172.16.0.0" to 12, parseCidr("172.16.0.0/12"))
    }

    @Test
    fun `parseCidr parses default route`() {
        assertEquals("0.0.0.0" to 0, parseCidr("0.0.0.0/0"))
    }

    @Test
    fun `parseCidr parses IPv6`() {
        assertEquals("::" to 0, parseCidr("::/0"))
    }

    // ── parseCidr — invalid input ─────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `parseCidr rejects hostname`() {
        parseCidr("internet.ucf.edu.cu/16")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseCidr rejects missing slash`() {
        parseCidr("10.0.0.0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseCidr rejects trailing slash`() {
        parseCidr("10.0.0.0/")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseCidr rejects non numeric prefix`() {
        parseCidr("10.0.0.0/abc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseCidr rejects prefix above 32`() {
        parseCidr("10.0.0.0/33")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseCidr rejects negative prefix`() {
        parseCidr("10.0.0.0/-1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseCidr rejects octet above 255`() {
        parseCidr("10.0.0.256/8")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseCidr rejects three octets`() {
        parseCidr("10.0.0/8")
    }

    // ── parseSocks5Proxy — valid input ────────────────────────────

    @Test
    fun `parseSocks5Proxy parses default proxy`() {
        assertEquals("127.0.0.1" to 1080, parseSocks5Proxy("127.0.0.1:1080"))
    }

    @Test
    fun `parseSocks5Proxy parses custom port`() {
        assertEquals("10.0.0.5" to 9999, parseSocks5Proxy("10.0.0.5:9999"))
    }

    // ── parseSocks5Proxy — invalid input ──────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `parseSocks5Proxy rejects missing port`() {
        parseSocks5Proxy("127.0.0.1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseSocks5Proxy rejects trailing colon`() {
        parseSocks5Proxy("127.0.0.1:")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseSocks5Proxy rejects non numeric port`() {
        parseSocks5Proxy("127.0.0.1:abc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseSocks5Proxy rejects port zero`() {
        parseSocks5Proxy("127.0.0.1:0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseSocks5Proxy rejects port above 65535`() {
        parseSocks5Proxy("127.0.0.1:70000")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseSocks5Proxy rejects empty host`() {
        parseSocks5Proxy(":1080")
    }
}