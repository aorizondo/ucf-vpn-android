package com.ucfvpn.app.wstunnel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Manages the lifecycle of an embedded wstunnel ARM64 binary as a
 * subprocess on Android.
 *
 * ## Binary management
 * On first run the binary is extracted from `assets/wstunnel_arm64` to
 * `context.filesDir/wstunnel` and marked executable.  Subsequent runs reuse
 * the already-extracted binary.
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
 */
class WstunnelManager(private val context: Context) {

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

    private val binaryName = "wstunnel"
    private val binaryAssetPath = "wstunnel_arm64"

    // ── Start ─────────────────────────────────────────────────────

    /**
     * Extract the binary (if needed) and launch the wstunnel subprocess.
     *
     * Returns [Result.success] when the process has been started and the
     * state has transitioned to [WstunnelState.RUNNING], or
     * [Result.failure] with the cause otherwise.
     */
    suspend fun start(config: WstunnelConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (_state.value == WstunnelState.RUNNING) {
                Log.w(TAG, "wstunnel already running — ignoring start()")
                return@withContext Result.success(Unit)
            }

            _state.value = WstunnelState.STARTING

            val binary = extractBinary()

            if (!config.isServerUrlValid) {
                val msg = "Invalid server URL: ${config.serverUrl}"
                Log.e(TAG, msg)
                _state.value = WstunnelState.ERROR
                return@withContext Result.failure(IllegalArgumentException(msg))
            }

            val cmd = config.buildCommand(binary.absolutePath)
            Log.i(TAG, "Launching: ${cmd.joinToString(" ")}")

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

    // ── Binary extraction ─────────────────────────────────────────

    /**
     * Ensure the ARM64 binary exists in the app's private files directory.
     *
     * 1. Look for `{filesDir}/wstunnel`.
     * 2. If missing, copy from `assets/wstunnel_arm64`.
     * 3. Mark it executable.
     */
    private fun extractBinary(): File {
        val dest = File(context.filesDir, binaryName)

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

        /** Regex matching lines that should be logged at ERROR level. */
        private val ERROR_PATTERN = Regex(
            "(?i)(error|failed|panic|fatal|refused|timeout|ssl_err)"
        )
    }
}
