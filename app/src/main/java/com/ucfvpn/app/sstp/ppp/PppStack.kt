package com.ucfvpn.app.sstp.ppp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PPP protocol constants (RFC 1661 LCP, RFC 1334 PAP, RFC 1877 IPCP).
 */
object PppConstants {

    // PPP protocol field values
    /** Raw IPv4 payload (RFC 1332). Unlike LCP/PAP/IPCP this is not a control frame. */
    const val PROTOCOL_IP = 0x0021

    const val PROTOCOL_LCP = 0xC021
    const val PROTOCOL_PAP = 0xC023
    const val PROTOCOL_IPCP = 0x8021

    // PPP control codes (shared by LCP and IPCP)
    const val CODE_CONFIGURE_REQUEST = 1
    const val CODE_CONFIGURE_ACK = 2
    const val CODE_CONFIGURE_NAK = 3
    const val CODE_CONFIGURE_REJECT = 4
    const val CODE_TERMINATE_REQUEST = 5
    const val CODE_TERMINATE_ACK = 6
    const val CODE_CODE_REJECT = 7
    const val CODE_PROTOCOL_REJECT = 8
    const val CODE_ECHO_REQUEST = 9
    const val CODE_ECHO_REPLY = 10
    const val CODE_DISCARD_REQUEST = 11

    // PAP codes
    const val PAP_AUTHENTICATE_REQUEST = 1
    const val PAP_AUTHENTICATE_ACK = 2
    const val PAP_AUTHENTICATE_NAK = 3

    // LCP option types
    const val OPTION_MRU = 1
    const val OPTION_AUTH_PROTOCOL = 3
    const val OPTION_MAGIC_NUMBER = 5

    // IPCP option types
    const val OPTION_IPCP_IP = 3
    const val OPTION_IPCP_DNS_PRIMARY = 0x81
    const val OPTION_IPCP_DNS_SECONDARY = 0x83

    // Authentication protocols
    const val AUTH_PROTOCOL_PAP = 0xC023
    const val AUTH_PROTOCOL_MSCHAPV2 = 0xC223

    // Timing (mirrors Open-SSTP-Client ConfigClient defaults)
    const val RETRANSMIT_MS = 3000L
    const val MAX_ATTEMPTS = 10
    const val NEGOTIATION_TIMEOUT_MS = 30_000L
}

/**
 * Parsed PPP frame.
 *
 * Frames arriving from the SSTP tunnel are raw PPP: `[protocol(2), code(1), id(1), length(2), data...]`
 * without the HDLC address/control bytes (FF 03) and without FCS.
 */
data class PppFrame(
    val protocol: Int,
    val code: Int,
    val id: Int,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PppFrame
        return protocol == other.protocol && code == other.code && id == other.id && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = protocol
        result = 31 * result + code
        result = 31 * result + id
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * PPP configuration option: `[type(1), length(1), data...]`.
 */
data class PppOption(
    val type: Int,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PppOption
        return type == other.type && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * Parse a raw PPP frame into [PppFrame].
 *
 * Tolerates an optional HDLC address/control prefix (FF 03) defensively.
 * The PPP Length field covers Code+Id+Length+Data (4 + data.size), so the
 * data payload ends at `offset + length + 2` (the 2-byte protocol field is excluded).
 *
 * @return the parsed frame, or null if the frame is malformed.
 */
fun parsePppFrame(frame: ByteArray): PppFrame? {
    if (frame.size < 6) return null
    var offset = 0
    // Tolerate HDLC address/control prefix (FF 03) if present
    if (frame.size >= 8 && frame[0] == 0xFF.toByte() && frame[1] == 0x03.toByte()) {
        offset = 2
    }
    val protocol = readShort(frame, offset)
    val code = frame[offset + 2].toInt() and 0xFF
    val id = frame[offset + 3].toInt() and 0xFF
    val length = readShort(frame, offset + 4)
    if (length < 4) return null
    val dataEnd = offset + length + 2
    if (dataEnd > frame.size) return null
    val data = frame.copyOfRange(offset + 6, dataEnd)
    return PppFrame(protocol, code, id, data)
}

/**
 * Build a raw PPP frame: `[protocol(2), code(1), id(1), length(2), data...]`.
 * The Length field is 4 + data.size (protocol field excluded).
 */
fun buildPppFrame(protocol: Int, code: Int, id: Int, data: ByteArray): ByteArray {
    val length = 4 + data.size
    val buf = ByteBuffer.allocate(2 + length).order(ByteOrder.BIG_ENDIAN)
    buf.putShort(protocol.toShort())
    buf.put(code.toByte())
    buf.put(id.toByte())
    buf.putShort(length.toShort())
    buf.put(data)
    return buf.array()
}

/**
 * Build a PPP data frame: `[protocol(2)][payload...]`.
 *
 * Data frames (protocol 0x0021) carry a raw IP packet and have none of the
 * Code/Identifier/Length fields a control frame does, so they cannot go through
 * the four-argument [buildPppFrame].
 */
fun buildPppFrame(protocol: Int, payload: ByteArray): ByteArray {
    val buf = ByteBuffer.allocate(2 + payload.size).order(ByteOrder.BIG_ENDIAN)
    buf.putShort(protocol.toShort())
    buf.put(payload)
    return buf.array()
}

/**
 * Parse a sequence of PPP options from a frame payload.
 */
fun parseOptions(data: ByteArray): List<PppOption> {
    val options = mutableListOf<PppOption>()
    var offset = 0
    while (offset + 2 <= data.size) {
        val type = data[offset].toInt() and 0xFF
        val len = data[offset + 1].toInt() and 0xFF
        if (len < 2 || offset + len > data.size) break
        options.add(PppOption(type, data.copyOfRange(offset + 2, offset + len)))
        offset += len
    }
    return options
}

/**
 * Serialize a sequence of PPP options into a frame payload.
 */
fun buildOptions(options: List<PppOption>): ByteArray {
    val out = ByteArrayOutputStream()
    for (opt in options) {
        out.write(opt.type)
        out.write(2 + opt.data.size)
        out.write(opt.data)
    }
    return out.toByteArray()
}

/**
 * Read a big-endian unsigned short at [offset].
 */
fun readShort(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

/**
 * Read a big-endian int at [offset].
 */
fun readInt(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xFF) shl 24) or
        ((data[offset + 1].toInt() and 0xFF) shl 16) or
        ((data[offset + 2].toInt() and 0xFF) shl 8) or
        (data[offset + 3].toInt() and 0xFF)

/**
 * Serialize an int as 4 big-endian bytes.
 */
fun intToBytes(value: Int): ByteArray = byteArrayOf(
    ((value ushr 24) and 0xFF).toByte(),
    ((value ushr 16) and 0xFF).toByte(),
    ((value ushr 8) and 0xFF).toByte(),
    (value and 0xFF).toByte()
)

/**
 * Format a 4-byte IPv4 address as a dotted-quad string.
 */
fun ipToString(ip: ByteArray): String {
    if (ip.size != 4) return ""
    return ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
}

/**
 * Parse a dotted-quad IPv4 string into 4 bytes.
 */
fun stringToIp(ip: String): ByteArray {
    val parts = ip.split(".")
    if (parts.size != 4) return ByteArray(4)
    return ByteArray(4) { i -> parts[i].toIntOrNull()?.toByte() ?: 0 }
}

/**
 * Receive one frame from a handler mailbox, distinguishing a retransmission
 * timeout (returns null) from a closed channel (throws).
 */
internal suspend fun Channel<PppFrame>.receiveFrame(timeoutMs: Long): PppFrame? {
    val result = withTimeoutOrNull(timeoutMs) {
        receiveCatching().getOrNull()
    }
    if (result == null && isClosedForReceive) {
        throw PppNegotiationException("PPP negotiation aborted (channel closed)")
    }
    return result
}

/**
 * Result of the LCP negotiation phase.
 */
data class LcpResult(val magicNumber: Int)

/**
 * Result of the PAP authentication phase.
 */
data class PapResult(val success: Boolean, val message: String)

/**
 * Result of the IPCP negotiation phase.
 */
data class IpcpResult(val localIp: String, val dns1: String, val dns2: String)

/**
 * Final result of a full PPP negotiation (LCP → PAP → IPCP).
 *
 * [gateway] is the PEER's address, learned from the server's own IPCP
 * Configure-Request — not our own [localIp].
 */
data class PppResult(
    val localIp: String,
    val dns1: String,
    val dns2: String,
    val gateway: String
)

/**
 * Events emitted by [PppStack] during negotiation, for observability.
 */
sealed class PppEvent {
    object LcpOpened : PppEvent()
    object AuthSuccess : PppEvent()
    data class IpAssigned(
        val localIp: String,
        val dns1: String,
        val dns2: String,
        val gateway: String
    ) : PppEvent()
}

/**
 * Thrown when PPP negotiation fails (timeout, rejected auth, unsupported protocol, ...).
 */
class PppNegotiationException(message: String) : Exception(message)

/**
 * Coordinates the PPP negotiation phases: LCP → PAP → IPCP.
 *
 * Incoming frames from the SSTP tunnel are fed via [handleFrame] and dispatched
 * to the active phase handler. Outgoing frames are delivered through [sendFrame].
 *
 * @param retransmitMs interval between Configure-Request retransmissions
 * @param maxAttempts maximum retransmissions before giving up (total sends = maxAttempts + 1)
 * @param negotiationTimeoutMs overall budget for the full negotiation
 */
class PppStack(
    private val retransmitMs: Long = PppConstants.RETRANSMIT_MS,
    private val maxAttempts: Int = PppConstants.MAX_ATTEMPTS,
    private val negotiationTimeoutMs: Long = PppConstants.NEGOTIATION_TIMEOUT_MS
) {

    /** Delivers raw PPP frames to the SSTP tunnel. */
    var sendFrame: ((ByteArray) -> Unit)? = null

    /** Observability callback for negotiation milestones. */
    var onEvent: ((PppEvent) -> Unit)? = null

    /**
     * Receives raw IPv4 packets arriving from the peer (PPP protocol 0x0021),
     * i.e. the tunnel's actual payload. Wired to the data path by the caller.
     */
    var onIpPacket: ((ByteArray) -> Unit)? = null

    private var lcpHandler: LcpHandler? = null
    private var papHandler: PapHandler? = null
    private var ipcpHandler: IpCpHandler? = null

    /**
     * Run the full PPP negotiation (LCP → PAP → IPCP).
     *
     * On success the phase handlers stay alive so the LCP control loop can keep
     * answering Echo-Request/Terminate-Request frames. On failure everything is
     * torn down and a [PppNegotiationException] is returned.
     */
    suspend fun negotiate(username: String, password: String): Result<PppResult> {
        return try {
            val result = withTimeout(negotiationTimeoutMs) {
                negotiateInternal(username, password)
            }
            Result.success(result)
        } catch (e: TimeoutCancellationException) {
            closeHandlers()
            Result.failure(PppNegotiationException("PPP negotiation timed out after ${negotiationTimeoutMs}ms"))
        } catch (e: CancellationException) {
            closeHandlers()
            throw e
        } catch (e: Exception) {
            closeHandlers()
            Result.failure(e)
        }
    }

    private suspend fun negotiateInternal(username: String, password: String): PppResult {
        closeHandlers()

        val lcp = LcpHandler(
            sendFrame = { frame -> sendFrame?.invoke(frame) },
            onLcpOpened = { onEvent?.invoke(PppEvent.LcpOpened) },
            retransmitMs = retransmitMs,
            maxAttempts = maxAttempts
        )
        lcpHandler = lcp
        val lcpResult = lcp.negotiate()
        Timber.d("LCP negotiation complete: $lcpResult")

        val pap = PapHandler(
            sendFrame = { frame -> sendFrame?.invoke(frame) },
            onPapSuccess = { onEvent?.invoke(PppEvent.AuthSuccess) },
            onPapFailure = { message -> Timber.w("PAP authentication failed: $message") },
            retransmitMs = retransmitMs,
            maxAttempts = maxAttempts
        )
        papHandler = pap
        val papResult = pap.authenticate(username, password)
        Timber.d("PAP authentication complete: $papResult")

        var peerGateway = ""
        val ipcp = IpCpHandler(
            sendFrame = { frame -> sendFrame?.invoke(frame) },
            onIpCpSuccess = { localIp, dns1, dns2, gateway ->
                peerGateway = gateway
                onEvent?.invoke(PppEvent.IpAssigned(localIp, dns1, dns2, gateway))
            },
            retransmitMs = retransmitMs,
            maxAttempts = maxAttempts
        )
        ipcpHandler = ipcp
        val ipcpResult = ipcp.negotiate()
        Timber.d("IPCP negotiation complete: $ipcpResult")

        return PppResult(ipcpResult.localIp, ipcpResult.dns1, ipcpResult.dns2, peerGateway)
    }

    /**
     * Feed a raw PPP frame received from the SSTP tunnel.
     */
    fun handleFrame(frame: ByteArray) {
        // Data frames must be split off BEFORE parsing: an IP packet has no
        // Code/Identifier/Length, so parsePppFrame would read its IP header as
        // control fields and either drop it or mangle it. These used to fall
        // into the `else` branch below and be discarded, which is why no
        // tunnelled traffic ever reached the device.
        if (readPppProtocol(frame) == PppConstants.PROTOCOL_IP) {
            onIpPacket?.invoke(stripPppProtocol(frame))
            return
        }

        val ppp = parsePppFrame(frame) ?: return
        when (ppp.protocol) {
            PppConstants.PROTOCOL_LCP -> lcpHandler?.handleFrame(ppp)
            PppConstants.PROTOCOL_PAP -> papHandler?.handleFrame(ppp)
            PppConstants.PROTOCOL_IPCP -> ipcpHandler?.handleFrame(ppp)
            else -> Timber.d("PppStack: ignoring PPP frame for protocol 0x%04x", ppp.protocol)
        }
    }

    /**
     * Tear down all phase handlers and stop their control loops.
     */
    fun close() {
        closeHandlers()
    }

    private fun closeHandlers() {
        lcpHandler?.close()
        papHandler?.close()
        ipcpHandler?.close()
        lcpHandler = null
        papHandler = null
        ipcpHandler = null
    }
}
/**
 * Read the 2-byte PPP protocol field, tolerating an HDLC address/control
 * prefix (FF 03), or null when the frame is too short.
 */
internal fun readPppProtocol(frame: ByteArray): Int? {
    val offset = pppPayloadOffset(frame) ?: return null
    return readShort(frame, offset)
}

/**
 * Strip the protocol field (and any HDLC prefix) to yield the raw payload of a
 * PPP data frame.
 */
internal fun stripPppProtocol(frame: ByteArray): ByteArray {
    val offset = pppPayloadOffset(frame) ?: return ByteArray(0)
    return frame.copyOfRange(offset + 2, frame.size)
}

/** Offset of the protocol field, skipping an optional FF 03 HDLC prefix. */
private fun pppPayloadOffset(frame: ByteArray): Int? {
    if (frame.size < 2) return null
    if (frame.size >= 4 && frame[0] == 0xFF.toByte() && frame[1] == 0x03.toByte()) return 2
    return 0
}
