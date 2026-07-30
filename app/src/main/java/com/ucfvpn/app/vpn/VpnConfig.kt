package com.ucfvpn.app.vpn

/**
 * Immutable data class representing TUN interface configuration.
 *
 * This configuration is used by [VpnGatewayService] to establish the VPN tunnel
 * interface before WireGuard starts handling the actual tunnel traffic.
 *
 * @property address The tunnel IP address (e.g., "10.0.0.1")
 * @property prefixLength The network prefix length (CIDR notation, e.g., 24 for /24)
 * @property mtu The Maximum Transmission Unit for the TUN interface (default: 1300)
 * @property dnsServers List of DNS server addresses to use
 * @property routes List of IP routes to include in the tunnel (default: 0.0.0.0/0)
 * @property ipv6Routes List of IPv6 routes to include in the tunnel (default: ::/0)
 */
data class VpnConfig(
    val address: String = "10.0.0.1",
    val prefixLength: Int = 24,
    val mtu: Int = 1300,
    val dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    val routes: List<String> = listOf("0.0.0.0/0"),
    val ipv6Routes: List<String> = listOf("::/0")
) {
    companion object {
        /**
         * Default TUN configuration for UCF VPN.
         * MTU=1300 is set to accommodate the WireGuard overhead over the SSTP transport.
         */
        val DEFAULT = VpnConfig()

        /**
         * Validates that this configuration can be used to establish a TUN interface.
         */
        fun VpnConfig.isValid(): Boolean {
            return address.isNotBlank()
                && prefixLength in 0..32
                && mtu in 68..65535
                && dnsServers.isNotEmpty()
        }
    }
}
