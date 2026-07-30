package com.ucfvpn.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.ucfvpn.app.wg.WireGuardConfig
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
        builder.setName("UCF VPN")
        builder.setMtu(mtu)
        builder.addAddress(address, prefixLength)

        // Add default routes (0.0.0.0/0 captures all IPv4 traffic)
        builder.addRoute("0.0.0.0", 0)

        // Add IPv6 routes if supported (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.addRoute("::", 0)
        }

        // Add DNS servers
        for (dns in dnsServers) {
            builder.addDnsServer(dns)
        }

        // Include all networks to capture traffic (optional, can be restricted)
        // builder.addDisallowedApplication(myPackageName) to exclude apps

        tunInterface = builder.establish()
        Timber.tag(TAG).d("TUN interface established: ${tunInterface != null}")

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
        return protectFileDescriptor(socket.getFileDescriptor())
    }

    /**
     * Protects a file descriptor from being routed through the VPN tunnel.
     *
     * This is the lower-level version of [protectSocket] that takes a raw file descriptor.
     * Use this when you have a FileDescriptor directly (e.g., from a Socket).
     *
     * ## CRITICAL ORDER:
     * 1. Create/bind socket
     * 2. Call protectFileDescriptor() to exempt it from VPN
     * 3. Connect the socket
     *
     * Example:
     * ```kotlin
     * val sstpSocket = SSLSocketFactory.getDefault().createSocket()
     * sstpSocket.bind(InetSocketAddress(0))
     * vpnService.protectFileDescriptor(sstpSocket.getFileDescriptor())
     * sstpSocket.connect(InetSocketAddress(sstpHost, sstpPort), timeout)
     * ```
     *
     * @param fd The file descriptor to protect
     * @return true if protection was applied, false otherwise
     */
    fun protectFileDescriptor(fd: FileDescriptor): Boolean {
        val result = protect(fd)
        Timber.tag(TAG).d("protectFileDescriptor result: $result (fd=$fd)")
        return result
    }

    /**
     * Protects a file descriptor by its integer value.
     *
     * This overload is useful when you have the raw fd as an integer.
     *
     * @param fd The file descriptor value to protect
     * @return true if protection was applied, false otherwise
     */
    fun protectFd(fd: Int): Boolean {
        val result = protect(fd)
        Timber.tag(TAG).d("protectFd result: $result (fd=$fd)")
        return result
    }

    /**
     * Returns the current WireGuard tunnel state.
     */
    fun getWireGuardState(): WireGuardState? = wireGuardManager?.state?.value

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
