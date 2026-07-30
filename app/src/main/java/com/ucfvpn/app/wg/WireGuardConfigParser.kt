package com.ucfvpn.app.wg

import timber.log.Timber

/**
 * Parser for wg-quick configuration format strings.
 *
 * Supports the standard format:
 * ```
 * [Interface]
 * PrivateKey = <base64>
 * Address = <cidr1>, <cidr2>
 * DNS = <ip1>, <ip2>
 * MTU = <int>
 *
 * [Peer]
 * PublicKey = <base64>
 * PresharedKey = <base64>
 * AllowedIPs = <cidr1>, <cidr2>
 * PersistentKeepalive = <int>
 * Endpoint = <host>:<port>
 * ```
 *
 * Provides two parsing strategies:
 * 1. **WireGuard .aar parser** — delegates to `com.wireguard.config.Config.parse()`
 *    when the .aar is available at runtime (preferred, more robust).
 * 2. **Fallback parser** — pure Kotlin implementation that works without the .aar.
 */
object WireGuardConfigParser {

    private const val SECTION_INTERFACE = "[Interface]"
    private const val SECTION_PEER = "[Peer]"

    /**
     * Parse a wg-quick format string into a [WireGuardConfig].
     *
     * First attempts to use the WireGuard .aar parser (`com.wireguard.config.Config`).
     * Falls back to the pure-Kotlin parser if the .aar class is unavailable or parsing fails.
     *
     * @param configText the wg-quick formatted configuration string
     * @return the parsed [WireGuardConfig], or null if parsing fails
     */
    fun parse(configText: String): WireGuardConfig? {
        return try {
            parseWithAar(configText) ?: parseFallback(configText)
        } catch (e: Exception) {
            Timber.w(e, "WireGuard .aar parser failed, falling back to Kotlin parser")
            parseFallback(configText)
        }
    }

    /**
     * Attempt to parse using the WireGuard .aar's built-in parser.
     * Returns null if the .aar class is not available or parsing fails.
     */
    private fun parseWithAar(configText: String): WireGuardConfig? {
        return try {
            val config = com.wireguard.config.Config.parse(configText)
            val iface = config.getInterface()
            val peers = config.getPeers()
            if (peers.isEmpty()) return null

            val peer = peers[0]
            val keyPair = iface.keyPair

            WireGuardConfig(
                privateKey = keyPair?.privateKey?.toBase64() ?: "",
                address = iface.addresses.joinToString(", ") { it.string },
                dns = iface.dnsServers.map { it.hostAddress ?: it.toString() },
                mtu = iface.mtu.orElse(1420),
                peerPublicKey = peer.publicKey.toBase64(),
                peerPresharedKey = peer.presharedKey.orElse(null)?.toBase64(),
                peerEndpoint = peer.endpoint.orElse(null)?.let { "${it.host}:${it.port}" } ?: "",
                allowedIps = peer.allowedIps.map { it.string },
                persistentKeepalive = peer.persistentKeepalive.orElse(0)
            )
        } catch (e: NoClassDefFoundError) {
            Timber.d("WireGuard .aar Config class not available, using fallback parser")
            null
        } catch (e: Exception) {
            Timber.w(e, "WireGuard .aar Config.parse() failed")
            null
        }
    }

    /**
     * Pure Kotlin fallback parser for wg-quick format.
     * Handles the same format as the WireGuard .aar parser.
     */
    private fun parseFallback(configText: String): WireGuardConfig? {
        val lines = configText.lines().map { it.trim() }.filter { it.isNotBlank() }

        var currentSection: String? = null
        val interfaceValues = mutableMapOf<String, String>()
        val peerValues = mutableMapOf<String, String>()

        for (line in lines) {
            when {
                line == SECTION_INTERFACE -> currentSection = SECTION_INTERFACE
                line == SECTION_PEER -> currentSection = SECTION_PEER
                line.startsWith("[") -> currentSection = null // unknown section, skip
                currentSection != null -> {
                    val parsed = parseKeyValue(line) ?: continue
                    when (currentSection) {
                        SECTION_INTERFACE -> interfaceValues[parsed.first] = parsed.second
                        SECTION_PEER -> peerValues[parsed.first] = parsed.second
                    }
                }
            }
        }

        val privateKey = interfaceValues["PrivateKey"] ?: ""
        val address = interfaceValues["Address"] ?: ""
        val dns = parseList(interfaceValues["DNS"])
        val mtu = interfaceValues["MTU"]?.toIntOrNull() ?: 1420

        val peerPublicKey = peerValues["PublicKey"] ?: ""
        val peerPresharedKey = peerValues["PresharedKey"]
        val peerEndpoint = peerValues["Endpoint"] ?: ""
        val allowedIps = parseList(peerValues["AllowedIPs"])
        val persistentKeepalive = peerValues["PersistentKeepalive"]?.toIntOrNull() ?: 0

        if (peerPublicKey.isBlank()) {
            Timber.w("Fallback parser: missing peer PublicKey")
            return null
        }

        return WireGuardConfig(
            privateKey = privateKey,
            address = address,
            dns = dns,
            mtu = mtu,
            peerPublicKey = peerPublicKey,
            peerPresharedKey = peerPresharedKey,
            peerEndpoint = peerEndpoint,
            allowedIps = allowedIps,
            persistentKeepalive = persistentKeepalive
        )
    }

    /**
     * Parse a "Key = Value" or "Key=Value" line into a pair.
     */
    private fun parseKeyValue(line: String): Pair<String, String>? {
        val equalsIndex = line.indexOf('=')
        if (equalsIndex == -1) return null
        val key = line.substring(0, equalsIndex).trim()
        val value = line.substring(equalsIndex + 1).trim()
        return if (key.isNotEmpty()) key to value else null
    }

    /**
     * Split a comma-separated string into a list of trimmed values.
     */
    private fun parseList(raw: String?): List<String> {
        return raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }
}
