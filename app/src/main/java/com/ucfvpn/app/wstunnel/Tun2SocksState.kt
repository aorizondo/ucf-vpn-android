package com.ucfvpn.app.wstunnel

/**
 * Represents the lifecycle state of the hev-socks5-tunnel library.
 *
 * Lives in its own file, alongside [WstunnelState], rather than trailing the
 * manager it belongs to.
 */
enum class Tun2SocksState {
    /** Tunnel is not running and not attempting to start. */
    STOPPED,

    /** Native library is being loaded and the tunnel started. */
    STARTING,

    /** Tunnel is running, as reported by the library itself. */
    RUNNING,

    /** Tunnel is being stopped and its worker thread joined. */
    STOPPING,

    /** Library could not be loaded, or the tunnel refused to start or stopped early. */
    ERROR
}
