package com.ucfvpn.app.wg

/**
 * Immutable data class representing a complete WireGuard tunnel configuration.
 *
 * Matches the wg-quick configuration format with sections [Interface] and [Peer].
 * Sensitive keys (privateKey, peerPresharedKey) are managed separately via
 * [WireGuardConfigRepository] which uses Android Keystore for secure storage.
 */
data class WireGuardConfig(
    val privateKey: String,
    val address: String,
    val dns: List<String>,
    val mtu: Int = 1420,
    val peerPublicKey: String,
    val peerPresharedKey: String?,
    val peerEndpoint: String,
    val allowedIps: List<String>,
    val persistentKeepalive: Int = 0
) {
    companion object {
        /**
         * Default configuration template based on the UCF VPN server setup.
         * The private key and pre-shared key must be provided at runtime
         * and are stored securely in Android Keystore.
         */
        val defaultConfig = WireGuardConfig(
            privateKey = "",
            address = "10.8.0.2/24",
            dns = listOf("1.1.1.1", "2606:4700:4700::1111"),
            mtu = 1420,
            peerPublicKey = "OVm14lotGvKKawksQ8UVPhO0phxZ+8WZDlxgAKZ55h0=",
            peerPresharedKey = null,
            peerEndpoint = "127.0.0.1:51820",
            allowedIps = listOf("0.0.0.0/0", "::/0")
        )

        /**
         * Whether this config has all required fields to establish a tunnel.
         * Private key must be non-empty, and all peer fields must be present.
         */
        fun WireGuardConfig.isValid(): Boolean {
            return privateKey.isNotBlank()
                && address.isNotBlank()
                && peerPublicKey.isNotBlank()
                && peerEndpoint.isNotBlank()
                && allowedIps.isNotEmpty()
        }
    }
}
