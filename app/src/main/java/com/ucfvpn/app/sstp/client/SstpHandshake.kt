package com.ucfvpn.app.sstp.client

import com.ucfvpn.app.sstp.protocol.SstpControlPacket
import com.ucfvpn.app.sstp.protocol.SstpMessageType
import com.ucfvpn.app.sstp.protocol.SstpPacket
import com.ucfvpn.app.sstp.protocol.SstpProtocol
import com.ucfvpn.app.sstp.protocol.createCallConnectRequest
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Manages SSTP connection handshake including SSL/TLS, HTTP, and SSTP negotiation.
 * Based on the Python reference implementation in solverius/sstp/sstp/handshake.py
 */
class SstpHandshake(
    private val server: String,
    private val port: Int = 443,
    private val username: String = "",
    private val password: String = "",
    /**
     * When true (legacy default) the TLS handshake accepts any certificate
     * (VERIFY_NONE). When false the system default trust manager is used,
     * enforcing real certificate validation.
     */
    private val ignoreSslErrors: Boolean = true,
    /**
     * Optional protector invoked on the TCP socket BEFORE it connects so the
     * SSTP traffic bypasses the VPN tunnel (prevents a traffic loop).
     */
    private val socketProtector: SocketProtector? = null
) {
    companion object {
        private const val SNI_HOST = "npv.ucf.edu.cu"
        private const val SSTP_PATH = "/sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/"
        private const val USER_AGENT = "SSTP-Client/1.0 (Windows NT 10.0; Win64; x64)"
        private const val CONTENT_LENGTH = "18446744073709551615" // 2^64-1
    }

    private var socket: Socket? = null
    private var sslSocket: SSLSocket? = null
    private var sslContext: SSLContext? = null

    // Nonce and certificate data for crypto binding
    var nonce: ByteArray? = null
        private set
    var hashId: Int = 0x01 // Default to SHA256
        private set

    /**
     * Create an SSL context for the TLS handshake.
     *
     * When [ignoreSslErrors] is true the context accepts all certificates
     * (VERIFY_NONE, legacy default). When false the system default trust
     * manager is used, enforcing real certificate validation.
     */
    fun createSslContext(): SSLContext {
        if (!ignoreSslErrors) {
            // Real certificate validation: init with the system default
            // trust managers (null trustManagers → platform defaults).
            sslContext = SSLContext.getInstance("TLS").apply {
                init(null, null, SecureRandom())
            }
            Timber.d("SSL context created with system default trust manager")
            return sslContext!!
        }

        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // No-op: accept all client certificates
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // No-op: accept all server certificates
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val trustManagers = arrayOf<TrustManager>(trustAll)
        val secureRandom = SecureRandom()

        sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustManagers, secureRandom)
        }

        Timber.d("SSL context created with VERIFY_NONE trust manager")
        return sslContext!!
    }

    /**
     * Perform full TCP + SSL + HTTP + SSTP handshake.
     * @return The SSL socket after successful handshake
     */
    fun connect(): SSLSocket {
        Timber.d("Starting SSTP handshake to $server:$port")

        // Step 1: TCP connection
        tcpConnect()

        // Step 2: SSL/TLS handshake
        sslConnect()

        // Step 3 & 4: HTTP and SSTP negotiation
        performHandshake()

        Timber.d("SSTP handshake completed successfully")
        return sslSocket!!
    }

    private fun tcpConnect() {
        Timber.d("Establishing TCP connection to $server:$port")
        val rawSocket = Socket()

        // Protect the socket BEFORE connecting so its traffic bypasses the
        // VPN tunnel (prevents the VPN → SSTP → VPN traffic loop).
        //
        // The bind() is required, not cosmetic: a freshly constructed Socket has
        // no underlying file descriptor yet, and VpnService.protect() needs one.
        // Binding to port 0 forces the fd to be created while leaving the port
        // choice to the OS. This is the order documented in VpnGatewayService.
        socketProtector?.let { protector ->
            rawSocket.bind(InetSocketAddress(0))
            if (!protector.protect(rawSocket)) {
                // Connecting unprotected while the VPN is up would route the SSTP
                // socket back into its own tunnel. Fail loudly instead.
                rawSocket.close()
                throw IOException(
                    "VpnService.protect() failed for $server:$port — refusing to " +
                        "connect unprotected (would create a VPN traffic loop)"
                )
            }
        }

        rawSocket.connect(InetSocketAddress(server, port))
        rawSocket.soTimeout = 10000
        socket = rawSocket
        Timber.d("TCP connection established")
    }

    private fun sslConnect() {
        Timber.d("Starting SSL/TLS handshake")

        val context = createSslContext()
        val factory = context.socketFactory

        sslSocket = factory.createSocket(socket, server, port, true) as SSLSocket
        val ssl = sslSocket!!
        with(ssl) {
            enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            useClientMode = true

            // Set SNI hostname via SSLParameters
            val params = sslParameters
            params.serverNames = listOf(javax.net.ssl.SNIHostName(SNI_HOST))
            sslParameters = params

            // Perform handshake
            ssl.startHandshake()

            Timber.d("SSL/TLS handshake complete. Cipher: ${ssl.session.cipherSuite}")
        }
    }

    private fun performHandshake() {
        val correlationId = UUID.randomUUID().toString().uppercase()

        // Build HTTP SSTP_DUPLEX_POST request
        val httpRequest = buildHttpRequest(correlationId)

        // Step 1: Send HTTP request
        Timber.d("Sending SSTP_DUPLEX_POST request...")
        sslSocket?.outputStream?.write(httpRequest.toByteArray(Charsets.UTF_8))
        sslSocket?.outputStream?.flush()

        // Step 2: Read HTTP 200 BEFORE sending CALL_CONNECT_REQUEST (MS-SSTP spec requirement)
        Timber.d("Waiting for HTTP 200 response...")
        val response = readHttpResponse()

        if (!response.contains("200")) {
            val errorMsg = "HTTP handshake failed: $response"
            Timber.e(errorMsg)
            throw ConnectionError(errorMsg)
        }
        Timber.d("HTTP tunnel established. Sending CALL_CONNECT_REQUEST...")

        // Step 3: Send CALL_CONNECT_REQUEST after HTTP 200
        val callRequest = createCallConnectRequest()
        Timber.d("Sending SSTP CALL_CONNECT_REQUEST (${callRequest.size} bytes)")
        sslSocket?.outputStream?.write(callRequest)
        sslSocket?.outputStream?.flush()

        // Step 4: Receive and parse CALL_CONNECT_ACK
        receiveCallConnectAck()
    }

    private fun buildHttpRequest(correlationId: String): String {
        return buildString {
            append("SSTP_DUPLEX_POST $SSTP_PATH HTTP/1.1\r\n")
            append("Host: $SNI_HOST\r\n")
            append("SSTPCORRELATIONID: {$correlationId}\r\n")
            append("Content-Length: $CONTENT_LENGTH\r\n")
            append("User-Agent: $USER_AGENT\r\n")
            append("\r\n")
        }
    }

    private fun readHttpResponse(): String {
        val response = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var headersComplete = false

        while (!headersComplete) {
            val bytesRead = sslSocket?.inputStream?.read(buffer) ?: -1
            if (bytesRead == -1) {
                throw ConnectionError("Connection closed during HTTP handshake")
            }
            response.write(buffer, 0, bytesRead)

            val responseBytes = response.toByteArray()
            if (findHeaderEnd(responseBytes) != -1) {
                headersComplete = true
            }
        }

        return String(response.toByteArray(), Charsets.UTF_8).replace("\r\n", "\\r\\n")
    }

    private fun findHeaderEnd(data: ByteArray): Int {
        // Look for \r\n\r\n sequence
        for (i in 0 until data.size - 3) {
            if (data[i] == 0x0D.toByte() && data[i + 1] == 0x0A.toByte() &&
                data[i + 2] == 0x0D.toByte() && data[i + 3] == 0x0A.toByte()) {
                return i + 4
            }
        }
        return -1
    }

    private fun receiveCallConnectAck() {
        val packet = receiveSstpPacket()
        if (!packet.isControl) {
            throw ConnectionError("Expected control packet, got data packet")
        }

        val control = SstpControlPacket.unpack(packet.data)
        Timber.d("Received control packet: ${control.messageType.name}")

        when (control.messageType) {
            SstpMessageType.CALL_CONNECT_ACK -> {
                Timber.d("Received CALL_CONNECT_ACK")
                for ((attrId, attrValue) in control.attributes) {
                    if (attrId == 0x04) { // CRYPTO_BINDING
                        if (attrValue.size >= 36) {
                            hashId = attrValue[3].toInt() and 0xFF
                            nonce = attrValue.copyOfRange(4, 36)
                            Timber.d("Extracted Nonce and HashID ($hashId) for Crypto Binding")
                        }
                    }
                }
                if (nonce == null) {
                    // No crypto binding requested, generate null nonce
                    nonce = ByteArray(32) { 0 }
                    Timber.d("No crypto binding in response, using null nonce")
                }
            }
            SstpMessageType.CALL_CONNECT_NAK -> {
                throw ConnectionError("Server rejected connection (CALL_CONNECT_NAK)")
            }
            else -> {
                Timber.w("Unexpected message type: ${control.messageType}")
            }
        }
    }

    /**
     * Receive a single SSTP packet from the SSL socket.
     */
    fun receiveSstpPacket(): SstpPacket {
        // Read 4-byte header
        val header = receiveExact(4)
        val packet = SstpPacket.unpack(header)

        // Read remaining data
        val remaining = packet.length - 4
        if (remaining > 0) {
            val packetData = receiveExact(remaining)
            return SstpPacket(packet.version, packet.isControl, packetData)
        }

        return packet
    }

    /**
     * Receive exactly n bytes from the SSL socket.
     */
    private fun receiveExact(n: Int): ByteArray {
        val data = ByteArray(n)
        var offset = 0

        while (offset < n) {
            val bytesRead = sslSocket?.inputStream?.read(data, offset, n - offset) ?: -1
            if (bytesRead == -1) {
                throw ConnectionError("Connection closed while reading")
            }
            offset += bytesRead
        }

        return data
    }

    /**
     * Send bytes through the SSL socket.
     */
    fun send(data: ByteArray) {
        sslSocket?.outputStream?.write(data)
        sslSocket?.outputStream?.flush()
    }

    /**
     * Get the SSL session for key material export.
     */
    fun getSslSession() = sslSocket?.session

    /**
     * Get the peer certificate for crypto binding.
     */
    fun getPeerCertificate(): X509Certificate? {
        return try {
            val session = sslSocket?.session
            val certs = session?.peerCertificates
            @Suppress("UNCHECKED_CAST")
            (certs?.firstOrNull() as? X509Certificate)
        } catch (e: Exception) {
            Timber.w(e, "Failed to get peer certificate")
            null
        }
    }

    /**
     * Export keying material from the TLS session.
     */
    fun exportKeyingMaterial(label: String, length: Int): ByteArray {
        return try {
            val session = sslSocket?.session
            // Use reflection to call exportKeyingMaterial since it's not in standard API
            val method = session?.javaClass?.getMethod(
                "exportKeyingMaterial",
                String::class.java,
                Array<ByteArray>::class.java,
                Int::class.javaPrimitiveType
            )
            @Suppress("UNCHECKED_CAST")
            method?.invoke(session, label, null, length) as? ByteArray ?: ByteArray(length)
        } catch (e: Exception) {
            Timber.w(e, "Failed to export keying material")
            ByteArray(length)
        }
    }

    /**
     * Close the connection.
     */
    fun close() {
        try {
            sslSocket?.close()
        } catch (e: Exception) {
            Timber.d(e, "Error closing SSL socket")
        }
        try {
            socket?.close()
        } catch (e: Exception) {
            Timber.d(e, "Error closing socket")
        }
        Timber.d("Connection closed")
    }
}

class ConnectionError(message: String) : Exception(message)

/**
 * Functional interface for protecting a socket from VPN routing.
 *
 * Implementations typically delegate to [android.net.VpnService.protect].
 * The protector MUST be invoked BEFORE the socket connects so the SSTP
 * traffic goes out over the physical network instead of looping through
 * the VPN tunnel.
 */
fun interface SocketProtector {
    fun protect(socket: Socket): Boolean
}
