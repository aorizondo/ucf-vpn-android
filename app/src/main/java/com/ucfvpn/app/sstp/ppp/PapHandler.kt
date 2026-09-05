package com.ucfvpn.app.sstp.ppp

import kotlinx.coroutines.channels.Channel
import timber.log.Timber

/**
 * PAP (Password Authentication Protocol) handler (RFC 1334).
 *
 * Sends an Authenticate-Request with the username/password and waits for the
 * server's Authenticate-Ack (success) or Authenticate-Nak (failure). The
 * request is retransmitted on timeout.
 *
 * @param sendFrame delivers raw PPP frames to the SSTP tunnel
 * @param onPapSuccess invoked when the server acknowledges the credentials
 * @param onPapFailure invoked with the server's message when authentication is rejected
 * @param retransmitMs interval between Authenticate-Request retransmissions
 * @param maxAttempts maximum retransmissions before giving up (total sends = maxAttempts + 1)
 */
class PapHandler(
    private val sendFrame: (ByteArray) -> Unit,
    private val onPapSuccess: () -> Unit,
    private val onPapFailure: (String) -> Unit,
    private val retransmitMs: Long = PppConstants.RETRANSMIT_MS,
    private val maxAttempts: Int = PppConstants.MAX_ATTEMPTS
) {
    private val mailbox = Channel<PppFrame>(Channel.UNLIMITED)
    private var idCounter = 0

    /**
     * Authenticate with the given credentials.
     *
     * @return the server's success message
     * @throws PppNegotiationException on timeout or when the server rejects the credentials
     */
    suspend fun authenticate(username: String, password: String): PapResult {
        var attempts = 0
        while (true) {
            if (attempts > maxAttempts) {
                throw PppNegotiationException(
                    "PAP authentication failed: no response after ${maxAttempts + 1} attempts"
                )
            }
            sendAuthenticateRequest(username, password)
            attempts++

            val frame = mailbox.receiveFrame(retransmitMs) ?: continue

            when (frame.code) {
                PppConstants.PAP_AUTHENTICATE_ACK -> {
                    val message = String(frame.data, Charsets.UTF_8)
                    Timber.d("PAP: authentication accepted")
                    onPapSuccess()
                    return PapResult(true, message)
                }
                PppConstants.PAP_AUTHENTICATE_NAK -> {
                    val message = String(frame.data, Charsets.UTF_8)
                    Timber.w("PAP: authentication rejected: $message")
                    onPapFailure(message)
                    throw PppNegotiationException("PAP authentication failed: $message")
                }
                else -> Timber.d("PAP: ignoring code ${frame.code}")
            }
        }
    }

    /**
     * Feed an incoming PAP frame.
     */
    fun handleFrame(frame: PppFrame) {
        if (frame.protocol == PppConstants.PROTOCOL_PAP) {
            mailbox.trySend(frame)
        }
    }

    /**
     * Release the mailbox.
     */
    fun close() {
        mailbox.close()
    }

    /**
     * Build an Authenticate-Request: `[len(user)][user][len(pass)][pass]`.
     */
    private fun sendAuthenticateRequest(username: String, password: String) {
        val userBytes = username.toByteArray(Charsets.UTF_8)
        val passBytes = password.toByteArray(Charsets.UTF_8)
        val data = ByteArray(2 + userBytes.size + 2 + passBytes.size)
        var offset = 0
        data[offset++] = userBytes.size.toByte()
        userBytes.copyInto(data, offset)
        offset += userBytes.size
        data[offset++] = passBytes.size.toByte()
        passBytes.copyInto(data, offset)
        sendFrame(buildPppFrame(PppConstants.PROTOCOL_PAP, PppConstants.PAP_AUTHENTICATE_REQUEST, nextId(), data))
    }

    private fun nextId(): Int {
        idCounter = (idCounter + 1) and 0xFF
        return idCounter
    }
}