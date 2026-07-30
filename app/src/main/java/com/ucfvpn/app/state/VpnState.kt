package com.ucfvpn.app.state

/**
 * VPN connection state definitions.
 * Represents all possible states in the VPN connection lifecycle.
 */
sealed class VpnState {
    // Normal connection flow states:
    object Disconnected : VpnState()
    object SstpConnecting : VpnState()
    object SstpConnected : VpnState()
    object ProxyAuthenticating : VpnState()
    object ProxyAuthenticated : VpnState()
    object WstunnelStarting : VpnState()
    object WstunnelRunning : VpnState()
    object WireGuardConnecting : VpnState()
    object WireGuardConnected : VpnState()
    object VpnStarting : VpnState()
    object VpnRunning : VpnState()

    // Error states:
    data class SstpError(val message: String) : VpnState()
    data class ProxyError(val message: String) : VpnState()
    data class WstunnelError(val message: String) : VpnState()
    data class WireGuardError(val message: String) : VpnState()

    val displayName: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is SstpConnecting -> "SSTP Connecting"
            is SstpConnected -> "SSTP Connected"
            is ProxyAuthenticating -> "Proxy Authenticating"
            is ProxyAuthenticated -> "Proxy Authenticated"
            is WstunnelStarting -> "Wstunnel Starting"
            is WstunnelRunning -> "Wstunnel Running"
            is WireGuardConnecting -> "WireGuard Connecting"
            is WireGuardConnected -> "WireGuard Connected"
            is VpnStarting -> "VPN Starting"
            is VpnRunning -> "VPN Running"
            is SstpError -> "SSTP Error"
            is ProxyError -> "Proxy Error"
            is WstunnelError -> "Wstunnel Error"
            is WireGuardError -> "WireGuard Error"
        }

    val isError: Boolean
        get() = this is SstpError || this is ProxyError ||
                this is WstunnelError || this is WireGuardError

    val isConnected: Boolean
        get() = this == VpnRunning

    val isTransitioning: Boolean
        get() = !isError && this != Disconnected && this != VpnRunning
}
