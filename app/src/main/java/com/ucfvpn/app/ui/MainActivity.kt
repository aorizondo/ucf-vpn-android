package com.ucfvpn.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

import com.ucfvpn.app.orchestrator.VpnOrchestrator
import com.ucfvpn.app.proxy.ProxyAuthService
import com.ucfvpn.app.sstp.client.SstpTunnelImpl
import com.ucfvpn.app.ui.navigation.AppNavHost
import com.ucfvpn.app.ui.theme.UcfVpnTheme
import com.ucfvpn.app.ui.viewmodel.VpnViewModel
import com.ucfvpn.app.wstunnel.WstunnelManager

/**
 * Main entry point for the UCF VPN application.
 *
 * Wires the real [VpnViewModel] backed by a production [VpnOrchestrator] with
 * concrete dependencies:
 * - [SstpTunnelImpl] — SSTP client with default settings (SSL errors ignored,
 *   socket protector null). Credentials are injected at connect-time via
 *   [VpnOrchestrator.performConnectionSequence].
 * - [ProxyAuthService] — captive-portal authenticator with default UCF endpoint.
 * - [WstunnelManager] — WebSocket tunnel process manager.
 *
 * ### Pending: VpnGatewayService wiring
 * The [VpnOrchestrator] accepts `vpnService = null` on construction. The actual
 * [com.ucfvpn.app.service.VpnGatewayService] (Android VpnService) must be bound
 * at runtime via `ServiceConnection` when the user initiates a connection. This
 * is documented in `.omo/notepads/ucf-vpn-split-tunnel/learnings.md` (line 257)
 * and is intentionally left as a follow-up task — the orchestrator gracefully
 * handles a null service reference via fail-open socket protection.
 */
class MainActivity : ComponentActivity() {

    /**
     * Lazily constructed [VpnViewModel] whose [VpnOrchestrator] is built with
     * real, production dependencies. The orchestrator's lifecycle is managed by
     * the ViewModel: [VpnViewModel.onCleared] calls [VpnOrchestrator.shutdown].
     */
    private val viewModel: VpnViewModel by lazy {
        val app = application
        val orchestrator = VpnOrchestrator(
            context = app,
            sstpTunnel = SstpTunnelImpl(),
            proxyAuthService = ProxyAuthService(),
            wstunnelManager = WstunnelManager(app)
            // vpnService = null — VpnGatewayService bind is a runtime concern
        )
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return VpnViewModel(app, orchestrator) as T
            }
        }
        ViewModelProvider(this, factory)[VpnViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UcfVpnTheme {
                AppNavHost(viewModel = viewModel)
            }
        }
    }
}
