package com.ucfvpn.app.wstunnel

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Runs hev-socks5-tunnel, which turns IP packets into SOCKS5 traffic.
 *
 * ## Why this is not a subprocess
 * It used to be launched with [ProcessBuilder], passing the TUN descriptor's
 * number on the command line (`-f <fd>`). That could never have worked: Android
 * closes descriptors above stderr on exec, so the number pointed at nothing in
 * the child — measured on the CI emulator by
 * `FdInheritanceInstrumentedTest`. No packet could have crossed the tunnel
 * however correct the rest of the code was, and nothing revealed it because the
 * code had never run.
 *
 * The library therefore runs in-process through the JNI layer that
 * hev-socks5-tunnel itself ships ([TProxyService]), where the descriptor is
 * simply valid.
 *
 * ## What it is given
 * Not the real TUN: one end of a socket pair, so [com.ucfvpn.app.sstp.data.SstpDataPath]
 * can keep the traffic bound for the UCF internal networks out of the proxy.
 *
 * ## State
 * Exposed as a [StateFlow] of [Tun2SocksState].
 */
class Tun2SocksManager(private val context: Context) {

    // ── Public API ────────────────────────────────────────────────

    /** Observable lifecycle state. */
    private val _state = MutableStateFlow(Tun2SocksState.STOPPED)
    val state: StateFlow<Tun2SocksState> = _state.asStateFlow()

    // ── Private fields ────────────────────────────────────────────

    /** Set once the native library has been loaded successfully. */
    private var libraryLoaded = false

    // ── Start ─────────────────────────────────────────────────────

    /**
     * Load the library if needed and start the tunnel on [tunFd].
     *
     * @param tunFd descriptor carrying raw IP packets — in the split-tunnel path
     *   this is our end of the socket pair, not the TUN itself
     * @param configPath optional config path; defaults to the generated YAML
     * @return [Result.success] once the tunnel reports itself running
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

            if (!libraryLoaded) {
                // Fails when no build exists for this ABI, or when the JNI layer
                // could not bind its natives to TProxyService.
                TProxyService.load()
                libraryLoaded = true
            }

            val config = configPath ?: generateDynamicConfig()

            if (tunFd.fd == -1) {
                val msg = "Invalid tunnel file descriptor"
                Log.e(TAG, msg)
                _state.value = Tun2SocksState.ERROR
                return@withContext Result.failure(IllegalArgumentException(msg))
            }

            Log.i(TAG, "Starting hev-socks5-tunnel (config=$config fd=${tunFd.fd})")

            // Returns as soon as its worker thread is up; the tunnel keeps
            // running until TProxyStopService().
            if (!TProxyService.TProxyStartService(config, tunFd.fd)) {
                val msg = "hev-socks5-tunnel refused to start"
                Log.e(TAG, msg)
                _state.value = Tun2SocksState.ERROR
                return@withContext Result.failure(IllegalStateException(msg))
            }

            // Ask the library rather than assuming: the previous implementation
            // reported RUNNING unconditionally, which made the caller's liveness
            // check tautological.
            if (!TProxyService.TProxyIsRunning()) {
                val msg = "hev-socks5-tunnel stopped immediately after starting"
                Log.e(TAG, msg)
                TProxyService.TProxyStopService()
                _state.value = Tun2SocksState.ERROR
                return@withContext Result.failure(IllegalStateException(msg))
            }

            _state.value = Tun2SocksState.RUNNING
            Log.i(TAG, "hev-socks5-tunnel started")
            Result.success(Unit)
        } catch (e: UnsatisfiedLinkError) {
            // Not an Exception, so it would otherwise escape the catch below and
            // crash the caller instead of failing the connection.
            Log.e(TAG, "hev-socks5-tunnel native library unavailable", e)
            _state.value = Tun2SocksState.ERROR
            Result.failure(IllegalStateException("hev-socks5-tunnel library not available for this ABI", e))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start hev-socks5-tunnel", e)
            _state.value = Tun2SocksState.ERROR
            Result.failure(e)
        }
    }

    // ── Stop ──────────────────────────────────────────────────────

    /** Stop the tunnel and wait for its worker thread to finish. */
    suspend fun stop() = withContext(Dispatchers.IO) {
        if (!libraryLoaded) {
            _state.value = Tun2SocksState.STOPPED
            return@withContext
        }

        _state.value = Tun2SocksState.STOPPING
        try {
            TProxyService.TProxyStopService()
        } catch (e: Throwable) {
            Log.w(TAG, "Error stopping hev-socks5-tunnel", e)
        }
        _state.value = Tun2SocksState.STOPPED
        Log.i(TAG, "hev-socks5-tunnel stopped")
    }

    // ── Query ─────────────────────────────────────────────────────

    /** Whether the tunnel's worker thread is alive, as reported by the library. */
    fun isRunning(): Boolean = try {
        libraryLoaded && TProxyService.TProxyIsRunning()
    } catch (e: Throwable) {
        false
    }

    /**
     * Traffic counters from the library, or null if it is not running.
     * Shape is defined by hev-socks5-tunnel.
     */
    fun stats(): LongArray? = try {
        if (libraryLoaded && TProxyService.TProxyIsRunning()) TProxyService.TProxyGetStats() else null
    } catch (e: Throwable) {
        null
    }

    // ── Config ────────────────────────────────────────────────────

    /**
     * Generate the YAML config via [HevSocks5TunnelConfigGenerator], pointed at
     * the local wstunnel SOCKS5 listener with DNS resolved over it.
     *
     * @return absolute path of the generated file
     */
    private fun generateDynamicConfig(): String =
        HevSocks5TunnelConfigGenerator.generate(context).getOrThrow()

    companion object {
        private const val TAG = "tun2socks"
    }
}
