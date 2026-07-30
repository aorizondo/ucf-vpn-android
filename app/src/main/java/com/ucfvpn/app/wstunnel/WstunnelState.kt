package com.ucfvpn.app.wstunnel

/**
 * Represents the lifecycle state of the wstunnel subprocess.
 */
enum class WstunnelState {
    /** Process is not running and not attempting to start. */
    STOPPED,

    /** Process is being launched (binary extraction + ProcessBuilder.start). */
    STARTING,

    /** Process is running and log capture threads are active. */
    RUNNING,

    /** Process is being gracefully terminated (SIGTERM sent, awaiting exit). */
    STOPPING,

    /** Process exited unexpectedly or failed to start. */
    ERROR
}
