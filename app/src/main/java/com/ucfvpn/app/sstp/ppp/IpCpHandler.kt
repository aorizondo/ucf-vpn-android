package com.ucfvpn.app.sstp.ppp

import kotlinx.coroutines.channels.Channel
import timber.log.Timber

/**
 * IPCP (Internet Protocol Control Protocol) handler (RFC 1877).
 *
 * Sends Configure-Request with IP-Address=0.0.0.0, Primary-DNS=0.0.0.0 and
 * Secondary-DNS=0.0.0.0, applies the server's suggested values from
 * Configure-Nak, and completes once the server sends Configure-Ack.
 *
 * @param sendFrame delivers raw PPP frames to the SSTP tunnel
 * @param onIpCpSuccess invoked with (localIp, dns1, dns2, gateway) when IPCP opens;
 * gateway is the peer's address, taken from the server's own Configure-Request
 * @param retransmitMs interval between Configure-Request retransmissions
 * @param maxAttempts maximum retransmissions before giving up (total sends = maxAttempts + 1)
 */
class IpCpHandler(
    private val sendFrame: (ByteArray) -> Unit,
    private val onIpCpSuccess: (localIp: String, dns1: String, dns2: String, gateway: String) -> Unit,
    private val retransmitMs: Long = PppConstants.RETRANSMIT_MS,
    private val maxAttempts: Int = PppConstants.MAX_ATTEMPTS
) {
    private val mailbox = Channel<PppFrame>(Channel.UNLIMITED)

    private var localIp: ByteArray = ByteArray(4)
    private var dns1: ByteArray = ByteArray(4)
    private var dns2: ByteArray = ByteArray(4)

    /** Peer (gateway) address, learned from the server's own Configure-Request. */
    private var peerIp: ByteArray = ByteArray(4)

    private var idCounter = 0

    /** Identifier of the in-flight Configure-Request; see [LcpHandler.requestId]. */
    private var requestId: Int? = null

    /**
     * Negotiate the IPCP link.
     *
     * @return the assigned local IP and DNS servers
     * @throws PppNegotiationException on timeout
     */
    suspend fun negotiate(): IpcpResult {
        // As in LCP, `attempts` counts elapsed timeouts only.
        sendConfigureRequest()
        var attempts = 1

        while (true) {
            val frame = mailbox.receiveFrame(retransmitMs)

            if (frame == null) {
                if (attempts > maxAttempts) {
                    throw PppNegotiationException(
                        "IPCP negotiation failed: no response after ${maxAttempts + 1} attempts"
                    )
                }
                sendConfigureRequest() // same Identifier — retransmission
                attempts++
                continue
            }

            when (frame.code) {
                PppConstants.CODE_CONFIGURE_ACK -> {
                    if (frame.id != requestId) {
                        Timber.d("IPCP: stale Configure-Ack id=${frame.id} (expected $requestId)")
                        continue
                    }
                    val result = IpcpResult(ipToString(localIp), ipToString(dns1), ipToString(dns2))
                    Timber.d("IPCP: link OPENED (ip=%s dns1=%s dns2=%s)", result.localIp, result.dns1, result.dns2)
                    onIpCpSuccess(result.localIp, result.dns1, result.dns2, ipToString(peerIp))
                    return result
                }
                PppConstants.CODE_CONFIGURE_NAK -> {
                    applyNak(frame)
                    requestId = null // options changed → new Identifier
                    sendConfigureRequest()
                    attempts = 1
                }
                PppConstants.CODE_CONFIGURE_REJECT -> {
                    applyReject(frame)
                    requestId = null
                    sendConfigureRequest()
                    attempts = 1
                }
                // IPCP is bidirectional: the server sends its OWN Configure-Request
                // carrying its IP address, and many servers will not open the link
                // until it is acknowledged. Ignoring it stalled us until timeout.
                PppConstants.CODE_CONFIGURE_REQUEST -> handleServerConfigureRequest(frame)
                else -> Timber.d("IPCP: ignoring code ${frame.code}")
            }
        }
    }

    /**
     * Acknowledge the server's Configure-Request, recording its IP-Address
     * option as the peer address (the tunnel's gateway).
     */
    private fun handleServerConfigureRequest(frame: PppFrame) {
        for (opt in parseOptions(frame.data)) {
            if (opt.type == PppConstants.OPTION_IPCP_IP && opt.data.size == 4) {
                peerIp = opt.data
                Timber.d("IPCP: peer address is %s", ipToString(peerIp))
            }
        }
        sendFrame(
            buildPppFrame(
                PppConstants.PROTOCOL_IPCP,
                PppConstants.CODE_CONFIGURE_ACK,
                frame.id,
                frame.data
            )
        )
    }

    /**
     * Feed an incoming IPCP frame.
     */
    fun handleFrame(frame: PppFrame) {
        if (frame.protocol == PppConstants.PROTOCOL_IPCP) {
            mailbox.trySend(frame)
        }
    }

    /**
     * Release the mailbox.
     */
    fun close() {
        mailbox.close()
    }

    private fun sendConfigureRequest() {
        if (requestId == null) {
            requestId = nextId()
        }
        val options = listOf(
            PppOption(PppConstants.OPTION_IPCP_IP, localIp),
            PppOption(PppConstants.OPTION_IPCP_DNS_PRIMARY, dns1),
            PppOption(PppConstants.OPTION_IPCP_DNS_SECONDARY, dns2)
        )
        sendFrame(buildPppFrame(PppConstants.PROTOCOL_IPCP, PppConstants.CODE_CONFIGURE_REQUEST, requestId!!, buildOptions(options)))
    }

    /**
     * Apply the server's suggested values from a Configure-Nak and resend.
     */
    private fun applyNak(frame: PppFrame) {
        for (opt in parseOptions(frame.data)) {
            when (opt.type) {
                PppConstants.OPTION_IPCP_IP -> if (opt.data.size == 4) localIp = opt.data
                PppConstants.OPTION_IPCP_DNS_PRIMARY -> if (opt.data.size == 4) dns1 = opt.data
                PppConstants.OPTION_IPCP_DNS_SECONDARY -> if (opt.data.size == 4) dns2 = opt.data
            }
        }
    }

    /**
     * Remove rejected options from a Configure-Reject and resend.
     */
    private fun applyReject(frame: PppFrame) {
        for (opt in parseOptions(frame.data)) {
            when (opt.type) {
                PppConstants.OPTION_IPCP_IP -> localIp = ByteArray(4)
                PppConstants.OPTION_IPCP_DNS_PRIMARY -> dns1 = ByteArray(4)
                PppConstants.OPTION_IPCP_DNS_SECONDARY -> dns2 = ByteArray(4)
            }
        }
    }

    private fun nextId(): Int {
        idCounter = (idCounter + 1) and 0xFF
        return idCounter
    }
}