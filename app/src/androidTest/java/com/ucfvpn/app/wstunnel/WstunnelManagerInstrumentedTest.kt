package com.ucfvpn.app.wstunnel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for how [WstunnelManager] locates its executable.
 *
 * The binary is no longer extracted from assets into `filesDir`: since Android
 * 10, an app with `targetSdk >= 29` is denied by SELinux from exec()ing files in
 * its own data directory, so it ships as `jniLibs/<abi>/libwstunnel.so` and runs
 * from `applicationInfo.nativeLibraryDir`.
 *
 * That makes the outcome ABI-dependent, and both branches are worth asserting:
 * the CI emulator is x86_64, for which no wstunnel build is packaged, so the
 * manager must fail with a clear diagnostic rather than silently reporting
 * success. On a device whose ABI IS packaged, the binary must be present and
 * executable.
 *
 * Requires an Android device or emulator with API 26+.
 */
@RunWith(AndroidJUnit4::class)
class WstunnelManagerInstrumentedTest {

    private lateinit var context: Context
    private lateinit var manager: WstunnelManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = WstunnelManager(context)
    }

    @After
    fun tearDown() {
        runBlocking { manager.stop() }
    }

    /** The executable as packaged for this device's ABI, if it was packaged at all. */
    private fun nativeBinary(): File =
        File(context.applicationInfo.nativeLibraryDir, "libwstunnel.so")

    // ──────────────────────────────────────────────────────
    //  Binary resolution
    // ──────────────────────────────────────────────────────

    @Test
    fun `start fails cleanly when no binary is packaged for this ABI`() {
        // Only meaningful where the ABI is unsupported (e.g. the x86_64 emulator).
        if (nativeBinary().exists()) return

        val result = runBlocking { manager.start(WstunnelConfig()) }

        assertTrue("start() must fail when the binary is absent", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(
            "The failure should name the missing binary, was: $message",
            message.contains("wstunnel binary not found")
        )
        assertEquals(WstunnelState.ERROR, manager.state.value)
        assertFalse("no process may be left behind", manager.isRunning())
    }

    @Test
    fun `packaged binary is executable and never copied into filesDir`() {
        val binary = nativeBinary()
        if (!binary.exists()) return // ABI not packaged; covered by the test above

        assertTrue("A packaged binary must be executable", binary.canExecute())

        // Nothing must be written to filesDir: exec() from there is denied.
        runBlocking { manager.start(WstunnelConfig(serverUrl = "not-a-valid-url")) }
        assertFalse(
            "the binary must not be copied into filesDir",
            File(context.filesDir, "libwstunnel.so").exists()
        )
    }

    @Test
    fun `start rejects an invalid server URL`() {
        // Only reachable where the binary resolves, since resolution comes first.
        if (!nativeBinary().exists()) return

        val result = runBlocking { manager.start(WstunnelConfig(serverUrl = "not-a-valid-url")) }

        assertTrue("start() should fail for an invalid server URL", result.isFailure)
        assertTrue(
            "Failure cause should be IllegalArgumentException",
            result.exceptionOrNull() is IllegalArgumentException
        )
        assertEquals(WstunnelState.ERROR, manager.state.value)
    }

    // ──────────────────────────────────────────────────────
    //  Stop without a running process
    // ──────────────────────────────────────────────────────

    @Test
    fun `stop without running process transitions to STOPPED`() {
        runBlocking { manager.stop() }

        assertEquals(
            "stop() with no process should leave the manager STOPPED",
            WstunnelState.STOPPED,
            manager.state.value
        )
        assertFalse("isRunning should be false", manager.isRunning())
    }
}
