package com.ucfvpn.app.state

import com.ucfvpn.app.wstunnel.TunnelType

/**
 * VPN connection state definitions.
 * Represents all possible states in the VPN connection lifecycle.
 */
sealed class VpnState {
    // Normal connection flow states:
    object Disconnected : VpnState()
    object SstpConnecting : VpnState()
    object SstpConnected : VpnState()
    data class PppNegotiating(val phase: PppPhase) : VpnState()
    data class PppAuthenticated(val localIp: String) : VpnState()
    object ProxyAuthenticating : VpnState()
    object ProxyAuthenticated : VpnState()
    data class WstunnelStarting(val type: TunnelType) : VpnState()
    data class WstunnelRunning(val socks5Port: Int) : VpnState()
    object WireGuardConnecting : VpnState()
    object WireGuardConnected : VpnState()
    object VpnStarting : VpnState()
    object VpnRunning : VpnState()

    // Error states:
    data class SstpError(val message: String) : VpnState()
    data class ProxyError(val message: String) : VpnState()
    data class WstunnelError(val message: String) : VpnState()
    data class WireGuardError(val message: String) : VpnState()

    /** PPP negotiation sub-phases (LCP → AUTH → IPCP). */
    enum class PppPhase { LCP, AUTH, IPCP }

    val displayName: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is SstpConnecting -> "SSTP Connecting"
            is SstpConnected -> "SSTP Connected"
            is PppNegotiating -> "PPP Negotiating (${phase.name})"
            is PppAuthenticated -> "PPP Authenticated"
            is ProxyAuthenticating -> "Proxy Authenticating"
            is ProxyAuthenticated -> "Proxy Authenticated"
            is WstunnelStarting -> "Wstunnel Starting (${type.name})"
            is WstunnelRunning -> "Wstunnel Running (SOCKS5 :$socks5Port)"
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