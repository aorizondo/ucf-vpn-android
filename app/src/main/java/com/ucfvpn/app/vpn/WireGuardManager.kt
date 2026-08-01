package com.ucfvpn.app.vpn

import com.ucfvpn.app.wg.WireGuardConfig
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

enum class WireGuardState {
    STOPPED, STARTING, CONNECTED, STOPPING, ERROR
}

class WireGuardManager(
    private val vpnService: VpnGatewayService
) {
    private val _state = MutableStateFlow(WireGuardState.STOPPED)
    val state: StateFlow<WireGuardState> = _state.asStateFlow()

    private var currentTunnel: Tunnel? = null
    private var currentConfig: Config? = null

    suspend fun start(wgConfig: WireGuardConfig, vpnConfig: VpnConfig): Result<Unit> {
        return try {
            Timber.tag(TAG).d("Starting WireGuard tunnel, endpoint=${wgConfig.peerEndpoint}")
            _state.value = WireGuardState.STARTING

            val tunFd = vpnService.establishTunInterface(
                address = vpnConfig.address,
                prefixLength = vpnConfig.prefixLength,
                mtu = vpnConfig.mtu,
                dnsServers = vpnConfig.dnsServers
            )

            if (tunFd == null) {
                throw IllegalStateException("Failed to establish TUN interface")
            }

            val configStr = buildWireGuardConfigString(wgConfig)
            Timber.tag(TAG).d("Config: ${configStr.replace("\n", " ")}")

            val config = Config.parse(configStr)
            currentConfig = config

            val backend = GoBackend(vpnService)
            val tunnel = object : Tunnel {
                override fun getName(): String = "ucfvpn0"
                override fun onStateChange(newState: Tunnel.State) {
                    Timber.tag(TAG).d("Tunnel state: $newState")
                }
            }
            backend.setState(tunnel, Backend.State.UP, config)
            currentTunnel = tunnel

            _state.value = WireGuardState.CONNECTED
            Timber.tag(TAG).i("WireGuard tunnel CONNECTED")
            Result.success(Unit)

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start WireGuard")
            _state.value = WireGuardState.ERROR
            Result.failure(e)
        }
    }

    suspend fun stop() {
        try {
            Timber.tag(TAG).d("Stopping WireGuard...")
            _state.value = WireGuardState.STOPPING

            val tunnel = currentTunnel
            if (tunnel != null) {
                val backend = GoBackend(vpnService)
                backend.setState(tunnel, Backend.State.DOWN, null)
            }

            currentTunnel = null
            currentConfig = null
            _state.value = WireGuardState.STOPPED
            Timber.tag(TAG).i("WireGuard STOPPED")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error stopping WireGuard")
            _state.value = WireGuardState.ERROR
            throw e
        }
    }

    fun getState(): WireGuardState = _state.value

    private fun buildWireGuardConfigString(wgConfig: WireGuardConfig): String {
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${wgConfig.privateKey}")
            appendLine("Address = ${wgConfig.address}")
            appendLine("DNS = ${wgConfig.dns.joinToString(", ")}")
            appendLine("MTU = ${wgConfig.mtu}")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${wgConfig.peerPublicKey}")
            wgConfig.peerPresharedKey?.let { appendLine("PresharedKey = $it") }
            appendLine("Endpoint = ${wgConfig.peerEndpoint}")
            appendLine("AllowedIPs = ${wgConfig.allowedIps.joinToString(", ")}")
            if (wgConfig.persistentKeepalive > 0) {
                appendLine("PersistentKeepalive = ${wgConfig.persistentKeepalive}")
            }
        }
    }

    companion object {
        private const val TAG = "WireGuardManager"
    }
}
