package com.ucfvpn.app.sstp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the split-tunnel routing decision.
 *
 * This is the logic that makes the private CIDRs mean something: before it
 * existed the whole TUN was handed to hev-socks5-tunnel, so traffic for the UCF
 * internal networks went to the Internet proxy and those sites were unreachable
 * while the VPN was up.
 */
class SplitRouterTest {

    private val defaults = listOf("10.0.0.0/8", "192.168.0.0/16", "172.16.0.0/12")

    /** Minimal but well-formed IPv4 packet addressed to [destination]. */
    private fun ipv4PacketTo(destination: String, totalLength: Int = 20): ByteArray {
        val packet = ByteArray(totalLength)
        packet[0] = 0x45 // version 4, IHL 5
        val octets = destination.split('.').map { it.toInt().toByte() }
        for (i in 0..3) packet[16 + i] = octets[i]
        return packet
    }

    // ── Internal destinations ─────────────────────────────────────

    @Test
    fun `addresses inside the private ranges go through SSTP`() {
        val router = SplitRouter(defaults)

        assertTrue("10.0.0.0/8", router.isInternal(ipv4PacketTo("10.14.0.13")))
        assertTrue("lower bound of 10/8", router.isInternal(ipv4PacketTo("10.0.0.0")))
        assertTrue("upper bound of 10/8", router.isInternal(ipv4PacketTo("10.255.255.255")))
        assertTrue("192.168/16", router.isInternal(ipv4PacketTo("192.168.1.1")))
        assertTrue("172.16/12", router.isInternal(ipv4PacketTo("172.16.0.1")))
        assertTrue("upper bound of 172.16/12", router.isInternal(ipv4PacketTo("172.31.255.255")))
    }

    @Test
    fun `public addresses take the SOCKS5 path`() {
        val router = SplitRouter(defaults)

        assertFalse("8.8.8.8", router.isInternal(ipv4PacketTo("8.8.8.8")))
        assertFalse("1.1.1.1", router.isInternal(ipv4PacketTo("1.1.1.1")))
        // Just outside 172.16/12 on either side — the classic off-by-one range.
        assertFalse("172.15.x is public", router.isInternal(ipv4PacketTo("172.15.255.255")))
        assertFalse("172.32.x is public", router.isInternal(ipv4PacketTo("172.32.0.0")))
        // 9/8 and 11/8 bracket 10/8.
        assertFalse("9.x is public", router.isInternal(ipv4PacketTo("9.255.255.255")))
        assertFalse("11.x is public", router.isInternal(ipv4PacketTo("11.0.0.0")))
    }

    @Test
    fun `high octets are treated as unsigned`() {
        // A dotted quad above 127 has a negative leading byte in Kotlin; sign
        // extension here would silently misroute traffic.
        val router = SplitRouter(listOf("200.0.0.0/8"))

        assertTrue(router.isInternal(ipv4PacketTo("200.1.2.3")))
        assertFalse(router.isInternal(ipv4PacketTo("201.1.2.3")))
    }

    // ── Malformed and non-IPv4 input ──────────────────────────────

    @Test
    fun `IPv6 and malformed packets take the SOCKS5 path`() {
        val router = SplitRouter(defaults)

        val ipv6 = ByteArray(40).also { it[0] = 0x60 } // version 6
        assertFalse("IPv6 is not internal", router.isInternal(ipv6))

        assertFalse("truncated packet", router.isInternal(ByteArray(10)))
        assertFalse("empty packet", router.isInternal(ByteArray(0)))
        assertFalse("version 0", router.isInternal(ByteArray(20)))
    }

    @Test
    fun `only the declared length is parsed`() {
        val router = SplitRouter(defaults)
        // The buffer holds an internal address, but the caller says only 10 of
        // its bytes are valid, so there is no complete header to trust.
        val buffer = ipv4PacketTo("10.0.0.1", totalLength = 64)
        assertFalse(router.isInternal(buffer, length = 10))
        assertTrue(router.isInternal(buffer, length = 20))
    }

    @Test
    fun `destinationIpv4 returns null for non-IPv4 input`() {
        assertNull(SplitRouter.destinationIpv4(ByteArray(4)))
        assertNull(SplitRouter.destinationIpv4(ByteArray(40).also { it[0] = 0x60 }))
    }

    // ── CIDR parsing ──────────────────────────────────────────────

    @Test
    fun `a zero-length prefix matches everything`() {
        // `shl 32` is a no-op on Int, so a naive mask computation makes 0.0.0.0/0
        // match nothing at all — the exact opposite of what it means.
        val router = SplitRouter(listOf("0.0.0.0/0"))

        assertTrue(router.isInternal(ipv4PacketTo("8.8.8.8")))
        assertTrue(router.isInternal(ipv4PacketTo("10.0.0.1")))
    }

    @Test
    fun `a full-length prefix matches exactly one address`() {
        val router = SplitRouter(listOf("10.14.0.13/32"))

        assertTrue(router.isInternal(ipv4PacketTo("10.14.0.13")))
        assertFalse(router.isInternal(ipv4PacketTo("10.14.0.14")))
    }

    @Test
    fun `host bits outside the prefix are ignored`() {
        // 10.1.2.3/8 describes the 10/8 network; the host part must not affect
        // matching.
        val router = SplitRouter(listOf("10.1.2.3/8"))
        assertTrue(router.isInternal(ipv4PacketTo("10.99.99.99")))
    }

    @Test
    fun `an empty network list routes everything to SOCKS5`() {
        val router = SplitRouter(emptyList())
        assertFalse(router.isInternal(ipv4PacketTo("10.0.0.1")))
    }

    @Test
    fun `malformed CIDRs are rejected`() {
        val invalid = listOf(
            "10.0.0.0",        // no prefix
            "10.0.0.0/",       // dangling slash
            "/8",              // no address
            "10.0.0.0/33",     // prefix out of range
            "10.0.0.0/abc",    // non-numeric prefix
            "10.0.0/8",        // three octets
            "10.0.0.256/8",    // octet out of range
            "ucf.edu.cu/8"     // hostname
        )
        for (cidr in invalid) {
            try {
                SplitRouter(listOf(cidr))
                throw AssertionError("expected '$cidr' to be rejected")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `parseCidrToMask masks the network address`() {
        val (network, mask) = SplitRouter.parseCidrToMask("172.16.0.0/12")
        assertEquals(0xFFF00000.toInt(), mask)
        assertEquals(network, network and mask)
    }
}
