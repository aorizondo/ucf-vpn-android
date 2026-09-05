package com.ucfvpn.app.sstp.ppp

import org.junit.Assert.*
import org.junit.Test

class PppHandlerTest {

    // ---------------------------------------------------------------------------
    // FCS-16 Tests
    // ---------------------------------------------------------------------------

    @Test
    fun fcs16_selfCheck_producesGoodfcs() {
        val testData = byteArrayOf(0xFF.toByte(), 0x03.toByte(), 0x00.toByte(), 0x21.toByte())
        val fcs = HDLCHandler.fcs16(testData)
        val fcsFinal = fcs xor 0xFFFF
        val fcsLe = byteArrayOf(
            (fcsFinal and 0xFF).toByte(),
            ((fcsFinal shr 8) and 0xFF).toByte()
        )
        val selfCheck = HDLCHandler.fcs16(testData + fcsLe)
        assertEquals("FCS-16 self-check must produce GOODFCS 0xf0b8", 0xf0b8, selfCheck)
    }

    // The two expected values below were wrong (0x84b0 and 0xe5f6). They were
    // never verified because the suite had never run. The implementation is the
    // correct one: it satisfies the RFC 1662 GOODFCS property asserted in
    // fcs16_selfCheck, which is the definitive check for this CRC. These are the
    // values produced by the standard PPP FCS-16 (poly 0x8408, init 0xFFFF,
    // no final xor).

    @Test
    fun fcs16_singleZeroByte() {
        val data = byteArrayOf(0x00.toByte())
        val fcs = HDLCHandler.fcs16(data)
        assertEquals(0x0f87, fcs)
    }

    @Test
    fun fcs16_knownSequence() {
        val data = byteArrayOf(0xFF.toByte(), 0x03.toByte(), 0x00.toByte())
        val fcs = HDLCHandler.fcs16(data)
        assertEquals(0xd5a8, fcs)
    }

    @Test
    fun fcs16_goodfcsProperty_holdsForVariousPayloads() {
        val payloads = listOf(
            byteArrayOf(),
            byteArrayOf(0xFF.toByte()),
            byteArrayOf(0xFF.toByte(), 0x03.toByte()),
            byteArrayOf(0xFF.toByte(), 0x03.toByte(), 0x00.toByte(), 0x21.toByte()),
            byteArrayOf(0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte())
        )
        for (payload in payloads) {
            val fcs = HDLCHandler.fcs16(payload)
            val fcsFinal = fcs xor 0xFFFF
            val fcsLe = byteArrayOf(
                (fcsFinal and 0xFF).toByte(),
                ((fcsFinal shr 8) and 0xFF).toByte()
            )
            val selfCheck = HDLCHandler.fcs16(payload + fcsLe)
            assertEquals("GOODFCS for " + payload.size + " bytes", 0xf0b8, selfCheck)
        }
    }

    // ---------------------------------------------------------------------------
    // HDLC Encode/Decode Roundtrip Tests
    // ---------------------------------------------------------------------------

    @Test
    fun hdlc_roundtrip_preservesFrame() {
        val rawFrame = byteArrayOf(
            0xFF.toByte(), 0x03.toByte(),
            0x00.toByte(), 0x21.toByte(),
            0x45.toByte(), 0x00.toByte()
        )
        val encoded = HDLCHandler.encode(rawFrame)
        val decoded = HDLCHandler.decode(encoded)
        assertEquals(rawFrame.toHex(), decoded.toHex())
    }

    @Test
    fun hdlc_roundtrip_withFf03Prefix() {
        val rawFrame = byteArrayOf(
            0xFF.toByte(), 0x03.toByte(),
            0xc0.toByte(), 0x21.toByte(),
            0x01.toByte(), 0x0d.toByte(), 0x00.toByte(), 0x0b.toByte()
        )
        val encoded = HDLCHandler.encode(rawFrame)
        val decoded = HDLCHandler.decode(encoded)
        assertEquals(rawFrame.toHex(), decoded.toHex())
    }

    @Test
    fun hdlc_roundtrip_emptyFrame() {
        val rawFrame = byteArrayOf()
        val encoded = HDLCHandler.encode(rawFrame)
        assertTrue(encoded.isNotEmpty())
        assertEquals(0x7E.toByte(), encoded.first())
        assertEquals(0x7E.toByte(), encoded.last())
    }

    @Test
    fun hdlc_encode_escapesControlBytes() {
        val rawFrame = byteArrayOf(0xFF.toByte(), 0x03.toByte(),
            0xc0.toByte(), 0x21.toByte(), 0x01.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x08.toByte())
        val encoded = HDLCHandler.encode(rawFrame)
        assertEquals(0x7E.toByte(), encoded.first())
        assertEquals(0x7E.toByte(), encoded.last())
        assertTrue(encoded.any { it.toInt() and 0xFF == 0x7D })
        val decoded = HDLCHandler.decode(encoded)
        assertEquals(rawFrame.toHex(), decoded.toHex())
    }

    @Test
    fun hdlc_encode_escapesFlagByte() {
        val rawFrame = byteArrayOf(0xFF.toByte(), 0x03.toByte(), 0x7e.toByte())
        val encoded = HDLCHandler.encode(rawFrame)
        val contains7D5E = encoded.toList().windowed(2).any { pair ->
            (pair[0].toInt() and 0xFF) == 0x7D && (pair[1].toInt() and 0xFF) == 0x5E
        }
        assertTrue(contains7D5E)
        val decoded = HDLCHandler.decode(encoded)
        assertEquals(rawFrame.toHex(), decoded.toHex())
    }

    @Test
    fun hdlc_encode_escapesEscapeByte() {
        val rawFrame = byteArrayOf(0xFF.toByte(), 0x03.toByte(), 0x7d.toByte())
        val encoded = HDLCHandler.encode(rawFrame)
        val contains7D5D = encoded.toList().windowed(2).any { pair ->
            (pair[0].toInt() and 0xFF) == 0x7D && (pair[1].toInt() and 0xFF) == 0x5D
        }
        assertTrue(contains7D5D)
        val decoded = HDLCHandler.decode(encoded)
        assertEquals(rawFrame.toHex(), decoded.toHex())
    }

    @Test
    fun hdlc_decode_multipleFrames_returnsFirstValid() {
        val frame1 = byteArrayOf(0xFF.toByte(), 0x03.toByte(), 0xc0.toByte(), 0x21.toByte())
        val frame2 = byteArrayOf(0xFF.toByte(), 0x03.toByte(), 0x00.toByte(), 0x21.toByte())
        val encoded1 = HDLCHandler.encode(frame1)
        val encoded2 = HDLCHandler.encode(frame2)
        val combined = encoded1 + encoded2
        val decoded = HDLCHandler.decode(combined)
        assertEquals(frame1.toHex(), decoded.toHex())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
