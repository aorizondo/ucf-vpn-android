package com.ucfvpn.app

import android.app.Application
import timber.log.Timber

/**
 * Plants the logging tree.
 *
 * Without this every `Timber.d/w/e` call in the app is a no-op: Timber routes to
 * the trees that have been planted, and with none planted it discards silently.
 * The whole stack — SSTP handshake, PPP negotiation, the data path, the tunnel
 * managers — logs through Timber, so the app produced no diagnostics at all and
 * a failed connection was impossible to investigate on a device.
 *
 * Registered as `android:name` in the manifest; an Application class that is not
 * declared there is never instantiated.
 */
class UcfVpnApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // DebugTree tags each line with the calling class, which is what makes
        // `adb logcat -s SstpTunnelImpl PppStack ...` useful.
        Timber.plant(Timber.DebugTree())
        Timber.tag(TAG).i("UCF VPN started")
    }

    private companion object {
        const val TAG = "UcfVpnApplication"
    }
}
