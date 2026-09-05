package com.ucfvpn.app.sstp.ppp

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the PPP negotiation stack (LCP → PAP → IPCP).
 *
 * A real [PppStack] is driven against a scripted server: outgoing frames are
 * captured on a [Channel] and server responses are injected via
 * [PppStack.handleFrame].
 */
class PppStackTest {

    /** LCP Configure-Request carrying Auth-Protocol=PAP, as a server would send. */
    private fun serverLcpRequest(id: Int): ByteArray = buildPppFrame(
        PppConstants.PROTOCOL_LCP,
        PppConstants.CODE_CONFIGURE_REQUEST,
        id,
        buildOptions(
            listOf(
                PppOption(PppConstants.OPTION_AUTH_PROTOCOL, byteArrayOf(0xC0.toByte(), 0x23))
            )
        )
    )

    // ---------------------------------------------------------------------------
    // Full negotiation flow
    // ---------------------------------------------------------------------------

    @Test
    fun negotiate_fullFlow_succeeds() = runBlocking {
        val stack = PppStack()
        val sent = Channel<ByteArray>(Channel.UNLIMITED)
        val events = mutableListOf<PppEvent>()
        stack.sendFrame = { frame -> sent.trySend(frame) }
        stack.onEvent = { events.add(it) }

        val negotiation = async { stack.negotiate("user", "pass") }

        // --- LCP phase ---
        // Client sends Configure-Request with MRU=1400 and a Magic Number.
        var frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.PROTOCOL_LCP, frame.protocol)
        assertEquals(PppConstants.CODE_CONFIGURE_REQUEST, frame.code)
        val lcpOptions = parseOptions(frame.data)
        assertTrue(lcpOptions.any { it.type == PppConstants.OPTION_MRU && readShort(it.data, 0) == 1400 })
        assertTrue(lcpOptions.any { it.type == PppConstants.OPTION_MAGIC_NUMBER && it.data.size == 4 })
        // RFC 1661 §6.2: Auth-Protocol is requested BY the authenticator. A
        // client offering it is asking the server to authenticate to us, which
        // usually earns a Configure-Reject.
        assertFalse(
            "client must not send Auth-Protocol in its own Configure-Request",
            lcpOptions.any { it.type == PppConstants.OPTION_AUTH_PROTOCOL }
        )
        val clientLcpReqId = frame.id

        // Server sends its own Configure-Request (demanding PAP); client ACKs it.
        stack.handleFrame(serverLcpRequest(id = 1))
        frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.PROTOCOL_LCP, frame.protocol)
        assertEquals(PppConstants.CODE_CONFIGURE_ACK, frame.code)
        assertEquals(1, frame.id)

        // Server ACKs the client's original request → LCP OPENED.
        stack.handleFrame(
            buildPppFrame(PppConstants.PROTOCOL_LCP, PppConstants.CODE_CONFIGURE_ACK, clientLcpReqId, byteArrayOf())
        )

        // --- PAP phase ---
        frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.PROTOCOL_PAP, frame.protocol)
        assertEquals(PppConstants.PAP_AUTHENTICATE_REQUEST, frame.code)
        // Payload: [len(user)][user][len(pass)][pass] — RFC 1334 §2.2.1 uses a
        // ONE-byte length for each field, so 4+4 characters must yield exactly
        // 10 bytes with no trailing padding.
        assertEquals("PAP payload must not carry padding", 10, frame.data.size)
        assertEquals(4, frame.data[0].toInt() and 0xFF)
        assertEquals("user", String(frame.data, 1, 4, Charsets.UTF_8))
        assertEquals(4, frame.data[5].toInt() and 0xFF)
        assertEquals("pass", String(frame.data, 6, 4, Charsets.UTF_8))
        val papId = frame.id

        // Server accepts the credentials
        stack.handleFrame(
            buildPppFrame(PppConstants.PROTOCOL_PAP, PppConstants.PAP_AUTHENTICATE_ACK, papId, "Welcome".toByteArray())
        )

        // --- IPCP phase ---
        frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.PROTOCOL_IPCP, frame.protocol)
        assertEquals(PppConstants.CODE_CONFIGURE_REQUEST, frame.code)
        val firstIpcpReqId = frame.id

        // Server NAKs with the assigned IP and DNS servers
        val nakOptions = buildOptions(
            listOf(
                PppOption(PppConstants.OPTION_IPCP_IP, stringToIp("10.0.0.2")),
                PppOption(PppConstants.OPTION_IPCP_DNS_PRIMARY, stringToIp("8.8.8.8")),
                PppOption(PppConstants.OPTION_IPCP_DNS_SECONDARY, stringToIp("8.8.4.4"))
            )
        )
        stack.handleFrame(
            buildPppFrame(PppConstants.PROTOCOL_IPCP, PppConstants.CODE_CONFIGURE_NAK, firstIpcpReqId, nakOptions)
        )

        // Client resends with the NAK'd values, under a NEW Identifier.
        frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.PROTOCOL_IPCP, frame.protocol)
        assertEquals(PppConstants.CODE_CONFIGURE_REQUEST, frame.code)
        assertTrue("a changed request must use a new Identifier", frame.id != firstIpcpReqId)
        val secondIpcpReqId = frame.id

        // IPCP is bidirectional: the server sends its own Configure-Request with
        // its address, and the client must ACK it. That address is the gateway.
        stack.handleFrame(
            buildPppFrame(
                PppConstants.PROTOCOL_IPCP,
                PppConstants.CODE_CONFIGURE_REQUEST,
                7,
                buildOptions(listOf(PppOption(PppConstants.OPTION_IPCP_IP, stringToIp("10.0.0.1"))))
            )
        )
        frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.PROTOCOL_IPCP, frame.protocol)
        assertEquals(PppConstants.CODE_CONFIGURE_ACK, frame.code)
        assertEquals(7, frame.id)

        // Server ACKs the client's request → IPCP OPENED
        stack.handleFrame(
            buildPppFrame(PppConstants.PROTOCOL_IPCP, PppConstants.CODE_CONFIGURE_ACK, secondIpcpReqId, byteArrayOf())
        )

        val result = negotiation.await().getOrThrow()
        assertEquals("10.0.0.2", result.localIp)
        assertEquals("8.8.8.8", result.dns1)
        assertEquals("8.8.4.4", result.dns2)
        // The gateway is the PEER's address, not our own.
        assertEquals("10.0.0.1", result.gateway)

        // One event per phase
        assertEquals(3, events.size)
        assertTrue(events[0] is PppEvent.LcpOpened)
        assertTrue(events[1] is PppEvent.AuthSuccess)
        assertTrue(events[2] is PppEvent.IpAssigned)
        val assigned = events[2] as PppEvent.IpAssigned
        assertEquals("10.0.0.2", assigned.localIp)
        assertEquals("10.0.0.1", assigned.gateway)

        stack.close()
    }

    // ---------------------------------------------------------------------------
    // Retransmission uses a stable Identifier
    // ---------------------------------------------------------------------------

    @Test
    fun negotiate_lcpRetransmissions_reuseIdentifier() = runBlocking {
        val stack = PppStack(retransmitMs = 50, maxAttempts = 3)
        val sent = Channel<ByteArray>(Channel.UNLIMITED)
        stack.sendFrame = { frame -> sent.trySend(frame) }

        val negotiation = async { stack.negotiate("user", "pass") }

        // RFC 1661 §5.1: retransmissions of an unchanged request keep the same
        // Identifier, otherwise the peer's Ack cannot be matched to a request.
        val ids = mutableListOf<Int>()
        repeat(4) {
            val frame = parsePppFrame(sent.receive())!!
            assertEquals(PppConstants.CODE_CONFIGURE_REQUEST, frame.code)
            ids.add(frame.id)
        }
        assertEquals("all retransmissions must share one Identifier", 1, ids.distinct().size)

        val result = negotiation.await()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("LCP"))

        stack.close()
    }

    // ---------------------------------------------------------------------------
    // LCP timeout
    // ---------------------------------------------------------------------------

    @Test
    fun negotiate_lcpTimeout_fails() = runBlocking {
        val stack = PppStack(retransmitMs = 50, maxAttempts = 3)
        val sent = Channel<ByteArray>(Channel.UNLIMITED)
        stack.sendFrame = { frame -> sent.trySend(frame) }

        val negotiation = async { stack.negotiate("user", "pass") }

        // Server never responds: client sends once, then retransmits maxAttempts times
        var lcpSends = 0
        while (true) {
            val frame = parsePppFrame(sent.receive()) ?: break
            if (frame.protocol != PppConstants.PROTOCOL_LCP) break
            lcpSends++
            if (lcpSends >= 4) break
        }
        assertEquals(4, lcpSends)

        val result = negotiation.await()
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is PppNegotiationException)
        assertTrue(error!!.message!!.contains("LCP"))

        stack.close()
    }

    // ---------------------------------------------------------------------------
    // PAP rejection
    // ---------------------------------------------------------------------------

    @Test
    fun negotiate_papFailure_fails() = runBlocking {
        val stack = PppStack()
        val sent = Channel<ByteArray>(Channel.UNLIMITED)
        stack.sendFrame = { frame -> sent.trySend(frame) }

        val negotiation = async { stack.negotiate("user", "pass") }

        // --- LCP phase (as in the full flow) ---
        var frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.PROTOCOL_LCP, frame.protocol)
        val clientLcpReqId = frame.id
        stack.handleFrame(serverLcpRequest(id = 1))
        frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.CODE_CONFIGURE_ACK, frame.code)
        stack.handleFrame(
            buildPppFrame(PppConstants.PROTOCOL_LCP, PppConstants.CODE_CONFIGURE_ACK, clientLcpReqId, byteArrayOf())
        )

        // --- PAP phase: server rejects the credentials ---
        frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.PROTOCOL_PAP, frame.protocol)
        assertEquals(PppConstants.PAP_AUTHENTICATE_REQUEST, frame.code)
        val papId = frame.id
        stack.handleFrame(
            buildPppFrame(PppConstants.PROTOCOL_PAP, PppConstants.PAP_AUTHENTICATE_NAK, papId, "Bad credentials".toByteArray())
        )

        val result = negotiation.await()
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is PppNegotiationException)
        assertTrue(error!!.message!!.contains("PAP"))

        stack.close()
    }

    // ---------------------------------------------------------------------------
    // MS-CHAPv2 detection
    // ---------------------------------------------------------------------------

    @Test
    fun negotiate_msChapv2Request_fails() = runBlocking {
        val stack = PppStack()
        val sent = Channel<ByteArray>(Channel.UNLIMITED)
        stack.sendFrame = { frame -> sent.trySend(frame) }

        val negotiation = async { stack.negotiate("user", "pass") }

        val frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.PROTOCOL_LCP, frame.protocol)
        assertEquals(PppConstants.CODE_CONFIGURE_REQUEST, frame.code)

        // The server states which auth protocol it demands in its OWN
        // Configure-Request. Blanket-acking this and then speaking PAP would
        // stall the link until it timed out.
        stack.handleFrame(
            buildPppFrame(
                PppConstants.PROTOCOL_LCP,
                PppConstants.CODE_CONFIGURE_REQUEST,
                1,
                buildOptions(
                    listOf(PppOption(PppConstants.OPTION_AUTH_PROTOCOL, byteArrayOf(0xC2.toByte(), 0x23)))
                )
            )
        )

        val result = negotiation.await()
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is PppNegotiationException)
        assertTrue(error!!.message!!.contains("MS-CHAPv2"))

        stack.close()
    }

    // ---------------------------------------------------------------------------
    // Unsupported server options are rejected, not acked
    // ---------------------------------------------------------------------------

    @Test
    fun negotiate_unknownServerOption_isRejected() = runBlocking {
        val stack = PppStack()
        val sent = Channel<ByteArray>(Channel.UNLIMITED)
        stack.sendFrame = { frame -> sent.trySend(frame) }

        val negotiation = async { stack.negotiate("user", "pass") }

        parsePppFrame(sent.receive())!! // client's Configure-Request

        // Option 0x07 (Protocol-Field-Compression) is not implemented here, so it
        // must come back as a Configure-Reject rather than a blanket Ack.
        stack.handleFrame(
            buildPppFrame(
                PppConstants.PROTOCOL_LCP,
                PppConstants.CODE_CONFIGURE_REQUEST,
                3,
                buildOptions(listOf(PppOption(0x07, byteArrayOf())))
            )
        )

        val frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.CODE_CONFIGURE_REJECT, frame.code)
        assertEquals(3, frame.id)
        assertTrue(parseOptions(frame.data).any { it.type == 0x07 })

        negotiation.cancel()
        stack.close()
    }
}
