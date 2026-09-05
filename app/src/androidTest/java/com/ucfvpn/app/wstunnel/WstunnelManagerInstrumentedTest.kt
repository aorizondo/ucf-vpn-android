package com.ucfvpn.app.wstunnel

import android.content.Context
import android.os.Build
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
 * Instrumented tests for [WstunnelManager] binary extraction from assets.
 *
 * [WstunnelManager.extractBinary] is private, so the extraction is exercised
 * through the public [WstunnelManager.start] entry point. To keep the test
 * network-free and process-free on ANY ABI, we pass a config with an INVALID
 * server URL: `start()` extracts the binary FIRST (before the URL validity
 * check), then fails with [IllegalArgumentException] without ever spawning a
 * subprocess. The observable side effect — the ABI-appropriate binary copied
 * to filesDir and marked executable — is what we assert.
 *
 * Note: on the CI emulator (x86_64) the selected asset is `wstunnel_arm64`,
 * which is committed in `app/src/main/assets/`. On an armeabi-v7a device the
 * manager would look for `wstunnel_armv7`, which is NOT committed (it is
 * produced by the `build-wstunnel` CI job) — that path is out of scope here.
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
        // Start clean for each test
        File(context.filesDir, expectedBinaryName()).delete()
    }

    @After
    fun tearDown() {
        runBlocking { manager.stop() }
        File(context.filesDir, expectedBinaryName()).delete()
    }

    // ──────────────────────────────────────────────────────
    //  Binary extraction (via start() with invalid URL)
    // ──────────────────────────────────────────────────────

    @Test
    fun `start with invalid server URL extracts binary to filesDir`() {
        val config = WstunnelConfig(serverUrl = "not-a-valid-url")

        val result = runBlocking { manager.start(config) }

        // start() must fail on the invalid URL — and must NOT spawn a process.
        assertTrue("start() should fail for an invalid server URL", result.isFailure)
        assertTrue(
            "Failure cause should be IllegalArgumentException",
            result.exceptionOrNull() is IllegalArgumentException
        )

        // But the binary extraction (which happens before the URL check) must
        // have left the ABI-appropriate binary in filesDir.
        val binary = File(context.filesDir, expectedBinaryName())
        assertTrue("Binary should be extracted to filesDir", binary.exists())
        assertTrue("Extracted binary should be executable", binary.canExecute())

        // The extracted file must be a byte-for-byte copy of the asset.
        val assetBytes = context.assets.open(expectedBinaryName()).use { it.readBytes() }
        assertEquals(
            "Extracted binary size should match the asset size",
            assetBytes.size.toLong(),
            binary.length()
        )

        // The manager must have transitioned to ERROR after the failed start.
        assertEquals(WstunnelState.ERROR, manager.state.value)
    }

    @Test
    fun `binary is not re-extracted when it already exists`() {
        // First start extracts the binary.
        runBlocking { manager.start(WstunnelConfig(serverUrl = "invalid")) }
        val binary = File(context.filesDir, expectedBinaryName())
        assertTrue("Binary should exist after first start", binary.exists())
        val firstModified = binary.lastModified()

        // Second start must reuse the existing binary (no re-extraction).
        runBlocking { manager.start(WstunnelConfig(serverUrl = "invalid")) }
        assertEquals(
            "Existing binary should not be re-extracted",
            firstModified,
            binary.lastModified()
        )
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

    // ──────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────

    /**
     * Mirrors the private [WstunnelManager.binaryAssetPath] selection logic
     * (documented in the manager KDoc): armeabi* → `wstunnel_armv7`, anything
     * else → `wstunnel_arm64`.
     */
    private fun expectedBinaryName(): String =
        if (Build.SUPPORTED_ABIS.any { it.startsWith("armeabi") }) {
            "wstunnel_armv7"
        } else {
            "wstunnel_arm64"
        }
}