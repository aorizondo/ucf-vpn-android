package com.ucfvpn.app.wstunnel

import android.content.Context
import android.os.Build
import android.util.Log
import com.ucfvpn.app.vpn.VpnGatewayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Manages the lifecycle of an embedded wstunnel binary (ARM64 or ARMv7,
 * selected at runtime from the device ABI) as a subprocess on Android.
 *
 * ## Binary management
 * On first run the binary is extracted from `assets/wstunnel_arm64` or
 * `assets/wstunnel_armv7` (chosen via [android.os.Build.SUPPORTED_ABIS]) to
 * `context.filesDir` under the same ABI-suffixed name and marked executable.
 * Subsequent runs reuse the already-extracted binary.
 *
 * ## Process lifecycle
 * - [start] extracts the binary, builds the argument list via
 *   [WstunnelConfig.buildCommand], launches a [ProcessBuilder], and attaches
 *   background readers for stdout / stderr.
 * - [stop] sends SIGTERM and waits up to 3 s before escalating to SIGKILL.
 *
 * ## State
 * The current lifecycle state is exposed as a [StateFlow] of [WstunnelState].
 *
 * ## Logging
 * All process output is forwarded to Android Logcat under the tag `wstunnel`.
 * The last [MAX_LOG_LINES] lines are kept in a circular buffer exposed via
 * [logBuffer].
 *
 * ## Thread safety
 * Start / stop are guarded by a mutating lock via [withContext] so that
 * concurrent calls from any dispatcher are serialised.
 *
 * ## Socket protection (protect()) — CRITICAL
 * The wstunnel binary creates its own socket when it connects to the
 * WebSocket server.  Because it runs as a subprocess, that socket cannot
 * be protected from Java: `VpnService.protectSocket()` only applies to
 * sockets created by the app process, and the subprocess inherits file
 * descriptors but not the ability to call back into the app.
 *
 * **Workaround**: start wstunnel BEFORE establishing the VPN (TUN).  The
 * wstunnel socket is then created before the TUN interface exists, so its
 * traffic goes out over the physical network and there is no loop.  The
 * start order is orchestrated by [com.ucfvpn.app.orchestrator.VpnOrchestrator]
 * (Fase 5); [vpnService] is stored here so the orchestrator can pass the
 * service reference without changing the process-launch logic.
 *
 * @param context Android context used for binary extraction and filesDir
 * @param vpnService Optional [VpnGatewayService] reference.  Not used to
 *   protect the subprocess socket (impossible from Java); kept for the
 *   orchestrator to enforce the "wstunnel before VPN" start order.
 */
class WstunnelManager(
    private val context: Context,
    vpnService: VpnGatewayService? = null
) {

    // ── Public API ────────────────────────────────────────────────

    /** Observable lifecycle state. */
    private val _state = MutableStateFlow(WstunnelState.STOPPED)
    val state: StateFlow<WstunnelState> = _state.asStateFlow()

    /** Last N lines of combined stdout + stderr output for UI display. */
    private val _logBuffer = ArrayDeque<String>(MAX_LOG_LINES)
    val logBuffer: List<String> get() = _logBuffer.toList()

    // ── Private fields ────────────────────────────────────────────

    private var process: Process? = null
    private var stdoutThread: Thread? = null
    private var stderrThread: Thread? = null

    /**
     * Optional [VpnGatewayService] reference for the orchestrator.
     *
     * The wstunnel subprocess creates its own socket when connecting to the
     * WebSocket server, so it cannot be protected from Java (see class KDoc).
     * This reference exists so the orchestrator can enforce the correct start
     * order: wstunnel BEFORE [VpnGatewayService.establishTunInterface].
     */
    private var vpnService: VpnGatewayService? = vpnService

    /**
     * Set the [VpnGatewayService] reference after construction.
     *
     * Allows wiring the service once it is available without rebuilding the
     * manager.  See [vpnService] for why this is not used to protect sockets.
     */
    fun setVpnService(vpnService: VpnGatewayService?) {
        this.vpnService = vpnService
    }

    /**
     * Asset name of the wstunnel binary for the current device ABI.
     *
     * - armeabi-v7a (32-bit) → `wstunnel_armv7`
     * - arm64-v8a and anything else → `wstunnel_arm64`
     */
    private val binaryAssetPath: String
        get() = when {
            Build.SUPPORTED_ABIS.any { it.startsWith("armeabi") } -> "wstunnel_armv7"
            else -> "wstunnel_arm64"
        }

    // ── Start ─────────────────────────────────────────────────────

    /**
     * Extract the binary (if needed) and launch the wstunnel subprocess.
     *
     * Returns [Result.success] when the process has been started and the
     * state has transitioned to [WstunnelState.RUNNING], or
     * [Result.failure] with the cause otherwise.
     *
     * ## Socket protection note (SOCKS5 + [vpnService])
     * The wstunnel binary creates its own socket when connecting to the
     * WebSocket server.  As a subprocess it cannot be protected from Java:
     * `VpnService.protectSocket()` does not apply to sockets created by the
     * child process (the subprocess inherits FDs but cannot call back into
     * the app).  The workaround is to start wstunnel BEFORE the VPN is
     * established — the wstunnel socket is then created before the TUN
     * interface exists, so there is no traffic loop.  The start order is
     * the orchestrator's responsibility (Fase 5); this method only logs a
     * reminder when the condition is detected.
     */
    suspend fun start(config: WstunnelConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (_state.value == WstunnelState.RUNNING) {
                Log.w(TAG, "wstunnel already running — ignoring start()")
                return@withContext Result.success(Unit)
            }

            _state.value = WstunnelState.STARTING

            if (config.tunnelType == TunnelType.SOCKS5 && vpnService != null) {
                Log.i(
                    TAG,
                    "SOCKS5 + vpnService: wstunnel must start BEFORE VpnService.establish() — " +
                        "subprocess sockets cannot be protect()ed from Java"
                )
            }

            val binary = extractBinary()

            if (!config.isServerUrlValid) {
                val msg = "Invalid server URL: ${config.serverUrl}"
                Log.e(TAG, msg)
                _state.value = WstunnelState.ERROR
                return@withContext Result.failure(IllegalArgumentException(msg))
            }

            val cmd = config.buildCommand(binary.absolutePath)
            // Redact proxy credentials from the log: the `-p` argument embeds
            // `http://user:pass@host:port` when proxyAuth is set.
            val redactedCmd = config.proxyAuth?.let { auth ->
                cmd.map { arg -> if (arg.contains(auth)) arg.replace(auth, "***") else arg }
            } ?: cmd
            Log.i(TAG, "Launching: ${redactedCmd.joinToString(" ")}")

            val pb = ProcessBuilder(cmd)
                .directory(context.filesDir)
                .redirectErrorStream(false)

            process = pb.start()
            _state.value = WstunnelState.RUNNING

            // Start log-capture threads
            stdoutThread = captureOutput(process!!.inputStream, "$TAG/stdout")
            stderrThread = captureOutput(process!!.errorStream, "$TAG/stderr")

            Log.i(TAG, "wstunnel started")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wstunnel", e)
            _state.value = WstunnelState.ERROR
            Result.failure(e)
        }
    }

    // ── Stop ──────────────────────────────────────────────────────

    /**
     * Gracefully terminate the wstunnel subprocess.
     *
     * Sends SIGTERM via [Process.destroy].  If the process is still alive
     * after 3 s, [Process.destroyForcibly] sends SIGKILL.  The state is
     * set back to [WstunnelState.STOPPED].
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        val p = process ?: run {
            Log.w(TAG, "stop() called but no process is running")
            _state.value = WstunnelState.STOPPED
            return@withContext
        }

        _state.value = WstunnelState.STOPPING
        Log.i(TAG, "Sending SIGTERM to wstunnel")
        p.destroy()

        // Wait up to 3 s for graceful exit
        if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            Log.w(TAG, "wstunnel did not exit — sending SIGKILL")
            p.destroyForcibly()
        }

        // Join log threads
        stdoutThread?.join(2_000)
        stderrThread?.join(2_000)

        process = null
        stdoutThread = null
        stderrThread = null

        _state.value = WstunnelState.STOPPED
        Log.i(TAG, "wstunnel stopped")
    }

    // ── Query ─────────────────────────────────────────────────────

    /** Convenience check: is the process currently alive? */
    fun isRunning(): Boolean = process?.isAlive == true

    /**
     * Wait until the local SOCKS5 listener accepts connections and completes
     * a real SOCKS5 handshake.
     *
     * Performs a TCP connect to `127.0.0.1:[port]`, sends the SOCKS5 greeting
     * `\x05\x01\x00` (version 5, one method, no-auth) and expects the server
     * reply `\x05\x00` (version 5, no-auth selected).  Probes are retried with
     * a short backoff until [timeoutMs] elapses.
     *
     * This is the readiness gate used by the orchestrator (Fase 4/5) before
     * handing the SOCKS5 endpoint to Tun2SocksManager / the split-tunnel
     * VpnService.
     *
     * @param port local SOCKS5 port exposed by wstunnel (default 1080)
     * @param timeoutMs maximum time to keep probing before giving up
     * @return true when the handshake succeeded, false on timeout
     */
    suspend fun waitForSocks5Ready(port: Int = 1080, timeoutMs: Long = 10_000): Boolean =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            val greeting = byteArrayOf(0x05, 0x01, 0x00)
            val expectedReply = byteArrayOf(0x05, 0x00)

            while (System.currentTimeMillis() < deadline) {
                try {
                    Socket().use { socket ->
                        socket.connect(
                            InetSocketAddress("127.0.0.1", port),
                            SOCKS5_PROBE_CONNECT_TIMEOUT_MS
                        )
                        socket.soTimeout = SOCKS5_PROBE_READ_TIMEOUT_MS

                        socket.getOutputStream().write(greeting)
                        socket.getOutputStream().flush()

                        val reply = ByteArray(2)
                        DataInputStream(socket.getInputStream()).use { input ->
                            input.readFully(reply)
                        }

                        if (reply.contentEquals(expectedReply)) {
                            Log.i(TAG, "SOCKS5 handshake OK on 127.0.0.1:$port")
                            return@withContext true
                        }
                        Log.w(TAG, "Unexpected SOCKS5 reply on 127.0.0.1:$port: " +
                            reply.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) })
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "SOCKS5 probe on 127.0.0.1:$port failed (retrying): ${e.message}")
                }
                delay(SOCKS5_PROBE_BACKOFF_MS)
            }

            Log.w(TAG, "SOCKS5 not ready on 127.0.0.1:$port after ${timeoutMs}ms")
            false
        }

    // ── Binary extraction ─────────────────────────────────────────

    /**
     * Ensure the ABI-appropriate binary exists in the app's private files directory.
     *
     * 1. Look for `{filesDir}/wstunnel_arm64` or `{filesDir}/wstunnel_armv7`
     *    (matching the current device ABI).
     * 2. If missing, copy from the matching asset.
     * 3. Mark it executable.
     *
     * The destination file name carries the ABI suffix so a binary extracted
     * under a different ABI is never reused.
     */
    private fun extractBinary(): File {
        val dest = File(context.filesDir, binaryAssetPath)

        if (!dest.exists()) {
            Log.i(TAG, "Extracting $binaryAssetPath → ${dest.absolutePath}")
            context.assets.open(binaryAssetPath).use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (!dest.setExecutable(true)) {
                Log.w(TAG, "setExecutable returned false — binary may still be usable")
            }
        }

        return dest
    }

    // ── Log capture ───────────────────────────────────────────────

    /**
     * Spawn a daemon [Thread] that reads lines from [stream] and
     * forwards them to both Logcat (tag [tag]) and the in-memory
     * circular buffer.
     *
     * The thread terminates when the stream is closed (process exits).
     */
    private fun captureOutput(stream: InputStream, tag: String): Thread {
        return thread(name = tag, isDaemon = true) {
            try {
                stream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        // Forward to Logcat
                        when {
                            // Detect error patterns
                            ERROR_PATTERN.matches(line) -> Log.e(tag, line)
                            else -> Log.i(tag, line)
                        }
                        // Keep in circular buffer
                        synchronized(_logBuffer) {
                            if (_logBuffer.size >= MAX_LOG_LINES) {
                                _logBuffer.removeFirst()
                            }
                            _logBuffer.addLast(line)
                        }
                        line = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                // Stream closed or interrupted — normal during shutdown
                Log.d(tag, "Log capture thread exiting: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "wstunnel"
        private const val MAX_LOG_LINES = 200

        /** Connect timeout per SOCKS5 probe attempt. */
        private const val SOCKS5_PROBE_CONNECT_TIMEOUT_MS = 500

        /** Read timeout for the SOCKS5 handshake reply. */
        private const val SOCKS5_PROBE_READ_TIMEOUT_MS = 1_000

        /** Backoff between SOCKS5 probe attempts. */
        private const val SOCKS5_PROBE_BACKOFF_MS = 250L

        /** Regex matching lines that should be logged at ERROR level. */
        private val ERROR_PATTERN = Regex(
            "(?i)(error|failed|panic|fatal|refused|timeout|ssl_err)"
        )
    }
}
