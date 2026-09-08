package com.ucfvpn.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import com.ucfvpn.app.sstp.data.SplitRouter
import com.ucfvpn.app.sstp.data.SstpDataPath
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
class VpnGatewayService : VpnService(), VpnTunnelController {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "ucf_vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "VpnGatewayService"

        /**
         * Prefix for the TUN address. /32 so the interface claims only the
         * address PPP assigned and does not shadow the rest of the subnet,
         * which must keep routing through the tunnel.
         */
        private const val TUN_PREFIX_LENGTH = 32
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var wireGuardManager: WireGuardManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Binder handed to in-process clients (the Activity) so they can obtain the
     * live service instance and pass it to the orchestrator.
     */
    inner class LocalBinder : Binder() {
        val service: VpnGatewayService get() = this@VpnGatewayService
    }

    private val localBinder = LocalBinder()

    /**
     * The framework binds with the [SERVICE_INTERFACE] action to operate the VPN
     * itself; that must go to [VpnService.onBind]. Any other binding is our own
     * app asking for the instance.
     */
    override fun onBind(intent: android.content.Intent?): android.os.IBinder? {
        return if (intent?.action == SERVICE_INTERFACE) {
            super.onBind(intent)
        } else {
            localBinder
        }
    }

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
    override suspend fun shutdown() {
        Timber.tag(TAG).d("Shutting down VPN components...")

        // Order matters, and this used to be wrong: the cleanup ran in a
        // launched coroutine while the TUN was closed immediately after, so the
        // interface disappeared from under the components still using it. With
        // the data path reading and writing that same descriptor, tearing it
        // down first is the only safe sequence.
        //
        //   data path → hev-socks5-tunnel → WireGuard → TUN
        shutdownTun2Socks()

        wireGuardManager?.let { manager ->
            try {
                manager.stop()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error stopping WireGuard")
            }
        }
        wireGuardManager = null

        // Close TUN interface last: nothing is reading it any more.
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

        // Run the shutdown to completion before stopping. Cancelling the scope
        // here, as this used to, killed the cleanup coroutine before it had a
        // chance to run; blocking instead would risk an ANR, since stopping a
        // subprocess can take seconds. The scope is cancelled in onDestroy.
        serviceScope.launch {
            shutdown()
            stopSelf()
        }
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
     * Like [establishTunInterface], this calls
     * [android.net.VpnService.Builder.addDisallowedApplication] for our own
     * package: the wstunnel subprocess shares this app's UID, so it must bypass
     * the VPN or it would be routed into the tunnel it serves.
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
        dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8"),
        bypassApps: List<String> = emptyList()
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

        // NOTE: this app is deliberately NOT excluded from the VPN.
        //
        // An earlier version excluded it, to keep wstunnel's socket out of the
        // tunnel it serves. That is wrong for this deployment: the SSTP link is
        // what places the device inside the UCF network, so from outside the
        // campus the HTTP proxy at 10.14.0.13 is reachable ONLY through the
        // tunnel. Excluding the app sent wstunnel out over mobile data, where
        // that address does not exist.
        //
        // There is no loop to avoid: wstunnel only ever talks to the internal
        // proxy — it is the proxy that reaches the Internet — and the SSTP
        // socket itself bypasses the tunnel through protect().
        //
        // User-selected apps that should bypass the VPN entirely.
        for (pkg in bypassApps) {
            if (pkg == packageName) continue // already excluded above
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                // An uninstalled package would otherwise abort the whole
                // connection; skipping it is the sane outcome.
                Timber.tag(TAG).w("Bypass app not installed, ignoring: $pkg")
            }
        }

        // Add DNS servers
        for (dns in dnsServers) {
            builder.addDnsServer(dns)
        }

        // Close any interface left over from a previous attempt before replacing
        // the field, otherwise the old descriptor leaks.
        tunInterface?.close()
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
    override fun protectSocket(socket: java.net.Socket): Boolean {
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

    /** Demultiplexer between the TUN, the SSTP tunnel and the SOCKS5 path. */
    private var dataPath: SstpDataPath? = null

    /** Our end of the socket pair that stands in for hev-socks5-tunnel's TUN. */
    private var socks5OurSide: ParcelFileDescriptor? = null

    /** hev's end, held between [establishSplitTunnel] and [startSocks5Bridge]. */
    private var socks5PendingHevSide: ParcelFileDescriptor? = null

    /**
     * Delivers a raw PPP frame to the SSTP tunnel. Set by the orchestrator once
     * the tunnel is up; without it internal traffic has nowhere to go, so the
     * split tunnel refuses to start.
     */
    override var sendToSstp: ((ByteArray) -> Unit)? = null

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
    /**
     * Phase 1: bring up the TUN interface and start moving packets.
     *
     * Split from the SOCKS5 side on purpose. The SSTP link is what places the
     * device inside the UCF network, so from outside the campus both the captive
     * portal and the HTTP proxy are reachable ONLY through the tunnel — and they
     * are needed *before* wstunnel can run. Establishing the TUN last, as this
     * used to, left those steps going out over the physical network, where those
     * addresses do not exist, and the connection could never succeed away from
     * the campus.
     *
     * Once this returns, traffic to [privateNetworks] flows through SSTP.
     * Everything else follows the default route into the socket pair, which has
     * no reader until [startSocks5Bridge] runs, so Internet traffic is dropped
     * for those few seconds rather than leaking outside the tunnel.
     *
     * @param privateNetworks CIDRs routed through the SSTP tunnel
     * @param localAddress address PPP assigned us; packets leaving the tunnel
     *   carry it as their source, so a made-up value would have replies dropped
     *   by the UCF network
     * @param dnsServers resolvers PPP assigned; internal names do not resolve
     *   against public ones
     * @param bypassApps packages excluded from the VPN
     */
    override suspend fun establishSplitTunnel(
        privateNetworks: List<String>,
        localAddress: String,
        dnsServers: List<String>,
        mtu: Int,
        bypassApps: List<String>
    ): Result<Unit> {
        Timber.tag(TAG).d("Establishing split tunnel (ip=%s dns=%s)", localAddress, dnsServers)

        val tunFd = try {
            establishSplitTunInterface(
                privateNetworks = privateNetworks,
                address = localAddress,
                prefixLength = TUN_PREFIX_LENGTH,
                mtu = mtu,
                dnsServers = dnsServers,
                bypassApps = bypassApps
            )
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).e(e, "Invalid split tunnel configuration")
            return Result.failure(e)
        }

        if (tunFd == null) {
            val msg = "Failed to establish split TUN interface"
            Timber.tag(TAG).e(msg)
            return Result.failure(IllegalStateException(msg))
        }

        val sender = sendToSstp
        if (sender == null) {
            val msg = "No SSTP sender wired: cannot route internal traffic"
            Timber.tag(TAG).e(msg)
            tunFd.close()
            tunInterface = null
            return Result.failure(IllegalStateException(msg))
        }

        // hev-socks5-tunnel cannot be handed the real TUN: it would receive
        // every packet, including the internal traffic the split exists to keep
        // out of the proxy. It gets one end of an AF_UNIX/SOCK_SEQPACKET pair
        // instead, reading and writing IP packets exactly as it would on a TUN;
        // SEQPACKET preserves the packet boundaries a datagram device needs.
        val socketPair = try {
            createSocks5SocketPair()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to create the SOCKS5 socket pair")
            tunFd.close()
            tunInterface = null
            return Result.failure(e)
        }
        val (ourSide, hevSide) = socketPair

        val path = try {
            SstpDataPath(
                tunFd = tunFd,
                socks5Side = ourSide,
                router = SplitRouter(privateNetworks),
                sendToSstp = sender,
                mtu = mtu
            ).also { it.start() }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start the data path")
            closeQuietly(ourSide)
            closeQuietly(hevSide)
            tunFd.close()
            tunInterface = null
            return Result.failure(e)
        }

        dataPath = path
        socks5OurSide = ourSide
        socks5PendingHevSide = hevSide

        Timber.tag(TAG).d("Split tunnel up: private=%s", privateNetworks)
        return Result.success(Unit)
    }

    /**
     * Phase 2: hand the Internet side to hev-socks5-tunnel.
     *
     * Runs once wstunnel's SOCKS5 listener is ready, which itself needs the
     * tunnel from phase 1 to reach the proxy.
     *
     * @param socks5Proxy local SOCKS5 endpoint in `host:port` form
     * @param dnsViaSocks5 resolve DNS over TCP through the proxy
     */
    override suspend fun startSocks5Bridge(
        socks5Proxy: String,
        dnsViaSocks5: Boolean
    ): Result<Unit> {
        val hevSide = socks5PendingHevSide
        if (hevSide == null) {
            val msg = "startSocks5Bridge called before establishSplitTunnel"
            Timber.tag(TAG).e(msg)
            return Result.failure(IllegalStateException(msg))
        }

        val (socks5Host, socks5Port) = try {
            parseSocks5Proxy(socks5Proxy)
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).e(e, "Invalid socks5Proxy")
            return Result.failure(e)
        }

        if (tun2SocksManager == null) {
            tun2SocksManager = Tun2SocksManager(this)
        }
        val manager = tun2SocksManager!!

        val yamlPath = HevSocks5TunnelConfigGenerator.generate(
            context = this,
            socks5Host = socks5Host,
            socks5Port = socks5Port,
            dnsViaSocks5 = dnsViaSocks5
        ).getOrElse { e ->
            Timber.tag(TAG).e(e, "Failed to generate hev-socks5-tunnel config")
            return Result.failure(e)
        }

        val startResult = manager.start(hevSide, yamlPath)
        if (startResult.isFailure) {
            return startResult
        }

        // Ask the library instead of assuming: a tunnel that died on launch
        // would otherwise be reported as healthy.
        if (!manager.isRunning()) {
            val msg = "hev-socks5-tunnel exited immediately after launch"
            Timber.tag(TAG).e(msg)
            manager.stop()
            return Result.failure(IllegalStateException(msg))
        }

        // The library runs in-process and has taken the descriptor; our copy is
        // no longer needed.
        closeQuietly(hevSide)
        socks5PendingHevSide = null

        Timber.tag(TAG).d("SOCKS5 bridge up: socks5=%s dnsViaSocks5=%s", socks5Proxy, dnsViaSocks5)
        return Result.success(Unit)
    }

    /**
     * Feed an IPv4 packet received through the SSTP tunnel into the TUN, so the
     * app that opened the connection sees the reply. Wired by the orchestrator
     * to `SstpTunnel.onIpPacket`.
     */
    override fun onPacketFromSstp(packet: ByteArray) {
        dataPath?.onPacketFromSstp(packet)
    }

    /**
     * Create the AF_UNIX socket pair used in place of a TUN for
     * hev-socks5-tunnel.
     *
     * @return our end first, the subprocess's end second
     */
    private fun createSocks5SocketPair(): Pair<ParcelFileDescriptor, ParcelFileDescriptor> {
        val ours = java.io.FileDescriptor()
        val theirs = java.io.FileDescriptor()
        // SOCK_SEQPACKET keeps message boundaries, so one read yields exactly one
        // IP packet, matching how a TUN behaves.
        android.system.Os.socketpair(
            android.system.OsConstants.AF_UNIX,
            android.system.OsConstants.SOCK_SEQPACKET,
            0,
            ours,
            theirs
        )
        // dup() takes a copy, so the descriptors Os.socketpair() opened have to
        // be closed here or every connection attempt leaks two of them.
        val wrappedOurs = ParcelFileDescriptor.dup(ours)
        val wrappedTheirs = try {
            ParcelFileDescriptor.dup(theirs)
        } catch (e: Exception) {
            closeQuietly(wrappedOurs)
            throw e
        } finally {
            closeRawQuietly(ours)
            closeRawQuietly(theirs)
        }
        return wrappedOurs to wrappedTheirs
    }

    private fun closeRawQuietly(fd: java.io.FileDescriptor) {
        try {
            android.system.Os.close(fd)
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "ignored error while closing a raw descriptor")
        }
    }

    private fun closeQuietly(fd: ParcelFileDescriptor?) {
        try {
            fd?.close()
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "ignored error while closing a descriptor")
        }
    }

    /**
     * Shuts down the tun2socks component cleanly.
     */
    private suspend fun shutdownTun2Socks() {
        // Stop the demultiplexer before the process it feeds.
        dataPath?.stop()
        dataPath = null
        closeQuietly(socks5OurSide)
        closeQuietly(socks5PendingHevSide)
        socks5OurSide = null
        socks5PendingHevSide = null

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
