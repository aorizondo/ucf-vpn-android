package com.ucfvpn.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor

/**
 * VpnService subclass that manages the VPN tunnel interface.
 * This service is declared in AndroidManifest.xml with BIND_VPN_SERVICE permission.
 *
 * The service lifecycle is managed by the VpnOrchestrator:
 * - [onStartCommand] is called when the stack is ready to establish the TUN interface
 * - [onRevoke] is called when the user manually disconnects or the system kills the VPN
 * - [protect()] is used on the SSTP socket to prevent traffic loops
 */
class VpnGatewayService : VpnService() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "ucf_vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "VpnGatewayService"
    }

    private var tunInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        tunInterface?.close()
        tunInterface = null
        stopSelf()
    }

    /**
     * Establishes the TUN interface with the given configuration.
     * Called by VpnOrchestrator after the full VPN stack is ready.
     */
    fun establishTunInterface(
        address: String = "10.0.0.1",
        prefixLength: Int = 24,
        mtu: Int = 1300,
        dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8")
    ): ParcelFileDescriptor? {
        val builder = Builder()
        builder.setName("UCF VPN")
        builder.setMtu(mtu)
        builder.addAddress(address, prefixLength)
        builder.addRoute("0.0.0.0", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.addRoute("::", 0)
        }
        for (dns in dnsServers) {
            builder.addDnsServer(dns)
        }
        tunInterface = builder.establish()
        return tunInterface
    }

    /**
     * Protects the given socket from being routed through the VPN tunnel.
     * Must be called BEFORE connecting the SSTP socket.
     */
    fun protectSocket(socket: java.net.Socket): Boolean {
        return protect(socket.getFileDescriptor())
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
        tunInterface?.close()
        super.onDestroy()
    }
}
