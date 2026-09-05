package com.ucfvpn.app.sstp.ppp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
     * Identifier of the Configure-Request currently in flight. Kept across
     * retransmissions and cleared whenever [options] changes, so the next
     * request gets a fresh id.
     */
    private var requestId: Int? = null

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
        requestId = null
        magicNumber = SecureRandom().nextInt()
        options.clear()
        options.add(PppOption(PppConstants.OPTION_MRU, byteArrayOf(0x05, 0x78))) // 1400
        options.add(PppOption(PppConstants.OPTION_MAGIC_NUMBER, intToBytes(magicNumber)))
        // NOTE: no Authentication-Protocol option here. Per RFC 1661 §6.2 that
        // option is sent by the side DEMANDING authentication — the server. A
        // client asking for it is telling the server "authenticate yourself to
        // me", which typically earns a Configure-Reject and kills the link.
        // Which auth protocol we must speak is read from the server's own
        // Configure-Request, in [handleServerConfigureRequest].

        // Retransmission is driven by silence, not by traffic: `attempts` counts
        // only elapsed timeouts. Counting every received frame as an attempt
        // exhausted the budget during a perfectly healthy negotiation.
        sendConfigureRequest()
        var attempts = 1

        while (true) {
            val frame = mailbox.receiveFrame(retransmitMs)

            if (frame == null) {
                if (attempts > maxAttempts) {
                    throw PppNegotiationException(
                        "LCP negotiation failed: no response after ${maxAttempts + 1} attempts"
                    )
                }
                sendConfigureRequest() // same Identifier — this is a retransmission
                attempts++
                continue
            }

            when (frame.code) {
                PppConstants.CODE_CONFIGURE_ACK -> {
                    if (frame.id != requestId) {
                        // Ack for a superseded request: ignore, keep waiting.
                        Timber.d("LCP: stale Configure-Ack id=${frame.id} (expected $requestId)")
                    } else if (clientReady) {
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
                PppConstants.CODE_CONFIGURE_NAK -> {
                    handleConfigureNak(frame)
                    // Options changed → new request, new Identifier, fresh budget.
                    requestId = null
                    sendConfigureRequest()
                    attempts = 1
                }
                PppConstants.CODE_CONFIGURE_REJECT -> {
                    handleConfigureReject(frame)
                    requestId = null
                    sendConfigureRequest()
                    attempts = 1
                }
                PppConstants.CODE_CONFIGURE_REQUEST -> {
                    if (handleServerConfigureRequest(frame)) {
                        serverReady = true
                        state = LcpState.ACK_SENT
                        if (clientReady) {
                            openLink()
                            return LcpResult(magicNumber)
                        }
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

    /**
     * Handle a Configure-Request sent by the server.
     *
     * The server's request is what tells us which authentication protocol it
     * demands, so its options must be inspected rather than blanket-acked.
     * Acking an MS-CHAPv2 request and then speaking PAP just stalls the link
     * until it times out.
     *
     * @return true if the request was acknowledged (the server's side is now
     * configured), false if we had to Nak/Reject and must wait for a new request.
     * @throws PppNegotiationException if the server demands an auth protocol we
     * cannot speak.
     */
    private fun handleServerConfigureRequest(frame: PppFrame): Boolean {
        val serverOptions = parseOptions(frame.data)
        val unsupported = mutableListOf<PppOption>()

        for (opt in serverOptions) {
            when (opt.type) {
                PppConstants.OPTION_AUTH_PROTOCOL -> {
                    val authProtocol = if (opt.data.size >= 2) readShort(opt.data, 0) else -1
                    when (authProtocol) {
                        PppConstants.AUTH_PROTOCOL_PAP -> {
                            Timber.d("LCP: server requests PAP authentication")
                        }
                        PppConstants.AUTH_PROTOCOL_MSCHAPV2 -> throw PppNegotiationException(
                            "LCP: server requires MS-CHAPv2 (0xC223), which is not supported"
                        )
                        else -> throw PppNegotiationException(
                            "LCP: server requires unsupported auth protocol 0x%04x".format(authProtocol)
                        )
                    }
                }
                // MRU and Magic-Number are always acceptable as sent by the peer.
                PppConstants.OPTION_MRU, PppConstants.OPTION_MAGIC_NUMBER -> Unit
                // Anything else (compression, ACCM, callback, ...) we do not
                // implement, so it must be rejected rather than silently acked.
                else -> unsupported.add(opt)
            }
        }

        if (unsupported.isNotEmpty()) {
            Timber.d("LCP: rejecting ${unsupported.size} unsupported server option(s)")
            sendFrame(
                buildPppFrame(
                    PppConstants.PROTOCOL_LCP,
                    PppConstants.CODE_CONFIGURE_REJECT,
                    frame.id,
                    buildOptions(unsupported)
                )
            )
            return false
        }

        sendConfigureAck(frame)
        return true
    }

    /**
     * Send the current Configure-Request.
     *
     * Retransmissions reuse the same Identifier (RFC 1661 §5.1): a fresh id per
     * attempt makes the peer's Ack ambiguous. A new id is only taken when the
     * option set actually changes (after a Nak or Reject).
     */
    private fun sendConfigureRequest() {
        if (requestId == null) {
            requestId = nextId()
        }
        val data = buildOptions(options)
        sendFrame(buildPppFrame(PppConstants.PROTOCOL_LCP, PppConstants.CODE_CONFIGURE_REQUEST, requestId!!, data))
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
     * Apply the server's suggested values from a Configure-Nak.
     *
     * The caller resends the request afterwards. Auth-Protocol is not handled
     * here: we no longer offer that option, so the server has nothing to Nak —
     * its own demand arrives in [handleServerConfigureRequest] instead.
     */
    private fun handleConfigureNak(frame: PppFrame) {
        for (opt in parseOptions(frame.data)) {
            when (opt.type) {
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
                    // A Nak on Magic-Number means it collided with the peer's.
                    // RFC 1661 §6.4 says to pick a NEW random number — adopting
                    // the value the peer suggested would just collide again.
                    magicNumber = SecureRandom().nextInt()
                    options.removeAll { it.type == PppConstants.OPTION_MAGIC_NUMBER }
                    options.add(PppOption(PppConstants.OPTION_MAGIC_NUMBER, intToBytes(magicNumber)))
                }
            }
        }
    }

    /**
     * Remove rejected options from a Configure-Reject; the caller then resends.
     */
    private fun handleConfigureReject(frame: PppFrame) {
        for (opt in parseOptions(frame.data)) {
            when (opt.type) {
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