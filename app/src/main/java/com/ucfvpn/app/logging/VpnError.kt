package com.ucfvpn.app.logging

/**
 * Classification of errors for reconnect logic and user messaging.
 */
enum class ErrorClassification {
    /** Error is temporary — auto-reconnect should be attempted. */
    RECOVERABLE,
    /** Error is permanent — requires user intervention. */
    FATAL
}

/**
 * Typed VPN error hierarchy with user-friendly messages.
 * Each error knows its classification (recoverable vs fatal)
 * and provides a non-technical message suitable for UI display.
 */
sealed class VpnError(
    open val message: String,
    open val classification: ErrorClassification,
    open val userFriendlyMessage: String
) {
    /** A connection stage exceeded its timeout threshold. */
    data class Timeout(
        val stage: ConnectionStage
    ) : VpnError(
        message = "${stage.displayName} timed out after ${stage.timeoutMs / 1000}s",
        classification = ErrorClassification.RECOVERABLE,
        userFriendlyMessage = "${stage.displayName} is taking too long. " +
            "The server may be slow or unreachable. Retrying…"
    )

    /** Network-level failure (DNS, socket, unreachable). */
    data class NetworkUnreachable(
        val details: String
    ) : VpnError(
        message = "Network unreachable: $details",
        classification = ErrorClassification.RECOVERABLE,
        userFriendlyMessage = "Cannot reach the server. " +
            "Check your internet connection and try again."
    )

    /** Connection established but dropped unexpectedly. */
    data class ConnectionDropped(
        val stage: ConnectionStage,
        val details: String
    ) : VpnError(
        message = "${stage.displayName} dropped: $details",
        classification = ErrorClassification.RECOVERABLE,
        userFriendlyMessage = "The connection was interrupted. " +
            "Attempting to reconnect…"
    )

    /** Proxy or SSTP authentication failure. */
    data class AuthFailed(
        val details: String
    ) : VpnError(
        message = "Authentication failed: $details",
        classification = ErrorClassification.FATAL,
        userFriendlyMessage = "Login failed. " +
            "Please verify your username and password in the configuration."
    )

    /** Missing or malformed configuration values. */
    data class ConfigurationInvalid(
        val details: String
    ) : VpnError(
        message = "Invalid configuration: $details",
        classification = ErrorClassification.FATAL,
        userFriendlyMessage = "VPN configuration is incorrect. " +
            "Please review and fix your settings before connecting."
    )

    /** Android VPN permission not granted. */
    data class PermissionDenied(
        val details: String
    ) : VpnError(
        message = "Permission denied: $details",
        classification = ErrorClassification.FATAL,
        userFriendlyMessage = "VPN permission was not granted. " +
            "Please allow the VPN request to continue."
    )

    /** WireGuard or SSTP tunnel could not be created. */
    data class TunnelCreationFailed(
        val details: String
    ) : VpnError(
        message = "Tunnel creation failed: $details",
        classification = ErrorClassification.FATAL,
        userFriendlyMessage = "Could not create the VPN tunnel. " +
            "Try restarting the app or your device."
    )

    /** SSL/TLS certificate validation error. */
    data class SslError(
        val details: String
    ) : VpnError(
        message = "SSL error: $details",
        classification = ErrorClassification.FATAL,
        userFriendlyMessage = "The server certificate could not be verified. " +
            "If this is expected, enable 'Ignore SSL Errors' in settings."
    )

    /** Catch-all for unexpected failures. */
    data class Unknown(
        val details: String,
        override val classification: ErrorClassification = ErrorClassification.FATAL
    ) : VpnError(
        message = "Unknown error: $details",
        classification = classification,
        userFriendlyMessage = "An unexpected error occurred. Please try again."
    )

    companion object {
        /**
         * Classify an arbitrary Throwable into a VpnError.
         * Uses message heuristics for common Android/network exceptions.
         */
        fun fromException(
            throwable: Throwable,
            stage: ConnectionStage? = null
        ): VpnError {
            val msg = throwable.message ?: throwable.javaClass.simpleName

            return when {
                msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("timed out", ignoreCase = true) ->
                    stage?.let { Timeout(it) } ?: NetworkUnreachable(msg)

                msg.contains("401") || msg.contains("403") ||
                    msg.contains("auth", ignoreCase = true) ||
                    msg.contains("unauthorized", ignoreCase = true) ->
                    AuthFailed(msg)

                msg.contains("resolve", ignoreCase = true) ||
                    msg.contains("UnknownHost", ignoreCase = true) ||
                    msg.contains("unreachable", ignoreCase = true) ||
                    msg.contains("ENETUNREACH") ->
                    NetworkUnreachable(msg)

                msg.contains("certificate", ignoreCase = true) ||
                    msg.contains("SSL", ignoreCase = true) ||
                    msg.contains("trust", ignoreCase = true) ->
                    SslError(msg)

                msg.contains("permission", ignoreCase = true) ||
                    msg.contains("denied", ignoreCase = true) ->
                    PermissionDenied(msg)

                msg.contains("config", ignoreCase = true) ||
                    msg.contains("invalid", ignoreCase = true) ||
                    msg.contains("malformed", ignoreCase = true) ->
                    ConfigurationInvalid(msg)

                msg.contains("tunnel", ignoreCase = true) ||
                    msg.contains("interface", ignoreCase = true) ||
                    msg.contains("create", ignoreCase = true) ->
                    TunnelCreationFailed(msg)

                msg.contains("connection", ignoreCase = true) ||
                    msg.contains("connect", ignoreCase = true) ||
                    msg.contains("reset", ignoreCase = true) ||
                    msg.contains("refused", ignoreCase = true) ||
                    msg.contains("broken pipe", ignoreCase = true) ->
                    stage?.let { ConnectionDropped(it, msg) }
                        ?: NetworkUnreachable(msg)

                else -> Unknown(msg)
            }
        }
    }
}
