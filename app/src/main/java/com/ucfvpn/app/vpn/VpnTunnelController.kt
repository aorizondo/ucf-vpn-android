package com.ucfvpn.app.vpn

import java.net.Socket

/**
 * The VPN operations the orchestrator needs, free of Android types.
 *
 * [VpnGatewayService] is a `VpnService`, which cannot be instantiated on the
 * JVM. Depending on it directly left the orchestrator's final stage impossible
 * to unit-test: tests had to pass `null`, so every one of them exercised the
 * "no service" error path and the success path was never covered — which is
 * precisely where the audit kept finding defects.
 *
 * Mirrors how [com.ucfvpn.app.sstp.client.SstpTunnel] is already an interface
 * with a concrete implementation behind it.
 */
interface VpnTunnelController {

    /**
     * Delivers a raw PPP frame to the SSTP tunnel. Set by the orchestrator once
     * the tunnel is up; without it internal traffic has nowhere to go.
     */
    var sendToSstp: ((ByteArray) -> Unit)?

    /** Keep [socket] out of the VPN, so the tunnel's own transport cannot loop. */
    fun protectSocket(socket: Socket): Boolean

    /** Write an IPv4 packet that arrived through the tunnel to the TUN. */
    fun onPacketFromSstp(packet: ByteArray)

    /**
     * Bring up the TUN and start moving packets: internal networks through SSTP,
     * everything else towards the SOCKS5 side.
     *
     * @param localAddress address PPP assigned; packets leaving the tunnel carry
     *   it as their source
     * @param dnsServers resolvers PPP assigned; internal names do not resolve
     *   against public ones
     */
    suspend fun establishSplitTunnel(
        privateNetworks: List<String>,
        localAddress: String,
        dnsServers: List<String>,
        mtu: Int,
        bypassApps: List<String>
    ): Result<Unit>

    /** Hand the Internet side to hev-socks5-tunnel, once wstunnel is listening. */
    suspend fun startSocks5Bridge(socks5Proxy: String, dnsViaSocks5: Boolean): Result<Unit>

    /** Tear everything down, in order: data path, tunnel library, TUN. */
    suspend fun shutdown()
}
