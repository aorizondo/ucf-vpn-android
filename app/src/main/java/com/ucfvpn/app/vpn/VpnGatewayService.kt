package com.ucfvpn.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.ucfvpn.app.wg.WireGuardConfig
import com.ucfvpn.app.wstunnel.HevSocks5TunnelConfigGenerator
import com.ucfvpn.app.wstunnel.Tun2SocksManager
import com.ucfvpn.app.wstunnel.Tun2SocksState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.FileDescriptor

/**
 * VpnService subclass that manages the VPN tunnel interface.
 *
 * This service is declared in AndroidManifest.xml with BIND_VPN_SERVICE permission.
 * It provides:
 * - TUN interface establishment via [establishTunInterface]
 * - Socket protection via [protectSocket] and [protectFileDescriptor] to prevent traffic loops
 * - WireGuard tunnel management via [startWithWireGuard] and [shutdown]
 *
 * ## Traffic Loop Prevention (CRITICAL)
 *
 * When the VPN is active, ALL socket connections are routed through the TUN interface
 * by default. This causes a problem for the SSTP connection because:
 * - SSTP socket connects to SSTP server -> goes through TUN -> WireGuard -> wstunnel -> Internet
 * - This creates a loop: VPN trying to reach VPN server through VPN
 *
 * The solution is to call [protectFileDescriptor] on the SSTP socket BEFORE connecting.
 * This tells Android to bypass the VPN for that specific socket.
 *
 * ## Correct protect() Order:
 * ```kotlin
 * val sstpSocket = SSLSocketFactory.getDefault().createSocket()
 * sstpSocket.bind(InetSocketAddress(0))  // Bind to assign a local port
 * vpnService.protectFileDescriptor(sstpSocket.getFileDescriptor())  // BEFORE connect!
 * sstpSocket.connect(InetSocketAddress(sstpHost, sstpPort), timeout)
 * ```
 *
 * The service lifecycle is managed by the VpnOrchestrator:
 * - [onStartCommand] is called when the stack is ready to establish the TUN interface
 * - [onRevoke] is called when the user manually disconnects or the system kills the VPN
 * - [shutdown] should be called when all components need to be cleanly stopped
 */
class VpnGatewayService : VpnService() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "ucf_vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "VpnGatewayService"
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var wireGuardManager: WireGuardManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.tag(TAG).d("VpnGatewayService created")
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        Timber.tag(TAG).d("VpnGatewayService started with startId=$startId")
        return START_STICKY
    }

    /**
     * Starts the VPN tunnel with WireGuard as the underlying transport.
     *
     * This method is called by VpnOrchestrator after the full VPN stack is ready:
     * 1. SSTP client is configured
     * 2. PPP handler is ready
     * 3. wstunnel is forwarding UDP:51820
     * 4. WireGuard configuration is available
     *
     * @param wgConfig WireGuard configuration from [com.ucfvpn.app.wg.WireGuardConfigRepository]
     * @param vpnConfig TUN interface configuration
     * @return Result.success(Unit) if tunnel started, Result.failure(exception) otherwise
     */
    suspend fun startWithWireGuard(wgConfig: WireGuardConfig, vpnConfig: VpnConfig = VpnConfig.DEFAULT): Result<Unit> {
        Timber.tag(TAG).d("Starting with WireGuard...")

        // Initialize WireGuardManager if not already done
        if (wireGuardManager == null) {
            wireGuardManager = WireGuardManager(this)
        }

        val manager = wireGuardManager!!

        // Start WireGuard tunnel and await result (suspending)
        return manager.start(wgConfig, vpnConfig)
    }

    /**
     * Shuts down all VPN components cleanly.
     *
     * This should be called when the user disconnects or when the app is closing:
     * 1. Stop WireGuard tunnel
     * 2. Close TUN interface
     * 3. Stop the service
     */
    fun shutdown() {
        Timber.tag(TAG).d("Shutting down VPN components...")

        // Stop WireGuard tunnel
        wireGuardManager?.let { manager ->
            serviceScope.launch {
                try {
                    manager.stop()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error stopping WireGuard")
                }
            }
        }
        wireGuardManager = null

        // Stop tun2socks if running
        serviceScope.launch {
            shutdownTun2Socks()
        }

        // Close TUN interface
        tunInterface?.close()
        tunInterface = null

        Timber.tag(TAG).d("VPN components shut down")
    }

    /**
     * Called when VPN permission is revoked or the VPN is being torn down.
     *
     * This override ensures clean shutdown of all components when the system
     * revokes our VPN permission (user disconnect or system kill).
     */
    override fun onRevoke() {
        Timber.tag(TAG).d("VPN permission revoked")
        super.onRevoke()

        // Shutdown all components
        shutdown()

        // Cancel service scope
        serviceScope.cancel()

        // Stop the service
        stopSelf()
    }

    /**
     * Establishes the TUN interface with the given configuration.
     *
     * This method sets up the VPN interface in Android's network stack.
     * The TUN interface captures all network traffic matching our routes.
     *
     * @param address The tunnel IP address (e.g., "10.0.0.1")
     * @param prefixLength The network prefix length (CIDR, e.g., 24 for /24)
     * @param mtu The Maximum Transmission Unit (default 1300 for WireGuard over SSTP)
     * @param dnsServers List of DNS servers to use
     * @return The ParcelFileDescriptor for the TUN interface, or null on failure
     */
    fun establishTunInterface(
        address: String = "10.0.0.1",
        prefixLength: Int = 24,
        mtu: Int = 1300,
        dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8")
    ): ParcelFileDescriptor? {
        Timber.tag(TAG).d("Establishing TUN interface: address=$address/$prefixLength mtu=$mtu")

        val builder = Builder()
        builder.setSession("UCF VPN")
        builder.setMtu(mtu)
        builder.addAddress(address, prefixLength)

        // Add default routes (0.0.0.0/0 captures all IPv4 traffic)
        builder.addRoute("0.0.0.0", 0)

        // Add IPv6 routes if supported (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.addRoute("::", 0)
        }

        // Captive portal routes (resolved via SSTP)
        // HTTP proxy network (10.14.0.13:3128), used by wstunnel in SOCKS5 mode.
        builder.addRoute("10.14.0.0", 16)

        // VpnService.Builder.addRoute() does NOT support hostnames: it resolves
        // the address with InetAddress.parseNumericAddress(), which throws
        // IllegalArgumentException for domain names like "internet.ucf.edu.cu".
        // Documented alternative: exclude this app from the VPN so captive portal
        // traffic (OkHttpClient in ProxyAuthService) goes through the system
        // network instead of being lost in the TUN. Accepted trade-off: all app
        // traffic bypasses the VPN.
        builder.addDisallowedApplication(packageName)

        // Add DNS servers
        for (dns in dnsServers) {
            builder.addDnsServer(dns)
        }

        tunInterface = builder.establish()
        Timber.tag(TAG).d("TUN interface established: ${tunInterface != null}")

        return tunInterface
    }

    /**
     * Establishes the TUN interface with split-tunnel routing (Fase 4).
     *
     * Route layout:
     * - Private networks (e.g. 10.0.0.0/8, 192.168.0.0/16, 172.16.0.0/12)
     *   → TUN → SSTP (protected transport)
     * - Captive portal / HTTP proxy network (10.14.0.0/16, proxy 10.14.0.13:3128)
     *   → TUN → SSTP
     * - Default route (0.0.0.0/0, and ::/0 on Android 10+) → TUN →
     *   hev-socks5-tunnel → wstunnel SOCKS5 → HTTP proxy
     *
     * Unlike [establishTunInterface], this does NOT call
     * [android.net.VpnService.Builder.addDisallowedApplication]: in split
     * tunnel mode the 10.14.0.0/16 route already resolves the captive portal
     * through the SSTP tunnel, so the app does not need to bypass the VPN.
     *
     * @param privateNetworks CIDR networks routed through the SSTP tunnel
     * @param address The tunnel IP address (e.g., "10.0.0.1")
     * @param prefixLength The network prefix length (CIDR, e.g., 24 for /24)
     * @param mtu The Maximum Transmission Unit (default 1300)
     * @param dnsServers List of DNS servers to use
     * @return The ParcelFileDescriptor for the TUN interface, or null on failure
     * @throws IllegalArgumentException if any CIDR in [privateNetworks] is invalid
     */
    fun establishSplitTunInterface(
        privateNetworks: List<String>,
        address: String = "10.0.0.1",
        prefixLength: Int = 24,
        mtu: Int = 1300,
        dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8")
    ): ParcelFileDescriptor? {
        Timber.tag(TAG).d("Establishing split TUN interface: address=$address/$prefixLength mtu=$mtu")

        val builder = Builder()
        builder.setSession("UCF VPN")
        builder.setMtu(mtu)
        builder.addAddress(address, prefixLength)

        // Private networks → TUN → SSTP (protected transport)
        for (cidr in privateNetworks) {
            val (ip, prefix) = parseCidr(cidr)
            builder.addRoute(ip, prefix)
        }

        // Captive portal / HTTP proxy network (10.14.0.13:3128) → TUN → SSTP
        builder.addRoute("10.14.0.0", 16)

        // Default route → TUN → hev-socks5-tunnel → wstunnel SOCKS5
        builder.addRoute("0.0.0.0", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.addRoute("::", 0)
        }

        // Add DNS servers
        for (dns in dnsServers) {
            builder.addDnsServer(dns)
        }

        tunInterface = builder.establish()
        Timber.tag(TAG).d("Split TUN interface established: ${tunInterface != null}")

        return tunInterface
    }

    /**
     * Protects the given socket from being routed through the VPN tunnel.
     *
     * ## CRITICAL: This MUST be called BEFORE connecting the socket!
     *
     * When a socket is protected, its traffic bypasses the VPN and goes directly
     * through the physical network. This is essential for the SSTP connection
     * because:
     * - The SSTP server might be the same IP as our VPN endpoint
     * - Without protection, the SSTP connection would go through the VPN tunnel
     * - This creates a recursive loop: VPN -> SSTP -> VPN -> SSTP -> ...
     *
     * @param socket The socket to protect from VPN routing
     * @return true if protection was applied, false otherwise
     */
    fun protectSocket(socket: java.net.Socket): Boolean {
        return protect(socket)
    }

    fun protectFd(fd: Int): Boolean {
        val result = protect(fd)
        Timber.tag(TAG).d("protectFd result: $result (fd=$fd)")
        return result
    }

    /**
     * Returns the current WireGuard tunnel state.
     */
    fun getWireGuardState(): WireGuardState? = wireGuardManager?.state?.value

    // ── Tun2Socks (Phase 2: socks5+VPN without WireGuard) ────────

    private var tun2SocksManager: Tun2SocksManager? = null

    /**
     * Starts the VPN tunnel with hev-socks5-tunnel (Phase 2: socks5+VPN).
     *
     * This method establishes a TUN interface and passes the file descriptor
     * to hev-socks5-tunnel, which routes all traffic through the local SOCKS5
     * proxy (wstunnel). No WireGuard is involved in this path.
     *
     * @param vpnConfig TUN interface configuration
     * @param tun2SocksConfigPath Optional custom path to the hev-socks5-tunnel YAML config
     * @return Result.success(Unit) if tunnel started, Result.failure(exception) otherwise
     */
    suspend fun startWithSocks5Vpn(
        vpnConfig: VpnConfig = VpnConfig.DEFAULT,
        tun2SocksConfigPath: String? = null
    ): Result<Unit> {
        Timber.tag(TAG).d("Starting with socks5+VPN (hev-socks5-tunnel)...")

        // Initialize Tun2SocksManager if not already done
        if (tun2SocksManager == null) {
            tun2SocksManager = Tun2SocksManager(this)
        }

        val manager = tun2SocksManager!!

        // Establish TUN interface
        val tunFd = establishTunInterface(
            address = vpnConfig.address,
            prefixLength = vpnConfig.prefixLength,
            mtu = vpnConfig.mtu,
            dnsServers = vpnConfig.dnsServers
        )

        if (tunFd == null) {
            val msg = "Failed to establish TUN interface"
            Timber.tag(TAG).e(msg)
            return Result.failure(Exception(msg))
        }

        Timber.tag(TAG).d("TUN interface established, starting hev-socks5-tunnel...")

        // Start hev-socks5-tunnel with the TUN fd
        return manager.start(tunFd, tun2SocksConfigPath)
    }

    /**
     * Starts the VPN tunnel with split-tunnel routing (Fase 4).
     *
     * Traffic to [privateNetworks] (and the captive portal 10.14.0.0/16) is
     * routed through the TUN interface into the SSTP tunnel (protected
     * transport). All remaining traffic follows the default route through the
     * TUN interface into hev-socks5-tunnel, which forwards it to the local
     * wstunnel SOCKS5 proxy ([socks5Proxy]). When [dnsViaSocks5] is true, DNS
     * queries are also resolved over TCP through the SOCKS5 proxy
     * (`dns.tcp: true` in the generated YAML).
     *
     * ## Start order (CRITICAL)
     * wstunnel must be started BEFORE this method is called: the wstunnel
     * subprocess creates its own socket and cannot be protect()ed from Java,
     * so it must exist before the TUN interface captures the default route.
     *
     * @param privateNetworks CIDR networks routed through the SSTP tunnel
     * @param socks5Proxy Local SOCKS5 proxy in `host:port` form (wstunnel)
     * @param vpnConfig TUN interface configuration
     * @param dnsViaSocks5 When true, DNS is resolved via SOCKS5 (`dns.tcp: true`)
     * @return Result.success(Unit) if the split tunnel started,
     *   Result.failure(exception) otherwise
     */
    suspend fun startWithSplitTunnelSocks5(
        privateNetworks: List<String> = listOf("10.0.0.0/8", "192.168.0.0/16", "172.16.0.0/12"),
        socks5Proxy: String = "127.0.0.1:1080",
        vpnConfig: VpnConfig = VpnConfig.DEFAULT,
        dnsViaSocks5: Boolean = true
    ): Result<Unit> {
        Timber.tag(TAG).d("Starting with split tunnel + hev-socks5-tunnel...")

        // Parse "host:port" SOCKS5 proxy
        val (socks5Host, socks5Port) = try {
            parseSocks5Proxy(socks5Proxy)
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).e(e, "Invalid socks5Proxy")
            return Result.failure(e)
        }

        // Initialize Tun2SocksManager if not already done
        if (tun2SocksManager == null) {
            tun2SocksManager = Tun2SocksManager(this)
        }
        val manager = tun2SocksManager!!

        // 1. Establish split TUN interface (private routes + default route)
        val tunFd = try {
            establishSplitTunInterface(
                privateNetworks = privateNetworks,
                address = vpnConfig.address,
                prefixLength = vpnConfig.prefixLength,
                mtu = vpnConfig.mtu,
                dnsServers = vpnConfig.dnsServers
            )
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).e(e, "Invalid split tunnel configuration")
            return Result.failure(e)
        }

        if (tunFd == null) {
            val msg = "Failed to establish split TUN interface"
            Timber.tag(TAG).e(msg)
            return Result.failure(Exception(msg))
        }

        // 2. Generate dynamic YAML with the configured SOCKS5 proxy
        val yamlPath = HevSocks5TunnelConfigGenerator.generate(
            context = this,
            socks5Host = socks5Host,
            socks5Port = socks5Port,
            dnsViaSocks5 = dnsViaSocks5
        ).getOrElse { e ->
            Timber.tag(TAG).e(e, "Failed to generate hev-socks5-tunnel config")
            tunFd.close()
            tunInterface = null
            return Result.failure(e)
        }

        Timber.tag(TAG).d("Split TUN established, starting hev-socks5-tunnel with $yamlPath...")

        // 3. Start hev-socks5-tunnel with the TUN fd and dynamic config
        val startResult = manager.start(tunFd, yamlPath)
        if (startResult.isFailure) {
            tunFd.close()
            tunInterface = null
            return startResult
        }

        // 4. Verify hev-socks5-tunnel reached RUNNING state
        if (manager.state.value != Tun2SocksState.RUNNING) {
            val msg = "hev-socks5-tunnel did not reach RUNNING state (current: ${manager.state.value})"
            Timber.tag(TAG).e(msg)
            return Result.failure(Exception(msg))
        }

        Timber.tag(TAG).d(
            "Split tunnel started: private=$privateNetworks socks5=$socks5Proxy dnsViaSocks5=$dnsViaSocks5"
        )
        return Result.success(Unit)
    }

    /**
     * Shuts down the tun2socks component cleanly.
     */
    private suspend fun shutdownTun2Socks() {
        tun2SocksManager?.let { manager ->
            try {
                manager.stop()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error stopping tun2socks")
            }
        }
        tun2SocksManager = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "UCF VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("UCF VPN")
            .setContentText("VPN is active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Timber.tag(TAG).d("VpnGatewayService destroyed")
        tunInterface?.close()
        serviceScope.cancel()
        super.onDestroy()
    }
}

/**
 * Parses a CIDR string ("10.0.0.0/8") into an IP address and prefix length.
 *
 * Only numeric IPs are accepted: [android.net.VpnService.Builder.addRoute]
 * resolves addresses with `InetAddress.parseNumericAddress()`, which throws
 * for hostnames like "internet.ucf.edu.cu".
 *
 * @param cidr CIDR in `ip/prefix` form (IPv4 or IPv6)
 * @return Pair of numeric IP and prefix length
 * @throws IllegalArgumentException if the CIDR is malformed or the IP is not numeric
 */
internal fun parseCidr(cidr: String): Pair<String, Int> {
    val slash = cidr.indexOf('/')
    if (slash <= 0 || slash == cidr.lastIndex) {
        throw IllegalArgumentException("Invalid CIDR '$cidr': expected format ip/prefix")
    }
    val ip = cidr.substring(0, slash)
    val prefix = cidr.substring(slash + 1).toIntOrNull()
        ?: throw IllegalArgumentException("Invalid CIDR '$cidr': prefix must be an integer")
    if (prefix !in 0..32) {
        throw IllegalArgumentException("Invalid CIDR '$cidr': prefix must be in 0..32")
    }
    if (!isNumericIp(ip)) {
        throw IllegalArgumentException(
            "Invalid CIDR '$cidr': '$ip' is not a numeric IP address " +
                "(hostnames are not supported by VpnService.Builder.addRoute)"
        )
    }
    return ip to prefix
}

/**
 * Parses a `host:port` SOCKS5 proxy string into host and port.
 *
 * @param socks5Proxy Proxy in `host:port` form
 * @return Pair of host and port
 * @throws IllegalArgumentException if the proxy string is malformed
 */
internal fun parseSocks5Proxy(socks5Proxy: String): Pair<String, Int> {
    val colon = socks5Proxy.lastIndexOf(':')
    if (colon <= 0 || colon == socks5Proxy.lastIndex) {
        throw IllegalArgumentException("Invalid SOCKS5 proxy '$socks5Proxy': expected format host:port")
    }
    val host = socks5Proxy.substring(0, colon)
    val port = socks5Proxy.substring(colon + 1).toIntOrNull()
        ?: throw IllegalArgumentException("Invalid SOCKS5 proxy '$socks5Proxy': port must be an integer")
    if (port !in 1..65535) {
        throw IllegalArgumentException("Invalid SOCKS5 proxy '$socks5Proxy': port must be in 1..65535")
    }
    if (host.isBlank()) {
        throw IllegalArgumentException("Invalid SOCKS5 proxy '$socks5Proxy': host is empty")
    }
    return host to port
}

/**
 * Returns true when [ip] looks like a numeric IPv4 or IPv6 address.
 *
 * IPv4: four dot-separated octets, each 0-255. IPv6: only hex digits,
 * colons and dots (embedded IPv4). Hostnames (letters outside hex, e.g.
 * "internet.ucf.edu.cu") are rejected without triggering DNS resolution.
 */
private fun isNumericIp(ip: String): Boolean {
    if (ip.contains(':')) {
        return ip.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }
    val octets = ip.split('.')
    if (octets.size != 4) return false
    return octets.all { octet ->
        octet.isNotEmpty() && octet.length <= 3 && octet.all { it.isDigit() } && octet.toInt() in 0..255
    }
}
