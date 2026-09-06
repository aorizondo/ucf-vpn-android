package com.ucfvpn.app.sstp.data

/**
 * Decides, for each IP packet leaving the TUN interface, whether it belongs to
 * the SSTP tunnel (UCF internal networks) or to the SOCKS5 path (the Internet).
 *
 * This is the piece that makes split tunnelling real. Until now the private
 * CIDRs were only handed to `VpnService.Builder.addRoute()`, which merely tells
 * Android to send that traffic into the TUN — it does not say what happens
 * next. With the whole TUN descriptor handed to hev-socks5-tunnel, every packet
 * ended up in the SOCKS5 proxy regardless of its destination, so internal sites
 * were unreachable while the VPN was up.
 *
 * Kept free of Android APIs so the matching can be unit-tested on the JVM.
 *
 * @param privateNetworks CIDRs reachable through the SSTP tunnel
 * @throws IllegalArgumentException if a CIDR is malformed
 */
class SplitRouter(privateNetworks: List<String>) {

    /** Pre-computed (network, mask) pairs, as unsigned 32-bit values in an Int. */
    private val networks: List<Pair<Int, Int>> = privateNetworks.map { parseCidrToMask(it) }

    /**
     * True when [packet] must be forwarded through the SSTP tunnel.
     *
     * IPv6 is never internal: the tunnel negotiates IPv4 only (IPCP), so those
     * packets take the SOCKS5 path like any other non-internal traffic.
     *
     * @param packet raw IP packet as read from the TUN interface
     * @param length number of valid bytes in [packet]
     */
    fun isInternal(packet: ByteArray, length: Int = packet.size): Boolean {
        val destination = destinationIpv4(packet, length) ?: return false
        return networks.any { (network, mask) -> (destination and mask) == network }
    }

    companion object {

        /** Minimum size of an IPv4 header, i.e. the smallest packet worth parsing. */
        private const val IPV4_HEADER_SIZE = 20

        /** Offset of the destination address inside an IPv4 header. */
        private const val IPV4_DESTINATION_OFFSET = 16

        /**
         * Destination address of an IPv4 packet as an unsigned 32-bit value in an
         * Int, or null when [packet] is not a well-formed IPv4 packet.
         */
        fun destinationIpv4(packet: ByteArray, length: Int = packet.size): Int? {
            if (length < IPV4_HEADER_SIZE) return null
            // High nibble of the first byte is the IP version.
            if ((packet[0].toInt() shr 4) and 0x0F != 4) return null
            return readBigEndianInt(packet, IPV4_DESTINATION_OFFSET)
        }

        /**
         * Parse `a.b.c.d/prefix` into the (network, mask) pair used for matching.
         */
        internal fun parseCidrToMask(cidr: String): Pair<Int, Int> {
            val slash = cidr.indexOf('/')
            require(slash > 0 && slash < cidr.lastIndex) {
                "Invalid CIDR '$cidr': expected format ip/prefix"
            }
            val prefix = cidr.substring(slash + 1).toIntOrNull()
                ?: throw IllegalArgumentException("Invalid CIDR '$cidr': prefix must be an integer")
            require(prefix in 0..32) { "Invalid CIDR '$cidr': prefix must be in 0..32" }

            val octets = cidr.substring(0, slash).split('.')
            require(octets.size == 4) { "Invalid CIDR '$cidr': expected four octets" }
            var address = 0
            for (octet in octets) {
                val value = octet.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid CIDR '$cidr': non-numeric octet")
                require(value in 0..255) { "Invalid CIDR '$cidr': octet out of range" }
                address = (address shl 8) or value
            }

            // A /0 must mask everything off; `shl 32` is a no-op on Int, so the
            // shift has to be special-cased or 0.0.0.0/0 would match nothing.
            val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
            return (address and mask) to mask
        }

        private fun readBigEndianInt(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }
}
