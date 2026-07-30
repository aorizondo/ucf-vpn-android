package com.ucfvpn.app.state

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Authenticating : ConnectionState()
    object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()

    val displayName: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is Connecting -> "Connecting"
            is Authenticating -> "Authenticating"
            is Connected -> "Connected"
            is Error -> "Error"
        }
}
