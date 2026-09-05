package com.ucfvpn.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

import com.ucfvpn.app.orchestrator.VpnOrchestrator
import com.ucfvpn.app.proxy.ProxyAuthService
import com.ucfvpn.app.sstp.client.SstpTunnelImpl
import com.ucfvpn.app.ui.navigation.AppNavHost
import com.ucfvpn.app.ui.theme.UcfVpnTheme
import com.ucfvpn.app.ui.viewmodel.VpnViewModel
import com.ucfvpn.app.vpn.VpnGatewayService
import com.ucfvpn.app.wstunnel.WstunnelManager
import timber.log.Timber

/**
 * Main entry point for the UCF VPN application.
 *
 * Wires the real [VpnViewModel] backed by a production [VpnOrchestrator] with
 * concrete dependencies:
 * - [SstpTunnelImpl] — SSTP client. Credentials are injected at connect-time.
 * - [ProxyAuthService] — captive-portal authenticator with default UCF endpoint.
 * - [WstunnelManager] — WebSocket tunnel process manager.
 *
 * ## VpnGatewayService lifecycle
 *
 * A `VpnService` cannot simply be constructed: Android must first grant VPN
 * consent, and the service has to be started and bound before the orchestrator
 * can obtain the live instance. That sequence lives here:
 *
 * 1. [VpnService.prepare] returns a consent Intent the first time (null after).
 * 2. The user accepts → [startForegroundService] + [bindService].
 * 3. [ServiceConnection.onServiceConnected] hands the instance to the
 *    orchestrator via [VpnOrchestrator.vpnService], and the pending connect
 *    request proceeds.
 *
 * Without this the orchestrator ran with `vpnService = null` and the final
 * stage of the connection sequence always threw.
 */
class MainActivity : ComponentActivity() {

    private val orchestrator: VpnOrchestrator by lazy {
        val app = application
        VpnOrchestrator(
            context = app,
            sstpTunnel = SstpTunnelImpl(),
            proxyAuthService = ProxyAuthService(),
            wstunnelManager = WstunnelManager(app)
        )
    }

    /**
     * Lazily constructed [VpnViewModel] whose [VpnOrchestrator] is built with
     * real, production dependencies. The orchestrator's lifecycle is managed by
     * the ViewModel: [VpnViewModel.onCleared] calls [VpnOrchestrator.shutdown].
     */
    private val viewModel: VpnViewModel by lazy {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return VpnViewModel(application, orchestrator) as T
            }
        }
        ViewModelProvider(this, factory)[VpnViewModel::class.java]
    }

    private var serviceBound = false

    /** Set when the user asked to connect before the service was available. */
    private var connectPending = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? VpnGatewayService.LocalBinder)?.service
            if (service == null) {
                Timber.e("Unexpected binder from VpnGatewayService")
                return
            }
            Timber.d("VpnGatewayService bound")
            serviceBound = true
            orchestrator.vpnService = service

            if (connectPending) {
                connectPending = false
                viewModel.connectNow()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.w("VpnGatewayService disconnected")
            serviceBound = false
            orchestrator.vpnService = null
        }
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startAndBindVpnService()
            } else {
                connectPending = false
                Timber.w("VPN consent denied by the user")
                viewModel.reportError("Permiso de VPN denegado")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The UI asks to connect; acquiring consent and binding the service is
        // an Activity concern, so the ViewModel delegates it back here.
        viewModel.onConnectRequested = { requestVpnPermissionAndConnect() }

        setContent {
            UcfVpnTheme {
                AppNavHost(viewModel = viewModel)
            }
        }
    }

    /**
     * Ask for VPN consent if needed, then start and bind the service.
     * Once bound, the pending connect request is resumed.
     */
    private fun requestVpnPermissionAndConnect() {
        if (serviceBound && orchestrator.vpnService != null) {
            viewModel.connectNow()
            return
        }

        connectPending = true
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) {
            vpnPermissionLauncher.launch(consentIntent)
        } else {
            // Consent already granted in a previous session.
            startAndBindVpnService()
        }
    }

    private fun startAndBindVpnService() {
        val intent = Intent(this, VpnGatewayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
}
