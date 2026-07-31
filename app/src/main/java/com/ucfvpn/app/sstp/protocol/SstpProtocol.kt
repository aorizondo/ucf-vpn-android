package com.ucfvpn.app.sstp.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SSTP Protocol Constants and Message Definitions.
 * Based on Microsoft's [MS-SSTP] specification.
 */
object SstpProtocol {

    const val HEADER_SIZE = 4

    // Version is in high nibble of first byte, but we use 0x10 for byte0 in V1
    const val PACKET_VERSION_MASK = 0x10

    fun Byte.toHexString(): String = "%02X".format(this)
    fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }
}

/**
 * SSTP protocol version.
 */
enum class SstpVersion(val value: Int) {
    SSTP_VERSION_1(0x01)
}

/**
 * SSTP control message types.
 */
enum class SstpMessageType(val value: Int) {
    CALL_CONNECT_REQUEST(0x0001),
    CALL_CONNECT_ACK(0x0002),
    CALL_CONNECT_NAK(0x0003),
    CALL_CONNECTED(0x0004),
    CALL_ABORT(0x0005),
    CALL_DISCONNECT(0x0006),
    CALL_DISCONNECT_ACK(0x0007),
    ECHO_REQUEST(0x0008),
    ECHO_RESPONSE(0x0009)
}

/**
 * SSTP attribute IDs.
 */
enum class SstpAttributeId(val value: Int) {
    NO_ERROR(0x01),
    ENCAPSULATED_PROTOCOL_ID(0x02),
    STATUS_INFO(0x03),
    CRYPTO_BINDING(0x04)
}

/**
 * Encapsulated protocol types.
 */
enum class SstpEncapsulatedProtocol(val value: Int) {
    PPP(0x0001)
}

/**
 * SSTP packet structure.
 */
data class SstpPacket(
    val version: Int = 0x01,
    val isControl: Boolean = true,
    val length: Int = 0,
    val data: ByteArray = ByteArray(0)
) {
    /**
     * Pack SSTP packet to bytes.
     * byte[0] = 0x10 (version 1 indicator)
     * byte[1] = 0x01 if control, 0x00 otherwise
     * byte[2-3] = length (big endian)
     */
    fun pack(): ByteArray {
        val packetLength = SstpProtocol.HEADER_SIZE + data.size
        val buf = ByteBuffer.allocate(packetLength).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x10.toByte())
        buf.put(if (isControl) 0x01.toByte() else 0x00.toByte())
        buf.putShort(packetLength.toShort())
        buf.put(data)
        return buf.array()
    }

    /**
     * Unpack SSTP packet from bytes.
     */
    companion object {
        fun unpack(data: ByteArray): SstpPacket {
            require(data.size >= SstpProtocol.HEADER_SIZE) { "Packet too short: ${data.size} bytes" }
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val byte0 = buf.get()
            val byte1 = buf.get()
            val length = buf.short.toInt() and 0xFFFF

            val version = (byte0.toInt() shr 4) and 0x0F
            val isControl = (byte1.toInt() and 0x01) != 0
            val packetData = data.copyOfRange(SstpProtocol.HEADER_SIZE, length)

            return SstpPacket(version, isControl, length, packetData)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SstpPacket
        return version == other.version && isControl == other.isControl && length == other.length && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + isControl.hashCode()
        result = 31 * result + length
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * SSTP control packet with message type and attributes.
 */
data class SstpControlPacket(
    val messageType: SstpMessageType,
    val attributes: List<Pair<Int, ByteArray>> = emptyList()
) {
    /**
     * Pack control packet to bytes.
     * Message Type (2 bytes big endian) + Num Attributes (2 bytes big endian) + attributes
     */
    fun pack(): ByteArray {
        val attrDataBuilder = ByteArrayOutputStream()
        for ((attrId, attrValue) in attributes) {
            val attrLen = 4 + attrValue.size
            val attrBuf = ByteBuffer.allocate(attrLen).order(ByteOrder.BIG_ENDIAN)
            attrBuf.put(0x01.toByte())       // Reserved with M=1
            attrBuf.put(attrId.toByte())
            attrBuf.putShort(attrLen.toShort())
            attrBuf.put(attrValue)
            attrDataBuilder.write(attrBuf.array())
        }

        val headerBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        headerBuf.putShort(messageType.value.toShort())
        headerBuf.putShort(attributes.size.toShort())

        return headerBuf.array() + attrDataBuilder.toByteArray()
    }

    /**
     * Unpack control packet from bytes.
     */
    companion object {
        fun unpack(data: ByteArray): SstpControlPacket {
            require(data.size >= 4) { "Control packet too short" }
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val messageType = buf.short.toInt() and 0xFFFF
            val numAttrs = buf.short.toInt() and 0xFFFF

            val attrs = mutableListOf<Pair<Int, ByteArray>>()
            var offset = 4
            repeat(numAttrs) {
                require(offset + 4 <= data.size) { "Attribute header exceeds data" }
                val attrBuf = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN)
                attrBuf.get() // reserved
                val attrId = attrBuf.get().toInt() and 0xFF
                val attrLen = attrBuf.short.toInt() and 0xFFFF
                val attrValue = data.copyOfRange(offset + 4, offset + attrLen)
                attrs.add(attrId to attrValue)
                offset += attrLen
            }

            val msgType = SstpMessageType.values().find { it.value == messageType }
                ?: throw IllegalArgumentException("Unknown SSTP message type: $messageType")
            return SstpControlPacket(msgType, attrs)
        }
    }
}

/**
 * Create SSTP CALL_CONNECT_REQUEST packet.
 * CALL_CONNECT_REQUEST with NO_ERROR attribute (empty value)
 */
fun createCallConnectRequest(): ByteArray {
    val control = SstpControlPacket(
        SstpMessageType.CALL_CONNECT_REQUEST,
        listOf(SstpAttributeId.NO_ERROR.value to ByteArray(0))
    )
    val packet = SstpPacket(isControl = true, data = control.pack())
    return packet.pack()
}

/**
 * Create SSTP CALL_CONNECTED packet.
 */
fun createCallConnected(attributes: List<Pair<Int, ByteArray>>? = null): ByteArray {
    val control = SstpControlPacket(
        SstpMessageType.CALL_CONNECTED,
        attributes ?: emptyList()
    )
    val packet = SstpPacket(isControl = true, data = control.pack())
    return packet.pack()
}

/**
 * Create CRYPTO_BINDING attribute value.
 *
 * Format: Reserved (3 bytes) + Hash Protocol ID (1 byte) + Nonce (32 bytes) + Cert Hash (32 bytes) + MAC (32 bytes)
 * Hash Protocol ID: 0x01 = SHA256
 */
fun createCryptoBindingAttribute(nonce: ByteArray, cmk: ByteArray): ByteArray {
    // Reserved (3) + Hash Protocol ID (1) + Nonce (32) + Cert Hash (32) + CMK (32) = 100
    val certHash = ByteArray(32)
    val buf = ByteBuffer.allocate(100).order(ByteOrder.BIG_ENDIAN)
    buf.put(ByteArray(3))           // Reserved
    buf.put(0x01.toByte())           // Hash Protocol ID = SHA256
    buf.put(nonce)                   // 32 bytes
    buf.put(certHash)                // 32 bytes
    buf.put(cmk)                     // 32 bytes
    return buf.array()
}

/**
 * Create SSTP ECHO_REQUEST packet.
 */
fun createEchoRequest(): ByteArray {
    val control = SstpControlPacket(SstpMessageType.ECHO_REQUEST)
    val packet = SstpPacket(isControl = true, data = control.pack())
    return packet.pack()
}

/**
 * Encapsulate PPP frame in SSTP data packet.
 */
fun createPppDataPacket(pppFrame: ByteArray): ByteArray {
    val packet = SstpPacket(isControl = false, data = pppFrame)
    return packet.pack()
}
