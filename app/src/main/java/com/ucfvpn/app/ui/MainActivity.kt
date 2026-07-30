package com.ucfvpn.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.ucfvpn.app.orchestrator.VpnOrchestrator
import com.ucfvpn.app.proxy.ProxyAuthService
import com.ucfvpn.app.sstp.client.SstpTunnelImpl
import com.ucfvpn.app.state.VpnStateMachine
import com.ucfvpn.app.ui.navigation.AppNavHost
import com.ucfvpn.app.ui.theme.UcfVpnTheme
import com.ucfvpn.app.ui.viewmodel.VpnViewModel
import com.ucfvpn.app.wg.WireGuardConfigRepositoryImpl
import com.ucfvpn.app.wstunnel.WstunnelManager

class MainActivity : ComponentActivity() {

    private lateinit var orchestrator: VpnOrchestrator

    private val viewModel: VpnViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as android.app.Application
                return VpnViewModel(application, this@MainActivity.orchestrator) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build orchestrator with real dependencies
        val wgConfigRepo = WireGuardConfigRepositoryImpl(applicationContext)
        val stateMachine = VpnStateMachine()

        orchestrator = VpnOrchestrator(
            context = applicationContext,
            sstpTunnel = SstpTunnelImpl(),
            proxyAuthService = ProxyAuthService(),
            wstunnelManager = WstunnelManager(applicationContext),
            wireGuardConfigRepository = wgConfigRepo,
            stateMachine = stateMachine
        )

        setContent {
            UcfVpnTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(viewModel = viewModel)
                }
            }
        }
    }
}
