package com.ucfvpn.app.sstp.ppp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.receiveCatching
import kotlinx.coroutines.launch
import timber.log.Timber
import java.security.SecureRandom

/**
 * LCP negotiation states (RFC 1661).
 */
enum class LcpState { CLOSED, REQ_SENT, ACK_RCVD, ACK_SENT, OPENED }

/**
 * LCP (Link Control Protocol) negotiation handler.
 *
 * Implements the client side of RFC 1661: sends Configure-Request with
 * MRU=1400, a random Magic Number and Auth-Protocol=PAP, retransmits on
 * timeout, applies Configure-Nak/Reject, and opens the link once both the
 * client and the server have exchanged Configure-Requests (clientReady &&
 * serverReady), mirroring the Open-SSTP-Client reference.
 *
 * After the link opens, a control loop keeps answering Echo-Request and
 * Terminate-Request frames while PAP/IPCP run.
 *
 * @param sendFrame delivers raw PPP frames to the SSTP tunnel
 * @param onLcpOpened invoked when the LCP link reaches OPENED
 * @param retransmitMs interval between Configure-Request retransmissions
 * @param maxAttempts maximum retransmissions before giving up (total sends = maxAttempts + 1)
 */
class LcpHandler(
    private val sendFrame: (ByteArray) -> Unit,
    private val onLcpOpened: () -> Unit,
    private val retransmitMs: Long = PppConstants.RETRANSMIT_MS,
    private val maxAttempts: Int = PppConstants.MAX_ATTEMPTS
) {
    private val mailbox = Channel<PppFrame>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var state = LcpState.CLOSED
    private var clientReady = false
    private var serverReady = false
    private var magicNumber = 0
    private val options = mutableListOf<PppOption>()
    private var idCounter = 0
    private var controlJob: Job? = null

    /**
     * Negotiate the LCP link.
     *
     * @return the negotiated magic number once the link is OPENED
     * @throws PppNegotiationException on timeout, Terminate-Request, MS-CHAPv2
     * requirement or a rejected Auth-Protocol
     */
    suspend fun negotiate(): LcpResult {
        state = LcpState.REQ_SENT
        clientReady = false
        serverReady = false
        magicNumber = SecureRandom().nextInt()
        options.clear()
        options.add(PppOption(PppConstants.OPTION_MRU, byteArrayOf(0x05, 0x78))) // 1400
        options.add(PppOption(PppConstants.OPTION_MAGIC_NUMBER, intToBytes(magicNumber)))
        options.add(PppOption(PppConstants.OPTION_AUTH_PROTOCOL, byteArrayOf(0xC0.toByte(), 0x23))) // PAP

        var attempts = 0
        while (true) {
            if (attempts > maxAttempts) {
                throw PppNegotiationException(
                    "LCP negotiation failed: no response after ${maxAttempts + 1} attempts"
                )
            }
            sendConfigureRequest()
            attempts++

            val frame = mailbox.receiveFrame(retransmitMs) ?: continue

            when (frame.code) {
                PppConstants.CODE_CONFIGURE_ACK -> {
                    if (clientReady) {
                        // Server restarted the negotiation: reset and resend
                        Timber.d("LCP: duplicate Configure-Ack, server restarted negotiation")
                        clientReady = false
                        serverReady = false
                        state = LcpState.REQ_SENT
                    } else {
                        clientReady = true
                        state = LcpState.ACK_RCVD
                        if (serverReady) {
                            openLink()
                            return LcpResult(magicNumber)
                        }
                    }
                }
                PppConstants.CODE_CONFIGURE_NAK -> handleConfigureNak(frame)
                PppConstants.CODE_CONFIGURE_REJECT -> handleConfigureReject(frame)
                PppConstants.CODE_CONFIGURE_REQUEST -> {
                    serverReady = true
                    state = LcpState.ACK_SENT
                    sendConfigureAck(frame)
                    if (clientReady) {
                        openLink()
                        return LcpResult(magicNumber)
                    }
                }
                PppConstants.CODE_TERMINATE_REQUEST -> {
                    sendTerminateAck(frame.id)
                    throw PppNegotiationException("LCP negotiation failed: server sent Terminate-Request")
                }
                PppConstants.CODE_ECHO_REQUEST -> sendEchoReply(frame.id)
                else -> Timber.d("LCP: ignoring code ${frame.code}")
            }
        }
    }

    /**
     * Feed an incoming LCP frame.
     */
    fun handleFrame(frame: PppFrame) {
        if (frame.protocol == PppConstants.PROTOCOL_LCP) {
            mailbox.trySend(frame)
        }
    }

    /**
     * Stop the control loop and release the mailbox.
     */
    fun close() {
        scope.cancel()
        mailbox.close()
    }

    private fun openLink() {
        state = LcpState.OPENED
        Timber.d("LCP link OPENED (magic 0x%08x)", magicNumber)
        onLcpOpened()
        startControlLoop()
    }

    /**
     * Control loop that keeps the LCP link alive while PAP/IPCP run:
     * answers Echo-Request, Terminate-Request and re-negotiation Configure-Requests.
     */
    private fun startControlLoop() {
        controlJob = scope.launch {
            while (true) {
                val frame = mailbox.receiveCatching().getOrNull() ?: break
                when (frame.code) {
                    PppConstants.CODE_ECHO_REQUEST -> sendEchoReply(frame.id)
                    PppConstants.CODE_TERMINATE_REQUEST -> {
                        Timber.d("LCP: received Terminate-Request, sending Terminate-Ack")
                        sendTerminateAck(frame.id)
                    }
                    PppConstants.CODE_CONFIGURE_REQUEST -> {
                        Timber.d("LCP: server restarted negotiation, sending Configure-Ack")
                        sendConfigureAck(frame)
                    }
                    else -> Timber.d("LCP control loop: ignoring code ${frame.code}")
                }
            }
        }
    }

    private fun sendConfigureRequest() {
        val data = buildOptions(options)
        sendFrame(buildPppFrame(PppConstants.PROTOCOL_LCP, PppConstants.CODE_CONFIGURE_REQUEST, nextId(), data))
    }

    private fun sendConfigureAck(request: PppFrame) {
        sendFrame(buildPppFrame(PppConstants.PROTOCOL_LCP, PppConstants.CODE_CONFIGURE_ACK, request.id, request.data))
    }

    private fun sendTerminateAck(id: Int) {
        sendFrame(buildPppFrame(PppConstants.PROTOCOL_LCP, PppConstants.CODE_TERMINATE_ACK, id, ByteArray(0)))
    }

    private fun sendEchoReply(id: Int) {
        sendFrame(buildPppFrame(PppConstants.PROTOCOL_LCP, PppConstants.CODE_ECHO_REPLY, id, ByteArray(0)))
    }

    /**
     * Apply the server's suggested values from a Configure-Nak and resend.
     * MS-CHAPv2 (0xC223) is detected here and rejected, as it is not supported.
     */
    private fun handleConfigureNak(frame: PppFrame) {
        for (opt in parseOptions(frame.data)) {
            when (opt.type) {
                PppConstants.OPTION_AUTH_PROTOCOL -> {
                    if (opt.data.size >= 2) {
                        val authProtocol = readShort(opt.data, 0)
                        if (authProtocol == PppConstants.AUTH_PROTOCOL_MSCHAPV2) {
                            throw PppNegotiationException(
                                "LCP: server requires MS-CHAPv2 (0xC223), which is not supported"
                            )
                        }
                        // PAP (0xC023) is already what we send; nothing to change
                    }
                }
                PppConstants.OPTION_MRU -> {
                    if (opt.data.size >= 2) {
                        val mru = readShort(opt.data, 0)
                        options.removeAll { it.type == PppConstants.OPTION_MRU }
                        options.add(
                            PppOption(
                                PppConstants.OPTION_MRU,
                                byteArrayOf(((mru shr 8) and 0xFF).toByte(), (mru and 0xFF).toByte())
                            )
                        )
                    }
                }
                PppConstants.OPTION_MAGIC_NUMBER -> {
                    if (opt.data.size >= 4) {
                        magicNumber = readInt(opt.data, 0)
                        options.removeAll { it.type == PppConstants.OPTION_MAGIC_NUMBER }
                        options.add(PppOption(PppConstants.OPTION_MAGIC_NUMBER, intToBytes(magicNumber)))
                    }
                }
            }
        }
    }

    /**
     * Remove rejected options from a Configure-Reject and resend.
     * A rejected Auth-Protocol is fatal: without authentication the link cannot proceed.
     */
    private fun handleConfigureReject(frame: PppFrame) {
        for (opt in parseOptions(frame.data)) {
            when (opt.type) {
                PppConstants.OPTION_AUTH_PROTOCOL ->
                    throw PppNegotiationException("LCP: server rejected Auth-Protocol option")
                PppConstants.OPTION_MRU -> options.removeAll { it.type == PppConstants.OPTION_MRU }
                PppConstants.OPTION_MAGIC_NUMBER -> options.removeAll { it.type == PppConstants.OPTION_MAGIC_NUMBER }
            }
        }
    }

    private fun nextId(): Int {
        idCounter = (idCounter + 1) and 0xFF
        return idCounter
    }
}