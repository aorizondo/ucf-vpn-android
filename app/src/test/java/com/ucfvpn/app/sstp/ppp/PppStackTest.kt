package com.ucfvpn.app.sstp.ppp

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        // Client sends Configure-Request with MRU=1400, Magic Number and Auth=PAP
        var frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.PROTOCOL_LCP, frame.protocol)
        assertEquals(PppConstants.CODE_CONFIGURE_REQUEST, frame.code)
        val lcpOptions = parseOptions(frame.data)
        assertTrue(lcpOptions.any { it.type == PppConstants.OPTION_MRU && readShort(it.data, 0) == 1400 })
        assertTrue(lcpOptions.any { it.type == PppConstants.OPTION_MAGIC_NUMBER && it.data.size == 4 })
        assertTrue(
            lcpOptions.any {
                it.type == PppConstants.OPTION_AUTH_PROTOCOL && readShort(it.data, 0) == PppConstants.AUTH_PROTOCOL_PAP
            }
        )
        val firstLcpReqId = frame.id

        // Server sends its own Configure-Request; client must ACK it
        stack.handleFrame(
            buildPppFrame(PppConstants.PROTOCOL_LCP, PppConstants.CODE_CONFIGURE_REQUEST, 1, byteArrayOf())
        )
        frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.PROTOCOL_LCP, frame.protocol)
        assertEquals(PppConstants.CODE_CONFIGURE_ACK, frame.code)

        // Client resends its Configure-Request; server ACKs it → LCP OPENED
        frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.PROTOCOL_LCP, frame.protocol)
        assertEquals(PppConstants.CODE_CONFIGURE_REQUEST, frame.code)
        stack.handleFrame(
            buildPppFrame(PppConstants.PROTOCOL_LCP, PppConstants.CODE_CONFIGURE_ACK, frame.id, byteArrayOf())
        )

        // --- PAP phase ---
        frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.PROTOCOL_PAP, frame.protocol)
        assertEquals(PppConstants.PAP_AUTHENTICATE_REQUEST, frame.code)
        // Payload: [len(user)][user][len(pass)][pass]
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

        // Client resends with the NAK'd values; server ACKs → IPCP OPENED
        frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.PROTOCOL_IPCP, frame.protocol)
        assertEquals(PppConstants.CODE_CONFIGURE_REQUEST, frame.code)
        stack.handleFrame(
            buildPppFrame(PppConstants.PROTOCOL_IPCP, PppConstants.CODE_CONFIGURE_ACK, frame.id, byteArrayOf())
        )

        val result = negotiation.await().getOrThrow()
        assertEquals("10.0.0.2", result.localIp)
        assertEquals("8.8.8.8", result.dns1)
        assertEquals("8.8.4.4", result.dns2)
        assertEquals("10.0.0.2", result.gateway)

        // One event per phase
        assertEquals(3, events.size)
        assertTrue(events[0] is PppEvent.LcpOpened)
        assertTrue(events[1] is PppEvent.AuthSuccess)
        assertTrue(events[2] is PppEvent.IpAssigned)
        val assigned = events[2] as PppEvent.IpAssigned
        assertEquals("10.0.0.2", assigned.localIp)
        assertEquals("10.0.0.2", assigned.gateway)

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

        // Server never responds: client retransmits maxAttempts + 1 times
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
        stack.handleFrame(
            buildPppFrame(PppConstants.PROTOCOL_LCP, PppConstants.CODE_CONFIGURE_REQUEST, 1, byteArrayOf())
        )
        frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.CODE_CONFIGURE_ACK, frame.code)
        frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.CODE_CONFIGURE_REQUEST, frame.code)
        stack.handleFrame(
            buildPppFrame(PppConstants.PROTOCOL_LCP, PppConstants.CODE_CONFIGURE_ACK, frame.id, byteArrayOf())
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
    fun negotiate_msChapv2Nak_fails() = runBlocking {
        val stack = PppStack()
        val sent = Channel<ByteArray>(Channel.UNLIMITED)
        stack.sendFrame = { frame -> sent.trySend(frame) }

        val negotiation = async { stack.negotiate("user", "pass") }

        // Client sends LCP Configure-Request; server NAKs with MS-CHAPv2
        val frame = parsePppFrame(sent.receive())!!
        assertEquals(PppConstants.PROTOCOL_LCP, frame.protocol)
        assertEquals(PppConstants.CODE_CONFIGURE_REQUEST, frame.code)
        val lcpReqId = frame.id

        val nakOptions = buildOptions(
            listOf(
                PppOption(PppConstants.OPTION_AUTH_PROTOCOL, byteArrayOf(0xC2.toByte(), 0x23))
            )
        )
        stack.handleFrame(
            buildPppFrame(PppConstants.PROTOCOL_LCP, PppConstants.CODE_CONFIGURE_NAK, lcpReqId, nakOptions)
        )

        val result = negotiation.await()
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is PppNegotiationException)
        assertTrue(error!!.message!!.contains("MS-CHAPv2"))

        stack.close()
    }
}