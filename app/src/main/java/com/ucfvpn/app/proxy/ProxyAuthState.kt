package com.ucfvpn.app.proxy

enum class ProxyAuthState {
    IDLE,
    AUTHENTICATING,
    AUTHENTICATED,
    EXPIRED,
    ERROR
}
