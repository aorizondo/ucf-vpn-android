package com.ucfvpn.app.wg

import timber.log.Timber

object WireGuardConfigParser {

    fun parse(configText: String): WireGuardConfig? {
        return parseFallback(configText)
    }

    private fun parseFallback(configText: String): WireGuardConfig? {
        val lines = configText.lines().map { it.trim() }.filter { it.isNotBlank() }

        var currentSection: String? = null
        val interfaceValues = mutableMapOf<String, String>()
        val peerValues = mutableMapOf<String, String>()

        for (line in lines) {
            when {
                line == "[Interface]" -> currentSection = "[Interface]"
                line == "[Peer]" -> currentSection = "[Peer]"
                line.startsWith("[") -> currentSection = null
                currentSection != null -> {
                    val parsed = parseKeyValue(line) ?: continue
                    when (currentSection) {
                        "[Interface]" -> interfaceValues[parsed.first] = parsed.second
                        "[Peer]" -> peerValues[parsed.first] = parsed.second
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

    private fun parseKeyValue(line: String): Pair<String, String>? {
        val equalsIndex = line.indexOf('=')
        if (equalsIndex == -1) return null
        val key = line.substring(0, equalsIndex).trim()
        val value = line.substring(equalsIndex + 1).trim()
        return if (key.isNotEmpty()) key to value else null
    }

    private fun parseList(raw: String?): List<String> {
        return raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }
}
