package com.ucfvpn.app.wstunnel

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Manages the lifecycle of the hev-socks5-tunnel binary as a subprocess on
 * Android.
 *
 * This is used in Phase 2 (socks5+VPN testing) to route all device
 * traffic through a local SOCKS5 proxy (wstunnel) without WireGuard.
 *
 * ## Binary management
 * The executable ships as `jniLibs/<abi>/libhev_socks5_tunnel.so` and is run
 * straight from [android.content.pm.ApplicationInfo.nativeLibraryDir]. It is
 * NOT copied to `filesDir`: since Android 10, an app with `targetSdk >= 29` is
 * denied by SELinux from exec()ing anything inside its own writable data
 * directory. The installer also picks the right ABI.
 *
 * ## Config file
 * The YAML config is generated dynamically via
 * [HevSocks5TunnelConfigGenerator] (Fase 4) and written to
 * `context.filesDir/hev_socks5_tunnel_dynamic.yaml`, replacing the static
 * asset `assets/hev_socks5_tunnel.yaml`. A custom config path can still be
 * passed to [start] to override the generated config.
 *
 * ## Process lifecycle
 * - [start] extracts the binary, generates the config, launches the process with the TUN fd
 * - [stop] sends SIGTERM and waits up to 3 s before SIGKILL
 *
 * ## State
 * The current lifecycle state is exposed as a [StateFlow] of [Tun2SocksState].
 */
class Tun2SocksManager(private val context: Context) {

    // ── Public API ────────────────────────────────────────────────

    /** Observable lifecycle state. */
    private val _state = MutableStateFlow(Tun2SocksState.STOPPED)
    val state: StateFlow<Tun2SocksState> = _state.asStateFlow()

    /** Last N lines of combined stdout + stderr output for UI display. */
    private val _logBuffer = ArrayDeque<String>(MAX_LOG_LINES)
    val logBuffer: List<String> get() = _logBuffer.toList()

    // ── Private fields ────────────────────────────────────────────

    private var process: Process? = null
    private var stdoutThread: Thread? = null
    private var stderrThread: Thread? = null

    /**
     * Absolute path of the hev-socks5-tunnel executable.
     *
     * Ships as `jniLibs/<abi>/libhev_socks5_tunnel.so`; see [resolveBinary] for
     * why it is not extracted to `filesDir`.
     */
    private val binaryPath: File
        get() = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    // ── Start ─────────────────────────────────────────────────────

    /**
     * Extract binary + config and launch hev-socks5-tunnel with the TUN fd.
     *
     * @param tunFd The ParcelFileDescriptor for the TUN interface
     * @param configPath Optional custom config path (defaults to the dynamically generated config)
     * @return [Result.success] when the process has started, or [Result.failure] with the cause
     */
    suspend fun start(
        tunFd: ParcelFileDescriptor,
        configPath: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (_state.value == Tun2SocksState.RUNNING) {
                Log.w(TAG, "hev-socks5-tunnel already running — ignoring start()")
                return@withContext Result.success(Unit)
            }

            _state.value = Tun2SocksState.STARTING

            val binary = resolveBinary()
            val config = configPath ?: generateDynamicConfig()

            // Validate TUN fd
            if (tunFd.fd == -1) {
                val msg = "Invalid TUN file descriptor"
                Log.e(TAG, msg)
                _state.value = Tun2SocksState.ERROR
                return@withContext Result.failure(IllegalArgumentException(msg))
            }

            val cmd = listOf(
                binary.absolutePath,
                "-c", config,
                "-f", tunFd.fd.toString()
            )
            Log.i(TAG, "Launching: ${cmd.joinToString(" ")}")

            val pb = ProcessBuilder(cmd)
                .directory(context.filesDir)
                .redirectErrorStream(false)

            val started = pb.start()
            process = started

            // Start log-capture threads before the liveness check, so a crash
            // banner on stderr still reaches Logcat.
            stdoutThread = captureOutput(started.inputStream, "$TAG/stdout")
            stderrThread = captureOutput(started.errorStream, "$TAG/stderr")

            // A launch failure (wrong ABI, unreadable config, SELinux denial on
            // exec) shows up as an immediate exit, not as an exception from
            // start(). Give the process a moment and confirm it is still alive
            // before reporting success.
            delay(LAUNCH_SETTLE_MS)
            if (!started.isAlive) {
                val msg = "hev-socks5-tunnel exited immediately (code ${started.exitValue()})"
                Log.e(TAG, msg)
                _state.value = Tun2SocksState.ERROR
                return@withContext Result.failure(IllegalStateException(msg))
            }

            _state.value = Tun2SocksState.RUNNING
            Log.i(TAG, "hev-socks5-tunnel started")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start hev-socks5-tunnel", e)
            _state.value = Tun2SocksState.ERROR
            Result.failure(e)
        }
    }

    // ── Stop ──────────────────────────────────────────────────────

    /**
     * Gracefully terminate the hev-socks5-tunnel subprocess.
     *
     * Sends SIGTERM via [Process.destroy]. If the process is still alive
     * after 3 s, [Process.destroyForcibly] sends SIGKILL.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        val p = process ?: run {
            Log.w(TAG, "stop() called but no process is running")
            _state.value = Tun2SocksState.STOPPED
            return@withContext
        }

        _state.value = Tun2SocksState.STOPPING
        Log.i(TAG, "Sending SIGTERM to hev-socks5-tunnel")
        p.destroy()

        // Wait up to 3 s for graceful exit
        if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            Log.w(TAG, "hev-socks5-tunnel did not exit — sending SIGKILL")
            p.destroyForcibly()
        }

        // Join log threads
        stdoutThread?.join(2_000)
        stderrThread?.join(2_000)

        process = null
        stdoutThread = null
        stderrThread = null

        _state.value = Tun2SocksState.STOPPED
        Log.i(TAG, "hev-socks5-tunnel stopped")
    }

    // ── Query ─────────────────────────────────────────────────────

    /** Convenience check: is the process currently alive? */
    fun isRunning(): Boolean = process?.isAlive == true

    // ── Binary extraction ─────────────────────────────────────────

    /**
     * Locate the hev-socks5-tunnel executable in the native library directory.
     *
     * Nothing is extracted or chmod'ed: since Android 10, an app with
     * `targetSdk >= 29` is denied by SELinux from exec()ing anything inside its
     * own writable data directory, so the previous copy-to-filesDir approach
     * could not work regardless of whether the file was present.
     *
     * @throws IllegalStateException if the binary is missing for this ABI
     */
    private fun resolveBinary(): File {
        val binary = binaryPath
        if (!binary.exists()) {
            throw IllegalStateException(
                "hev-socks5-tunnel binary not found at ${binary.absolutePath} — " +
                    "no build for this device ABI (${android.os.Build.SUPPORTED_ABIS.joinToString()})"
            )
        }
        return binary
    }

    /**
     * Generate the dynamic YAML config via [HevSocks5TunnelConfigGenerator]
     * (Fase 4) instead of extracting the static asset.
     *
     * Defaults point at the wstunnel SOCKS5 listener (127.0.0.1:1080) with
     * DNS over SOCKS5 enabled (`dns.tcp: true`).
     *
     * @return the absolute path of the generated config file
     * @throws IllegalStateException if generation fails
     */
    private fun generateDynamicConfig(): String {
        val result = HevSocks5TunnelConfigGenerator.generate(context)
        if (result.isFailure) {
            throw result.exceptionOrNull()
                ?: IllegalStateException("Failed to generate dynamic hev-socks5-tunnel config")
        }
        return result.getOrThrow()
    }

    // ── Log capture ───────────────────────────────────────────────

    private fun captureOutput(stream: InputStream, tag: String): Thread {
        return thread(name = tag, isDaemon = true) {
            try {
                stream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        when {
                            ERROR_PATTERN.matches(line) -> Log.e(tag, line)
                            else -> Log.i(tag, line)
                        }
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
                Log.d(tag, "Log capture thread exiting: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "tun2socks"
        private const val MAX_LOG_LINES = 200

        /** File name under `jniLibs/<abi>/`; must keep the `lib*.so` shape to be installed. */
        private const val BINARY_NAME = "libhev_socks5_tunnel.so"

        /** Grace period before checking that the freshly launched process survived. */
        private const val LAUNCH_SETTLE_MS = 300L

        /** Regex matching lines that should be logged at ERROR level. */
        private val ERROR_PATTERN = Regex(
            "(?i)(error|failed|panic|fatal|refused|timeout)"
        )
    }
}

/** Lifecycle states for the Tun2SocksManager. */
enum class Tun2SocksState {
    STOPPED, STARTING, RUNNING, STOPPING, ERROR
}
