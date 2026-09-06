package com.ucfvpn.app.sstp.ppp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for PPP data frames (protocol 0x0021), the tunnel's actual payload.
 *
 * These frames used to fall through [PppStack.handleFrame]'s `else` branch and
 * be discarded, so no tunnelled traffic ever reached the device. They also
 * cannot go through the control-frame parser: an IP packet has no
 * Code/Identifier/Length, so parsing one as a control frame reads its IP header
 * as protocol fields.
 */
class PppDataFrameTest {

    /** Minimal well-formed IPv4 packet, used as a stand-in payload. */
    private fun ipv4Packet(destination: String = "10.0.0.1"): ByteArray {
        val packet = ByteArray(20)
        packet[0] = 0x45
        destination.split('.').map { it.toInt().toByte() }.forEachIndexed { i, b ->
            packet[16 + i] = b
        }
        return packet
    }

    @Test
    fun `a data frame is the protocol field followed by the raw payload`() {
        val payload = ipv4Packet()
        val frame = buildPppFrame(PppConstants.PROTOCOL_IP, payload)

        assertEquals("no Code/Id/Length may be inserted", 2 + payload.size, frame.size)
        assertEquals(0x00, frame[0].toInt() and 0xFF)
        assertEquals(0x21, frame[1].toInt() and 0xFF)
        assertArrayEquals(payload, frame.copyOfRange(2, frame.size))
    }

    @Test
    fun `build and strip round-trip`() {
        val payload = ipv4Packet("192.168.5.7")
        val frame = buildPppFrame(PppConstants.PROTOCOL_IP, payload)

        assertEquals(PppConstants.PROTOCOL_IP, readPppProtocol(frame))
        assertArrayEquals(payload, stripPppProtocol(frame))
    }

    @Test
    fun `an HDLC address control prefix is tolerated`() {
        // Some peers keep the FF 03 header; the payload must survive either way.
        val payload = ipv4Packet()
        val framed = byteArrayOf(0xFF.toByte(), 0x03) +
            buildPppFrame(PppConstants.PROTOCOL_IP, payload)

        assertEquals(PppConstants.PROTOCOL_IP, readPppProtocol(framed))
        assertArrayEquals(payload, stripPppProtocol(framed))
    }

    @Test
    fun `short frames are rejected rather than misread`() {
        assertNull(readPppProtocol(ByteArray(0)))
        assertNull(readPppProtocol(ByteArray(1)))
        assertEquals(0, stripPppProtocol(ByteArray(1)).size)
    }

    @Test
    fun `the stack routes data frames to the packet callback`() {
        val stack = PppStack()
        val received = mutableListOf<ByteArray>()
        stack.onIpPacket = { received.add(it) }

        val payload = ipv4Packet("10.14.0.13")
        stack.handleFrame(buildPppFrame(PppConstants.PROTOCOL_IP, payload))

        assertEquals("the packet must reach the data path", 1, received.size)
        assertArrayEquals(payload, received.single())
    }

    @Test
    fun `control frames never reach the packet callback`() {
        val stack = PppStack()
        var delivered = false
        stack.onIpPacket = { delivered = true }

        stack.handleFrame(
            buildPppFrame(PppConstants.PROTOCOL_LCP, PppConstants.CODE_ECHO_REQUEST, 1, ByteArray(0))
        )

        assertTrue("LCP is not tunnel payload", !delivered)
    }
}
