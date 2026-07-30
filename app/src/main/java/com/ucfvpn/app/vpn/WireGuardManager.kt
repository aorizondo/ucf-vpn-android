package com.ucfvpn.app.vpn

import com.ucfvpn.app.wg.WireGuardConfig
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.State
import com.wireguard.android.config.Config
import com.wireguard.android.tunnel.Tunnel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.FileDescriptor

/**
 * WireGuard tunnel state machine.
 */
enum class WireGuardState {
    STOPPED,
    STARTING,
    CONNECTED,
    STOPPING,
    ERROR
}

/**
 * Manages the WireGuard tunnel using the GoBackend from the WireGuard .aar.
 *
 * This class is responsible for:
 * - Creating and configuring the WireGuard tunnel interface
 * - Managing tunnel lifecycle (start/stop)
 * - Reporting tunnel state via StateFlow
 *
 * The actual TUN interface is established by [VpnGatewayService] using Android's VpnService API.
 * This manager then configures WireGuard to use that interface via GoBackend.
 *
 * @property vpnService The VpnGatewayService instance used for TUN interface management
 */
class WireGuardManager(
    private val vpnService: VpnGatewayService
) {
    private val _state = MutableStateFlow(WireGuardState.STOPPED)
    val state: StateFlow<WireGuardState> = _state.asStateFlow()

    private var currentTunnel: Tunnel? = null
    private var currentConfig: Config? = null

    /**
     * Starts the WireGuard tunnel with the given configuration.
     *
     * The flow is:
     * 1. Validate both wgConfig and vpnConfig
     * 2. Update state to STARTING
     * 3. Create TUN interface via vpnService.establishTunInterface()
     * 4. Build WireGuard Config from wgConfig
     * 5. Create Tunnel instance
     * 6. Call backend.setState(tunnel, State.UP, config)
     * 7. Update state to CONNECTED
     *
     * @param wgConfig WireGuard tunnel configuration (interface + peer settings)
     * @param vpnConfig TUN interface configuration (address, routes, DNS, MTU)
     * @return Result.success(Unit) on successful startup, Result.failure(exception) on error
     */
    suspend fun start(wgConfig: WireGuardConfig, vpnConfig: VpnConfig): Result<Unit> {
        return try {
            Timber.tag(TAG).d("Starting WireGuard tunnel...")
            _state.value = WireGuardState.STARTING

            // Validate configurations
            require(WireGuardConfig.isValid()) { "Invalid WireGuard configuration" }
            require(VpnConfig.isValid()) { "Invalid VPN configuration" }

            // Establish TUN interface (this also sets up the VPN interface in Android)
            val tunFd = vpnService.establishTunInterface(
                address = vpnConfig.address,
                prefixLength = vpnConfig.prefixLength,
                mtu = vpnConfig.mtu,
                dnsServers = vpnConfig.dnsServers
            )

            if (tunFd == null) {
                throw IllegalStateException("Failed to establish TUN interface")
            }

            Timber.tag(TAG).d("TUN interface established with MTU=${vpnConfig.mtu}")

            // Build WireGuard Config from wgConfig
            val config = buildWireGuardConfig(wgConfig, vpnConfig)
            currentConfig = config

            // Create tunnel instance
            val tunnel = createTunnel("ucfvpn0")
            currentTunnel = tunnel

            Timber.tag(TAG).d("Created WireGuard tunnel: ${tunnel.name}")

            // GoBackend needs to know about our VpnGatewayService
            // We pass the FileDescriptor to the backend
            val backend = GoBackend(vpnService)

            // Set tunnel state to UP with our config
            backend.setState(tunnel, State.UP, config)

            _state.value = WireGuardState.CONNECTED
            Timber.tag(TAG).i("WireGuard tunnel is now CONNECTED")
            Result.success(Unit)

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start WireGuard tunnel")
            _state.value = WireGuardState.ERROR
            Result.failure(e)
        }
    }

    /**
     * Stops the WireGuard tunnel gracefully.
     *
     * Flow:
     * 1. Update state to STOPPING
     * 2. Call backend.setState(tunnel, State.DOWN, null)
     * 3. Clear current tunnel and config
     * 4. Update state to STOPPED
     */
    suspend fun stop() {
        return try {
            Timber.tag(TAG).d("Stopping WireGuard tunnel...")
            _state.value = WireGuardState.STOPPING

            val tunnel = currentTunnel
            if (tunnel != null) {
                val backend = GoBackend(vpnService)
                backend.setState(tunnel, State.DOWN, null)
                Timber.tag(TAG).d("WireGuard tunnel state set to DOWN")
            }

            currentTunnel = null
            currentConfig = null

            _state.value = WireGuardState.STOPPED
            Timber.tag(TAG).i("WireGuard tunnel is now STOPPED")

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error stopping WireGuard tunnel")
            _state.value = WireGuardState.ERROR
            throw e
        }
    }

    /**
     * Builds a WireGuard [Config] object from [WireGuardConfig] and [VpnConfig].
     *
     * The resulting config string looks like:
     * ```
     * [Interface]
     * PrivateKey = <privateKey>
     * Address = <address>
     * DNS = <dnsServers>
     * MTU = <mtu>
     *
     * [Peer]
     * PublicKey = <peerPublicKey>
     * PresharedKey = <peerPresharedKey>  # if present
     * Endpoint = <peerEndpoint>
     * AllowedIPs = <allowedIps>
     * PersistentKeepalive = <persistentKeepalive>
     * ```
     *
     * Note: The endpoint is set to 127.0.0.1:51820 because wstunnel
     * forwards UDP traffic to the actual WireGuard server.
     */
    private fun buildWireGuardConfig(wgConfig: WireGuardConfig, vpnConfig: VpnConfig): Config {
        val configString = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${wgConfig.privateKey}")
            appendLine("Address = ${wgConfig.address}")
            appendLine("DNS = ${wgConfig.dns.joinToString(", ")}")
            appendLine("MTU = ${wgConfig.mtu}")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${wgConfig.peerPublicKey}")
            wgConfig.peerPresharedKey?.let {
                appendLine("PresharedKey = $it")
            }
            appendLine("Endpoint = ${wgConfig.peerEndpoint}")
            appendLine("AllowedIPs = ${wgConfig.allowedIps.joinToString(", ")}")
            if (wgConfig.persistentKeepalive > 0) {
                appendLine("PersistentKeepalive = ${wgConfig.persistentKeepalive}")
            }
        }

        Timber.tag(TAG).d("Building WireGuard config: ${configString.replace("\n", "|")}")

        return Config.parse(configString)
    }

    /**
     * Creates a new [Tunnel] instance with the given name.
     *
     * The Tunnel class from WireGuard .aar represents a tunnel configuration
     * that can be managed by a Backend.
     */
    private fun createTunnel(name: String): Tunnel {
        return Tunnel(name)
    }

    /**
     * Returns the current tunnel state.
     */
    fun getState(): WireGuardState = _state.value

    companion object {
        private const val TAG = "WireGuardManager"
    }
}
