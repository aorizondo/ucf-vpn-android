package com.ucfvpn.app.sstp.client

import com.ucfvpn.app.sstp.protocol.SstpControlPacket
import com.ucfvpn.app.sstp.protocol.SstpMessageType
import com.ucfvpn.app.sstp.protocol.SstpPacket
import com.ucfvpn.app.sstp.protocol.SstpProtocol
import com.ucfvpn.app.sstp.protocol.createCallConnected
import com.ucfvpn.app.sstp.protocol.createEchoRequest
import com.ucfvpn.app.sstp.protocol.createPppDataPacket
import com.ucfvpn.app.sstp.ppp.PppEvent
import com.ucfvpn.app.sstp.ppp.PppStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Complete SSTP tunnel implementation.
 * Manages the SSL/TLS connection, SSTP handshake, and PPP frame transport.
 *
 * @param username PPP username used for PAP authentication
 * @param password PPP password used for PAP authentication
 */
class SstpTunnelImpl(
    private val username: String = "",
    private val password: String = "",
    /**
     * When true (legacy default) the TLS handshake accepts any certificate
     * (VERIFY_NONE). When false the system default trust manager is used.
     * Configurable at runtime via [configure].
     */
    private var ignoreSslErrors: Boolean = true,
    /**
     * Optional protector invoked on the TCP socket BEFORE it connects so the
     * SSTP traffic bypasses the VPN tunnel. Configurable at runtime via [configure].
     */
    private var socketProtector: SocketProtector? = null
) : SstpTunnel {

    companion object {
        private const val SERVER = "npv.ucf.edu.cu"
        private const val PORT = 443
        private const val KEEPALIVE_INTERVAL_MS = 30_000L

        /** CERT_HASH_PROTOCOL_SHA1 (MS-SSTP 2.2.9). SHA256 would be 0x02. */
        private const val CERT_HASH_PROTOCOL_SHA1: Byte = 0x01
    }

    private var handshake: SstpHandshake? = null
    private var serverAddress: String = SERVER
    private var serverPort: Int = PORT

    private var state: SstpState = SstpState.DISCONNECTED
        set(value) {
            if (field != value) {
                Timber.d("SstpState transition: $field -> $value")
                field = value
                callbacks?.onStateChanged(value)
                onStateChanged?.invoke(value)
            }
        }

    private var callbacks: SstpTunnelCallbacks? = null
    override var onPppFrameReceived: ((ByteArray) -> Unit)? = null
    override var onStateChanged: ((SstpState) -> Unit)? = null
    override var onPppEvent: ((PppEvent) -> Unit)? = null

    override var localAddress: String? = null
        private set

    override val isConnected: Boolean
        get() = state == SstpState.CONNECTED

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var receiveJob: Job? = null
    private var keepaliveJob: Job? = null

    // PPP negotiation stack (LCP → PAP → IPCP)
    private var pppStack: PppStack? = null

    // MPPE keys for crypto binding (set after PPP auth)
    private var sendKey: ByteArray? = null
    private var recvKey: ByteArray? = null
    private var masterKey: ByteArray? = null

    /**
     * Configure TLS certificate validation and socket protection.
     *
     * Called by the orchestrator before [connect] so the flag and protector
     * flow from the app configuration down to the handshake.
     *
     * @param ignoreSslErrors when true the TLS handshake accepts any certificate
     *   (VERIFY_NONE, legacy default); when false the system trust manager is used.
     * @param socketProtector invoked on the TCP socket BEFORE it connects so the
     *   SSTP traffic bypasses the VPN tunnel (prevents a traffic loop).
     */
    fun configure(ignoreSslErrors: Boolean, socketProtector: SocketProtector?) {
        this.ignoreSslErrors = ignoreSslErrors
        this.socketProtector = socketProtector
    }

    /**
     * Connect callbacks.
     */
    override fun setCallbacks(callbacks: SstpTunnelCallbacks) {
        this.callbacks = callbacks
    }

    /**
     * Connect to the SSTP server.
     */
    override fun connect(server: String, port: Int) {
        if (state == SstpState.CONNECTING || state == SstpState.CONNECTED) {
            Timber.w("Already connected or connecting")
            return
        }

        serverAddress = server
        serverPort = port
        state = SstpState.CONNECTING

        scope.launch {
            try {
                performConnect()
            } catch (e: Exception) {
                Timber.e(e, "Connection failed")
                state = SstpState.ERROR
                callbacks?.onError(e)
            }
        }
    }

    private suspend fun performConnect() {
        withContext(Dispatchers.IO) {
            Timber.d("Connecting to SSTP server $serverAddress:$serverPort")

            // Create handshake and connect
            handshake = SstpHandshake(
                serverAddress,
                serverPort,
                username,
                password,
                ignoreSslErrors = ignoreSslErrors,
                socketProtector = socketProtector
            ).also { hs ->
                hs.connect()
            }

            state = SstpState.CONNECTED

            // Start receive loop
            startReceiveLoop()

            // Negotiate PPP (LCP → PAP → IPCP)
            val stack = PppStack().apply {
                sendFrame = { frame -> sendPppFrame(frame) }
                // Forward real negotiation milestones so callers can report the
                // phase the link is actually in.
                onEvent = { event -> onPppEvent?.invoke(event) }
            }
            pppStack = stack

            val result = stack.negotiate(username, password)
            result.fold(
                onSuccess = { ppp ->
                    Timber.d(
                        "PPP negotiation complete: localIp=%s dns1=%s dns2=%s gateway=%s",
                        ppp.localIp, ppp.dns1, ppp.dns2, ppp.gateway
                    )
                    localAddress = ppp.localIp
                    // Send CALL_CONNECTED with crypto binding after PPP auth succeeds
                    onPppAuthSuccess(null, null, null)
                },
                onFailure = { e ->
                    Timber.e(e, "PPP negotiation failed")
                    throw e
                }
            )

            // Start keepalive
            startKeepalive()
        }
    }

    /**
     * Send CALL_CONNECTED with crypto binding.
     * Based on Python reference: send_call_connected()
     */
    private fun sendCallConnected(sendKey: ByteArray?, recvKey: ByteArray?) {
        val hs = handshake ?: return

        val nonce = hs.nonce
        if (nonce == null) {
            Timber.w("No nonce available, sending generic CALL_CONNECTED")
            val packet = createCallConnected()
            hs.send(packet)
            return
        }

        Timber.d("Calculating Precision Crypto Binding...")

        // 1. Export MK from TLS session
        val mk = hs.exportKeyingMaterial("SSTP Key Binding", 32)

        // 2. HLAK = SendKey + RecvKey (16 bytes each)
        val hlak = if (sendKey != null && recvKey != null && sendKey.size >= 16 && recvKey.size >= 16) {
            sendKey.copyOf(16) + recvKey.copyOf(16)
        } else {
            Timber.d("Using null HLAK (no MPPE keys)")
            ByteArray(32) { 0 }
        }

        // 3. Derive CMK: HMAC-SHA1(MK, "SSTP inner method derived CMK\0" + HLAK)
        val cmkData = "SSTP inner method derived CMK\u0000".toByteArray() + hlak
        val cmk = hmacSha1(mk, cmkData)
        Timber.d("Crypto binding keys derived (MK/HLAK/CMK)")

        // 4. Certificate Hash (SHA1, padded to 32 bytes)
        val cert = hs.getPeerCertificate()
        val certHash = if (cert != null) {
            val digest = MessageDigest.getInstance("SHA-1")
            val rawHash = digest.digest(cert.encoded)
            rawHash + ByteArray(12) // Pad to 32 bytes
        } else {
            ByteArray(32) { 0 }
        }

        // 5. Build packet with zeroed MAC (32 bytes)
        val sstpHeader = byteArrayOf(0x10, 0x01, 0x00, 0x70.toByte())
        val controlHeader = byteArrayOf(0x00, 0x04, 0x00, 0x01)
        val attrHeader = byteArrayOf(0x00, 0x04, 0x00, 0x68)

        // Attribute value layout (MS-SSTP 2.2.9): Reserved(3) +
        // HashProtocolBitmask(1) + Nonce(32) + CertHash(32) + CompoundMAC(32).
        // The bitmask byte was being left at 0x00, which tells the server
        // "no hash protocol" instead of SHA1.
        val attrValuePrefix = byteArrayOf(0x00, 0x00, 0x00, CERT_HASH_PROTOCOL_SHA1) + nonce + certHash

        // 6. Compound MAC (HMAC-SHA1 over full packet with MAC field zeroed)
        val packetToSign = sstpHeader + controlHeader + attrHeader + attrValuePrefix + ByteArray(32)
        val macRaw = hmacSha1(cmk, packetToSign)
        val mac = macRaw + ByteArray(12) // Pad to 32 bytes

        // 7. Final packet
        val callConnected = sstpHeader + controlHeader + attrHeader + attrValuePrefix + mac
        Timber.d("Sending CALL_CONNECTED with crypto binding MAC")
        hs.send(callConnected)
    }

    /**
     * Called when PPP authentication succeeds with MPPE keys.
     * This enables crypto binding with the actual session keys.
     */
    fun onPppAuthSuccess(sendKey: ByteArray?, recvKey: ByteArray?, masterKey: ByteArray?) {
        this.sendKey = sendKey
        this.recvKey = recvKey
        this.masterKey = masterKey

        // Re-send CALL_CONNECTED with crypto binding if connected
        if (state == SstpState.CONNECTED) {
            scope.launch(Dispatchers.IO) {
                sendCallConnected(sendKey, recvKey)
            }
        }
    }

    /**
     * Start the receive loop in a coroutine.
     */
    private fun startReceiveLoop() {
        receiveJob = scope.launch {
            val hs = handshake ?: return@launch

            try {
                while (isActive && state == SstpState.CONNECTED) {
                    try {
                        val packet = hs.receiveSstpPacket()
                        handlePacket(packet)
                    } catch (e: Exception) {
                        if (isActive && state == SstpState.CONNECTED) {
                            Timber.e(e, "Error in receive loop")
                            break
                        }
                    }
                }
            } finally {
                if (state == SstpState.CONNECTED) {
                    // Unexpected disconnect
                    Timber.w("Receive loop ended, connection lost")
                    state = SstpState.ERROR
                }
            }
        }
    }

    /**
     * Handle received SSTP packet.
     */
    private fun handlePacket(packet: SstpPacket) {
        if (packet.isControl) {
            handleControlPacket(packet)
        } else {
            // Data packet - contains PPP frame
            Timber.d("Received PPP data packet (${packet.data.size} bytes)")
            pppStack?.handleFrame(packet.data)
            onPppFrameReceived?.invoke(packet.data)
            callbacks?.onPppFrameReceived(packet.data)
        }
    }

    /**
     * Handle SSTP control packet.
     */
    private fun handleControlPacket(packet: SstpPacket) {
        try {
            val control = SstpControlPacket.unpack(packet.data)
            Timber.d("Received control packet: ${control.messageType.name}")

            when (control.messageType) {
                SstpMessageType.CALL_DISCONNECT -> {
                    Timber.d("Received CALL_DISCONNECT")
                    disconnect()
                }
                SstpMessageType.CALL_DISCONNECT_ACK -> {
                    Timber.d("Received CALL_DISCONNECT_ACK")
                }
                SstpMessageType.ECHO_REQUEST -> {
                    Timber.d("Received ECHO_REQUEST, sending ECHO_RESPONSE")
                    sendEchoResponse()
                }
                SstpMessageType.ECHO_RESPONSE -> {
                    Timber.d("Received ECHO_RESPONSE")
                }
                else -> {
                    Timber.d("Received control message: ${control.messageType.name}")
                }
            }

            // Log attributes for debugging
            for ((attrId, attrValue) in control.attributes) {
                Timber.d("  Attribute $attrId: ${attrValue.toHexString()}")
            }
        } catch (e: Exception) {
            Timber.w(e, "Error parsing control packet")
        }
    }

    /**
     * Send ECHO_RESPONSE for keepalive.
     */
    private fun sendEchoResponse() {
        val hs = handshake ?: return
        try {
            val echoResponse = createSstpControlPacket(SstpMessageType.ECHO_RESPONSE, emptyList())
            hs.send(echoResponse)
        } catch (e: Exception) {
            Timber.w(e, "Failed to send ECHO_RESPONSE")
        }
    }

    /**
     * Create an SSTP control packet.
     */
    private fun createSstpControlPacket(
        messageType: SstpMessageType,
        attributes: List<Pair<Int, ByteArray>>
    ): ByteArray {
        val attrDataBuilder = java.io.ByteArrayOutputStream()
        for ((attrId, attrValue) in attributes) {
            val attrLen = 4 + attrValue.size
            val attrBuf = ByteBuffer.allocate(attrLen).order(ByteOrder.BIG_ENDIAN)
            attrBuf.put(0x01.toByte()) // Reserved with M=1
            attrBuf.put(attrId.toByte())
            attrBuf.putShort(attrLen.toShort())
            attrBuf.put(attrValue)
            attrDataBuilder.write(attrBuf.array())
        }

        val headerBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        headerBuf.putShort(messageType.value.toShort())
        headerBuf.putShort(attributes.size.toShort())

        val ctrlData = headerBuf.array() + attrDataBuilder.toByteArray()
        val packetLen = SstpProtocol.HEADER_SIZE + ctrlData.size

        val packetBuf = ByteBuffer.allocate(packetLen).order(ByteOrder.BIG_ENDIAN)
        packetBuf.put(0x10.toByte()) // Version 1
        packetBuf.put(0x01.toByte()) // Control
        packetBuf.putShort(packetLen.toShort())
        packetBuf.put(ctrlData)

        return packetBuf.array()
    }

    /**
     * Start the keepalive coroutine.
     */
    private fun startKeepalive() {
        keepaliveJob = scope.launch {
            while (isActive && state == SstpState.CONNECTED) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (isActive && state == SstpState.CONNECTED) {
                    sendEchoRequest()
                }
            }
        }
    }

    /**
     * Send ECHO_REQUEST keepalive packet.
     */
    private fun sendEchoRequest() {
        val hs = handshake ?: return
        try {
            val packet = createEchoRequest()
            hs.send(packet)
            Timber.d("Sent ECHO_REQUEST keepalive")
        } catch (e: Exception) {
            Timber.w(e, "Failed to send ECHO_REQUEST")
        }
    }

    /**
     * Disconnect from the SSTP server.
     */
    override fun disconnect() {
        if (state == SstpState.DISCONNECTED || state == SstpState.DISCONNECTING) {
            return
        }

        state = SstpState.DISCONNECTING

        scope.launch {
            try {
                // Stop keepalive and receive loop
                keepaliveJob?.cancel()
                receiveJob?.cancel()

                // Stop PPP negotiation stack
                pppStack?.close()
                pppStack = null

                // Send CALL_DISCONNECT
                withContext(Dispatchers.IO) {
                    try {
                        val hs = handshake
                        if (hs != null) {
                            val disconnectPacket = createSstpControlPacket(
                                SstpMessageType.CALL_DISCONNECT,
                                listOf(0x01 to ByteArray(0)) // NO_ERROR attribute
                            )
                            hs.send(disconnectPacket)
                            Timber.d("Sent CALL_DISCONNECT")
                        }
                    } catch (e: Exception) {
                        Timber.d(e, "Error sending CALL_DISCONNECT")
                    }

                    // Close handshake
                    handshake?.close()
                    handshake = null
                }

                state = SstpState.DISCONNECTED
            } catch (e: Exception) {
                Timber.e(e, "Error during disconnect")
                state = SstpState.ERROR
            }
        }
    }

    /**
     * Send a PPP frame through the SSTP tunnel.
     */
    override fun send(pppFrame: ByteArray) {
        val hs = handshake
        if (hs == null || state != SstpState.CONNECTED) {
            Timber.w("Cannot send: not connected")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val packet = createPppDataPacket(pppFrame)
                hs.send(packet)
                Timber.d("Sent PPP frame (${pppFrame.size} bytes -> SSTP packet ${packet.size} bytes)")
            } catch (e: Exception) {
                Timber.e(e, "Error sending PPP frame")
                callbacks?.onError(e)
            }
        }
    }

    /**
     * Send a raw PPP frame synchronously through the SSTP tunnel.
     * Used by the PPP stack during LCP/PAP/IPCP negotiation.
     */
    private fun sendPppFrame(frame: ByteArray) {
        val hs = handshake ?: return
        try {
            val packet = createPppDataPacket(frame)
            hs.send(packet)
        } catch (e: Exception) {
            Timber.e(e, "Error sending PPP frame")
        }
    }

    /**
     * HMAC-SHA1 implementation.
     */
    private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data)
    }

    /**
     * ByteArray extension to convert to hex string.
     */
    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }
}
