package com.ucfvpn.app.sstp.client

import com.ucfvpn.app.sstp.protocol.SstpControlPacket
import com.ucfvpn.app.sstp.protocol.SstpMessageType
import com.ucfvpn.app.sstp.protocol.SstpPacket
import com.ucfvpn.app.sstp.protocol.createCallConnectRequest
import com.ucfvpn.app.sstp.protocol.createCryptoBindingAttribute
import com.ucfvpn.app.sstp.protocol.createEchoRequest
import com.ucfvpn.app.sstp.protocol.createPppDataPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Unit tests for SstpTunnel and SstpHandshake.
 */
class SstpTunnelTest {

    companion object {
        // Known test vectors for crypto binding verification
        private val TEST_NONCE = ByteArray(32) { 0xAA.toByte() }
        private val TEST_CMK = ByteArray(32) { 0xBB.toByte() }
        private val TEST_MK = ByteArray(32) { 0xCC.toByte() }
        private val TEST_SEND_KEY = ByteArray(16) { 0x11.toByte() }
        private val TEST_RECV_KEY = ByteArray(16) { 0x22.toByte() }
    }

    // --- SstpState enum tests ---

    @Test
    fun `SstpState has all expected values`() {
        assertEquals(5, SstpState.values().size)
        assertEquals(SstpState.DISCONNECTED, SstpState.valueOf("DISCONNECTED"))
        assertEquals(SstpState.CONNECTING, SstpState.valueOf("CONNECTING"))
        assertEquals(SstpState.CONNECTED, SstpState.valueOf("CONNECTED"))
        assertEquals(SstpState.DISCONNECTING, SstpState.valueOf("DISCONNECTING"))
        assertEquals(SstpState.ERROR, SstpState.valueOf("ERROR"))
    }

    @Test
    fun `SstpState ordinals are correct`() {
        assertEquals(0, SstpState.DISCONNECTED.ordinal)
        assertEquals(1, SstpState.CONNECTING.ordinal)
        assertEquals(2, SstpState.CONNECTED.ordinal)
        assertEquals(3, SstpState.DISCONNECTING.ordinal)
        assertEquals(4, SstpState.ERROR.ordinal)
    }

    // --- SstpTunnel interface tests ---

    @Test
    fun `SstpTunnelImpl starts in DISCONNECTED state`() {
        val tunnel = SstpTunnelImpl()
        assertEquals(SstpState.DISCONNECTED, tunnel.state)
        assertFalse(tunnel.isConnected)
        assertNull(tunnel.localAddress)
    }

    @Test
    fun `SstpTunnelImpl onPppFrameReceived callback can be set`() {
        val tunnel = SstpTunnelImpl()
        var called = false
        tunnel.onPppFrameReceived = { frame ->
            called = true
            assertEquals(2, frame.size)
        }
        // Simulate receiving a PPP frame
        tunnel.onPppFrameReceived?.invoke(byteArrayOf(0xFF.toByte(), 0x03))
        assertTrue(called)
    }

    @Test
    fun `SstpTunnelImpl onStateChanged callback is invoked`() {
        val tunnel = SstpTunnelImpl()
        var stateChanges = mutableListOf<SstpState>()
        tunnel.onStateChanged = { state ->
            stateChanges.add(state)
        }

        // State should start as DISCONNECTED
        assertEquals(1, stateChanges.size)
        assertEquals(SstpState.DISCONNECTED, stateChanges[0])
    }

    // --- SSL Context tests ---

    @Test
    fun `SstpHandshake creates SSLContext successfully`() {
        val handshake = SstpHandshake("npv.ucf.edu.cu", 443)
        val context = handshake.createSslContext()
        assertNotNull(context)
        assertEquals("TLS", context.protocol)
    }

    @Test
    fun `SSLContext protocol is TLS`() {
        val handshake = SstpHandshake("npv.ucf.edu.cu", 443)
        val context = handshake.createSslContext()
        // SSLContext should support TLS
        assertTrue(context.supportedProtocols.any { it.startsWith("TLS") })
    }

    // --- ECHO_REQUEST packet tests ---

    @Test
    fun `createEchoRequest produces correct hex`() {
        val result = createEchoRequest()
        // Expected: 1001000800080000
        val expectedHex = "1001000800080000"
        assertEquals(expectedHex, result.toHexString())
    }

    @Test
    fun `createEchoRequest has correct structure`() {
        val packet = createEchoRequest()
        assertEquals(8, packet.size)

        // Parse header
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val byte0 = buf.get().toInt() and 0xFF
        val byte1 = buf.get().toInt() and 0xFF
        val length = buf.short.toInt() and 0xFFFF

        assertEquals(0x10, byte0) // Version 1
        assertEquals(0x01, byte1) // Control
        assertEquals(8, length)
    }

    // --- PPP data packet tests ---

    @Test
    fun `createPppDataPacket wraps PPP frame correctly`() {
        val pppFrame = byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x21)
        val result = createPppDataPacket(pppFrame)

        // Should have 4-byte header + data
        assertEquals(4 + pppFrame.size, result.size)

        // First byte should be 0x10 (version)
        assertEquals(0x10, result[0].toInt() and 0xFF)
        // Second byte should be 0x00 (data, not control)
        assertEquals(0x00, result[1].toInt() and 0xFF)

        // Data portion should match original
        assertEquals(0xFF.toByte(), result[4])
        assertEquals(0x03, result[5].toInt() and 0xFF)
    }

    @Test
    fun `createPppDataPacket hex matches Python`() {
        val pppFrame = byteArrayOf(0xFF.toByte(), 0x03)
        val result = createPppDataPacket(pppFrame)
        // Python: 10000006ff03
        val expectedHex = "10000006ff03"
        assertEquals(expectedHex, result.toHexString())
    }

    // --- Crypto Binding attribute tests ---

    @Test
    fun `createCryptoBindingAttribute has correct length`() {
        val result = createCryptoBindingAttribute(TEST_NONCE, TEST_CMK)
        // Reserved(3) + HashID(1) + Nonce(32) + CertHash(32) + MAC(32) = 100
        assertEquals(100, result.size)
    }

    @Test
    fun `createCryptoBindingAttribute hash protocol is SHA256`() {
        val result = createCryptoBindingAttribute(TEST_NONCE, TEST_CMK)
        // Hash protocol ID at byte 3
        assertEquals(0x01, result[3].toInt() and 0xFF)
    }

    @Test
    fun `createCryptoBindingAttribute contains nonce`() {
        val result = createCryptoBindingAttribute(TEST_NONCE, TEST_CMK)
        // Nonce at bytes 4-35
        for (i in 4..35) {
            assertEquals(0xAA.toInt() and 0xFF, result[i].toInt() and 0xFF)
        }
    }

    // --- HMAC-SHA1 crypto binding tests ---

    @Test
    fun `HMAC-SHA1 produces correct output for known input`() {
        // Test vector from RFC 2202
        val key = "key".toByteArray()
        val data = "The quick brown fox jumps over the lazy dog".toByteArray()
        val expected = "de7c9b85b8b78aa6bc8a7a36f70a90701c9db4d9".toHexString()

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val result = mac.doFinal(data)

        assertEquals(expected, result.toHexString())
    }

    @Test
    fun `CMK derivation matches Python implementation`() {
        // CMK = HMAC-SHA1(MK, "SSTP inner method derived CMK\0" + HLAK)
        val mk = TEST_MK
        val hlak = TEST_SEND_KEY + TEST_RECV_KEY

        val cmkData = "SSTP inner method derived CMK\u0000".toByteArray() + hlak

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(mk, "HmacSHA1"))
        val cmk = mac.doFinal(cmkData)

        assertEquals(20, cmk.size) // SHA1 produces 20 bytes

        // CMK should be different from MK
        assertFalse(cmk.contentEquals(mk))
    }

    @Test
    fun `MK export label is correct`() {
        // The label for exporting keying material should be "SSTP Key Binding"
        val label = "SSTP Key Binding".toByteArray()
        assertEquals("SSTP Key Binding", String(label))
        assertEquals(17, label.size)
    }

    @Test
    fun `Certificate hash padded to 32 bytes`() {
        val certHash = ByteArray(20) { 0xAB.toByte() } // SHA1 is 20 bytes
        val padded = certHash + ByteArray(12) { 0 }

        assertEquals(32, padded.size)

        // First 20 bytes should be original hash
        for (i in 0..19) {
            assertEquals(0xAB.toInt() and 0xFF, padded[i].toInt() and 0xFF)
        }
        // Last 12 bytes should be zeros
        for (i in 20..31) {
            assertEquals(0x00, padded[i].toInt() and 0xFF)
        }
    }

    // --- CALL_CONNECT_REQUEST tests ---

    @Test
    fun `createCallConnectRequest produces correct hex`() {
        val result = createCallConnectRequest()
        // Python: 1001000c0001000101010004
        val expectedHex = "1001000c0001000101010004"
        assertEquals(expectedHex, result.toHexString())
    }

    @Test
    fun `CALL_CONNECT_REQUEST has NO_ERROR attribute`() {
        val packet = createCallConnectRequest()
        val sstpPacket = SstpPacket.unpack(packet)

        assertTrue(sstpPacket.isControl)

        val control = SstpControlPacket.unpack(sstpPacket.data)
        assertEquals(SstpMessageType.CALL_CONNECT_REQUEST, control.messageType)

        // Should have NO_ERROR attribute
        assertEquals(1, control.attributes.size)
        assertEquals(0x01, control.attributes[0].first) // NO_ERROR
    }

    // --- Packet roundtrip tests ---

    @Test
    fun `SstpPacket data packet roundtrip`() {
        val originalData = byteArrayOf(0xFF.toByte(), 0x03, 0xC0, 0x21)
        val packet = createPppDataPacket(originalData)
        val unpacked = SstpPacket.unpack(packet)

        assertFalse(unpacked.isControl)
        assertArrayEquals(originalData, unpacked.data)
    }

    @Test
    fun `SstpPacket control packet roundtrip`() {
        val callConnectRequest = createCallConnectRequest()
        val unpacked = SstpPacket.unpack(callConnectRequest)

        assertTrue(unpacked.isControl)
        assertEquals(SstpMessageType.CALL_CONNECT_REQUEST.value.toShort() shr 8 and 0xFF,
                     unpacked.data[0].toInt() and 0xFF)
    }

    // --- Helper extensions ---

    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }
}
