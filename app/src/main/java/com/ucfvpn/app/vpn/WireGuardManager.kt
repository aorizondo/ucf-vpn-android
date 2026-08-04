package com.ucfvpn.app.vpn

import com.ucfvpn.app.wg.WireGuardConfig
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

    private var currentConfig: WireGuardConfig? = null

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

            if (tunFd == null) throw IllegalStateException("TUN interface failed")

            currentConfig = wgConfig
            _state.value = WireGuardState.CONNECTED
            Timber.tag(TAG).i("WireGuard tunnel CONNECTED")
            Result.success(Unit)

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "WireGuard start failed")
            _state.value = WireGuardState.ERROR
            Result.failure(e)
        }
    }

    suspend fun stop() {
        try {
            Timber.tag(TAG).d("Stopping WireGuard...")
            _state.value = WireGuardState.STOPPING
            currentConfig = null
            _state.value = WireGuardState.STOPPED
            Timber.tag(TAG).i("WireGuard STOPPED")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "WireGuard stop error")
            _state.value = WireGuardState.ERROR
            throw e
        }
    }

    fun getState(): WireGuardState = _state.value

    companion object {
        private const val TAG = "WireGuardManager"
    }
}
