package com.ucfvpn.app.sstp.client

import timber.log.Timber

/**
 * SSTP connection state machine.
 */
enum class SstpState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

/**
 * Callback interface for SSTP tunnel events.
 */
interface SstpTunnelCallbacks {
    /**
     * Called when a PPP frame is received from the server.
     */
    fun onPppFrameReceived(frame: ByteArray)

    /**
     * Called when the tunnel state changes.
     */
    fun onStateChanged(state: SstpState)

    /**
     * Called when an error occurs.
     */
    fun onError(error: Throwable)
}

/**
 * Interface for SSTP tunnel operations.
 * The tunnel transports PPP frames over an SSL/TLS connection to the SSTP server.
 */
interface SstpTunnel {
    /**
     * Connect to the SSTP server.
     * @param server Server hostname or IP
     * @param port Server port (default 443)
     */
    fun connect(server: String, port: Int = 443)

    /**
     * Disconnect from the SSTP server.
     */
    fun disconnect()

    /**
     * Send a PPP frame through the tunnel.
     * @param pppFrame Raw PPP frame data
     */
    fun send(pppFrame: ByteArray)

    /**
     * Callback for received PPP frames.
     */
    var onPppFrameReceived: ((ByteArray) -> Unit)?

    /**
     * Callback for state changes.
     */
    var onStateChanged: ((SstpState) -> Unit)?

    /**
     * Callback for PPP negotiation milestones (LCP opened, auth accepted, IP
     * assigned). Lets callers report the phase the negotiation is ACTUALLY in,
     * instead of guessing.
     */
    var onPppEvent: ((com.ucfvpn.app.sstp.ppp.PppEvent) -> Unit)?

    /**
     * Receives raw IPv4 packets arriving through the tunnel (PPP protocol
     * 0x0021) — the tunnel's actual payload, as opposed to its negotiation.
     */
    var onIpPacket: ((ByteArray) -> Unit)?

    /**
     * Local IP address assigned via PPP, or null if not connected.
     */
    val localAddress: String?

    /**
     * Whether the tunnel is currently connected.
     */
    val isConnected: Boolean

    /**
     * Set callbacks for tunnel events.
     */
    fun setCallbacks(callbacks: SstpTunnelCallbacks)
}
