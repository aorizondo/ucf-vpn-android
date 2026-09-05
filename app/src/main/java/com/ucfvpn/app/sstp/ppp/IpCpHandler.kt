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
 * in PPP the peer is the gateway, so gateway == localIp
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
    private var idCounter = 0

    /**
     * Negotiate the IPCP link.
     *
     * @return the assigned local IP and DNS servers
     * @throws PppNegotiationException on timeout
     */
    suspend fun negotiate(): IpcpResult {
        var attempts = 0
        while (true) {
            if (attempts > maxAttempts) {
                throw PppNegotiationException(
                    "IPCP negotiation failed: no response after ${maxAttempts + 1} attempts"
                )
            }
            sendConfigureRequest()
            attempts++

            val frame = mailbox.receiveFrame(retransmitMs) ?: continue

            when (frame.code) {
                PppConstants.CODE_CONFIGURE_ACK -> {
                    val result = IpcpResult(ipToString(localIp), ipToString(dns1), ipToString(dns2))
                    Timber.d("IPCP: link OPENED (ip=%s dns1=%s dns2=%s)", result.localIp, result.dns1, result.dns2)
                    onIpCpSuccess(result.localIp, result.dns1, result.dns2, result.localIp)
                    return result
                }
                PppConstants.CODE_CONFIGURE_NAK -> applyNak(frame)
                PppConstants.CODE_CONFIGURE_REJECT -> applyReject(frame)
                else -> Timber.d("IPCP: ignoring code ${frame.code}")
            }
        }
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
        val options = listOf(
            PppOption(PppConstants.OPTION_IPCP_IP, localIp),
            PppOption(PppConstants.OPTION_IPCP_DNS_PRIMARY, dns1),
            PppOption(PppConstants.OPTION_IPCP_DNS_SECONDARY, dns2)
        )
        sendFrame(buildPppFrame(PppConstants.PROTOCOL_IPCP, PppConstants.CODE_CONFIGURE_REQUEST, nextId(), buildOptions(options)))
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