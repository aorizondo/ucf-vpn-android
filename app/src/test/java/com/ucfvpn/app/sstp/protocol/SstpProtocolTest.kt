package com.ucfvpn.app.sstp.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SstpProtocolTest {

    // --- Enum value verification (byte-exact match with Python) ---

    @Test
    fun `SstpVersion has correct value`() {
        assertEquals(0x01, SstpVersion.SSTP_VERSION_1.value)
    }

    @Test
    fun `SstpMessageType enum values match Python`() {
        assertEquals(0x0001, SstpMessageType.CALL_CONNECT_REQUEST.value)
        assertEquals(0x0002, SstpMessageType.CALL_CONNECT_ACK.value)
        assertEquals(0x0003, SstpMessageType.CALL_CONNECT_NAK.value)
        assertEquals(0x0004, SstpMessageType.CALL_CONNECTED.value)
        assertEquals(0x0005, SstpMessageType.CALL_ABORT.value)
        assertEquals(0x0006, SstpMessageType.CALL_DISCONNECT.value)
        assertEquals(0x0007, SstpMessageType.CALL_DISCONNECT_ACK.value)
        assertEquals(0x0008, SstpMessageType.ECHO_REQUEST.value)
        assertEquals(0x0009, SstpMessageType.ECHO_RESPONSE.value)
    }

    @Test
    fun `SstpAttributeId enum values match Python`() {
        assertEquals(0x01, SstpAttributeId.NO_ERROR.value)
        assertEquals(0x02, SstpAttributeId.ENCAPSULATED_PROTOCOL_ID.value)
        assertEquals(0x03, SstpAttributeId.STATUS_INFO.value)
        assertEquals(0x04, SstpAttributeId.CRYPTO_BINDING.value)
    }

    @Test
    fun `SstpEncapsulatedProtocol PPP value matches Python`() {
        assertEquals(0x0001, SstpEncapsulatedProtocol.PPP.value)
    }

    // --- SstpPacket pack/unpack roundtrip ---

    @Test
    fun `SstpPacket pack produces correct header`() {
        val packet = SstpPacket(version = 1, isControl = true, data = byteArrayOf(0x00, 0x01))
        val bytes = packet.pack()
        assertEquals(0x10, bytes[0].toInt() and 0xFF)
        assertEquals(0x01, bytes[1].toInt() and 0xFF)
    }

    @Test
    fun `SstpPacket data packet has correct control flag`() {
        val packet = SstpPacket(version = 1, isControl = false, data = byteArrayOf(0xFF.toByte(), 0x03))
        val bytes = packet.pack()
        assertEquals(0x10, bytes[0].toInt() and 0xFF)
        assertEquals(0x00, bytes[1].toInt() and 0xFF)
    }

    @Test
    fun `SstpPacket length field is big-endian`() {
        val data = byteArrayOf(0x00, 0x01, 0x02)
        val packet = SstpPacket(version = 1, isControl = true, data = data)
        val bytes = packet.pack()
        // HEADER_SIZE=4, data=3, total=7
        assertEquals(0x00, bytes[2].toInt() and 0xFF)
        assertEquals(0x07, bytes[3].toInt() and 0xFF)
    }

    @Test
    fun `SstpPacket pack unpack roundtrip`() {
        val original = SstpPacket(version = 1, isControl = true, data = byteArrayOf(0x00, 0x01, 0x02, 0x03))
        val packed = original.pack()
        val unpacked = SstpPacket.unpack(packed)
        assertEquals(original.version, unpacked.version)
        assertEquals(original.isControl, unpacked.isControl)
        assertEquals(original.length, unpacked.length)
        assertArrayEquals(original.data, unpacked.data)
    }

    @Test
    fun `SstpPacket data packet pack unpack roundtrip`() {
        val original = SstpPacket(version = 1, isControl = false, data = byteArrayOf(0xFF.toByte(), 0x03))
        val packed = original.pack()
        val unpacked = SstpPacket.unpack(packed)
        assertFalse(unpacked.isControl)
        assertArrayEquals(original.data, unpacked.data)
    }

    // --- SstpControlPacket pack/unpack roundtrip ---

    @Test
    fun `SstpControlPacket CALL_CONNECT_REQUEST structure matches Python`() {
        val ctrl = SstpControlPacket(
            SstpMessageType.CALL_CONNECT_REQUEST,
            listOf(SstpAttributeId.NO_ERROR.value to ByteArray(0))
        )
        val packed = ctrl.pack()
        // Message type (2) + num attrs (2) + attr header (4) = 8
        assertEquals(8, packed.size)
        // Message type = 0x0001 big-endian
        assertEquals(0x00, packed[0].toInt() and 0xFF)
        assertEquals(0x01, packed[1].toInt() and 0xFF)
        // Num attributes = 1
        assertEquals(0x00, packed[2].toInt() and 0xFF)
        assertEquals(0x01, packed[3].toInt() and 0xFF)
        // Attr: reserved=0x01, id=0x01, len=0x0004
        assertEquals(0x01, packed[4].toInt() and 0xFF)
        assertEquals(0x01, packed[5].toInt() and 0xFF)
        assertEquals(0x00, packed[6].toInt() and 0xFF)
        assertEquals(0x04, packed[7].toInt() and 0xFF)
    }

    @Test
    fun `SstpControlPacket CALL_CONNECTED empty attributes matches Python`() {
        val ctrl = SstpControlPacket(SstpMessageType.CALL_CONNECTED, emptyList())
        val packed = ctrl.pack()
        assertEquals(4, packed.size)
        // Message type = 0x0004 big-endian
        assertEquals(0x00, packed[0].toInt() and 0xFF)
        assertEquals(0x04, packed[1].toInt() and 0xFF)
        // Num attributes = 0
        assertEquals(0x00, packed[2].toInt() and 0xFF)
        assertEquals(0x00, packed[3].toInt() and 0xFF)
    }

    @Test
    fun `SstpControlPacket pack unpack roundtrip`() {
        val original = SstpControlPacket(
            SstpMessageType.CALL_CONNECT_REQUEST,
            listOf(SstpAttributeId.NO_ERROR.value to ByteArray(0))
        )
        val packed = original.pack()
        val unpacked = SstpControlPacket.unpack(packed)
        assertEquals(original.messageType, unpacked.messageType)
        assertEquals(original.attributes.size, unpacked.attributes.size)
        assertArrayEquals(original.attributes[0].second, unpacked.attributes[0].second)
    }

    @Test
    fun `SstpControlPacket with crypto binding unpack roundtrip`() {
        val nonce = ByteArray(32) { 0xAA.toByte() }
        val cmk = ByteArray(32) { 0xBB.toByte() }
        val cryptoAttr = createCryptoBindingAttribute(nonce, cmk)
        val original = SstpControlPacket(
            SstpMessageType.CALL_CONNECTED,
            listOf(SstpAttributeId.CRYPTO_BINDING.value to cryptoAttr)
        )
        val packed = original.pack()
        val unpacked = SstpControlPacket.unpack(packed)
        assertEquals(original.messageType, unpacked.messageType)
        assertEquals(original.attributes.size, unpacked.attributes.size)
        assertEquals(100, unpacked.attributes[0].second.size)
    }

    // --- Function output byte-exact match with Python ---

    @Test
    fun `createCallConnectRequest hex matches Python`() {
        val result = createCallConnectRequest()
        // Python: 1001000c0001000101010004
        val expectedHex = "1001000c0001000101010004"
        assertEquals(expectedHex, result.toHexString())
    }

    @Test
    fun `createEchoRequest hex matches Python`() {
        val result = createEchoRequest()
        // Python: 1001000800080000
        val expectedHex = "1001000800080000"
        assertEquals(expectedHex, result.toHexString())
    }

    @Test
    fun `createPppDataPacket hex matches Python`() {
        val pppFrame = byteArrayOf(0xFF.toByte(), 0x03)
        val result = createPppDataPacket(pppFrame)
        // Python: 10000006ff03
        val expectedHex = "10000006ff03"
        assertEquals(expectedHex, result.toHexString())
    }

    @Test
    fun `createCryptoBindingAttribute length is 100 bytes`() {
        val nonce = ByteArray(32)
        val cmk = ByteArray(32)
        val result = createCryptoBindingAttribute(nonce, cmk)
        assertEquals(100, result.size)
    }

    @Test
    fun `createCryptoBindingAttribute hex matches Python`() {
        val nonce = ByteArray(32)
        val cmk = ByteArray(32)
        val result = createCryptoBindingAttribute(nonce, cmk)
        // Python: 000000010000000000000000... (reserved=000000, hashId=01, nonce=32*00, certHash=32*00, cmk=32*00)
        assertEquals(0x00, result[0].toInt() and 0xFF)
        assertEquals(0x00, result[1].toInt() and 0xFF)
        assertEquals(0x00, result[2].toInt() and 0xFF)
        assertEquals(0x01, result[3].toInt() and 0xFF)
        // bytes 4-35 = nonce (32 zeros)
        for (i in 4..35) assertEquals(0x00, result[i].toInt() and 0xFF)
        // bytes 36-67 = certHash (32 zeros)
        for (i in 36..67) assertEquals(0x00, result[i].toInt() and 0xFF)
        // bytes 68-99 = cmk (32 zeros)
        for (i in 68..99) assertEquals(0x00, result[i].toInt() and 0xFF)
    }

    @Test
    fun `createCryptoBindingAttribute with known nonce and cmk`() {
        val nonce = ByteArray(32) { 0xAA.toByte() }
        val cmk = ByteArray(32) { 0xBB.toByte() }
        val result = createCryptoBindingAttribute(nonce, cmk)
        assertEquals(100, result.size)
        // Check reserved bytes
        assertEquals(0x00, result[0].toInt() and 0xFF)
        assertEquals(0x00, result[1].toInt() and 0xFF)
        assertEquals(0x00, result[2].toInt() and 0xFF)
        // Check hash protocol ID
        assertEquals(0x01, result[3].toInt() and 0xFF)
        // Check nonce (32 bytes of 0xAA)
        for (i in 4..35) assertEquals(0xAA.toInt() and 0xFF, result[i].toInt() and 0xFF)
        // Check certHash (32 bytes of 0x00)
        for (i in 36..67) assertEquals(0x00, result[i].toInt() and 0xFF)
        // Check cmk (32 bytes of 0xBB)
        for (i in 68..99) assertEquals(0xBB.toInt() and 0xFF, result[i].toInt() and 0xFF)
    }

    @Test
    fun `createCallConnected with crypto binding packet hex`() {
        val nonce = ByteArray(32) { 0xAA.toByte() }
        val cmk = ByteArray(32) { 0xBB.toByte() }
        val cryptoAttr = createCryptoBindingAttribute(nonce, cmk)
        val packet = createCallConnected(listOf(SstpAttributeId.CRYPTO_BINDING.value to cryptoAttr))
        // Full packet structure: 0x10 + 0x01 + len(2) + control_data
        // control_data: 0x0004 + 0x0001 + attr(104)
        // Total = 4 + 4 + 104 = 112
        assertEquals(112, packet.size)
    }

    @Test
    fun `createCallConnected with attributes roundtrip unpack`() {
        val nonce = ByteArray(32) { 0x11.toByte() }
        val cmk = ByteArray(32) { 0x22.toByte() }
        val cryptoAttr = createCryptoBindingAttribute(nonce, cmk)
        val attrs = listOf(SstpAttributeId.CRYPTO_BINDING.value to cryptoAttr)
        val packet = createCallConnected(attrs)
        val unpacked = SstpPacket.unpack(packet)
        assertTrue(unpacked.isControl)
        assertEquals(112, unpacked.length)
        val ctrlUnpacked = SstpControlPacket.unpack(unpacked.data)
        assertEquals(SstpMessageType.CALL_CONNECTED, ctrlUnpacked.messageType)
        assertEquals(1, ctrlUnpacked.attributes.size)
        assertArrayEquals(cryptoAttr, ctrlUnpacked.attributes[0].second)
    }

    // --- Helper extension ---

    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }
}
