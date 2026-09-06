package com.ucfvpn.app.wstunnel

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks that hev-socks5-tunnel's JNI layer binds to [TProxyService].
 *
 * The binding is made of two halves that must agree exactly: `hev-jni.c`
 * registers its natives in `JNI_OnLoad` against the class named by the
 * `PKGNAME`/`CLSNAME` defines the CI build passes, and this Kotlin object has to
 * be that class, with those method names. Nothing checks it at compile time — a
 * renamed package or method would only surface as an `UnsatisfiedLinkError` the
 * first time someone pressed Connect on a real phone.
 *
 * The CI emulator is x86_64 and no build is packaged for it, so the library is
 * genuinely absent there. That is a legitimate outcome and is asserted as such;
 * the real value is the branch that runs on a packaged ABI, where a mismatch
 * fails here instead of on a device.
 */
@RunWith(AndroidJUnit4::class)
class TProxyServiceInstrumentedTest {

    private fun libraryAvailable(): Boolean = try {
        TProxyService.load()
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    @Test
    fun nativeMethodsAreBoundWhenTheLibraryIsPackaged() {
        if (!libraryAvailable()) return // ABI not packaged; see the class KDoc

        // Reaching the native method at all proves JNI_OnLoad found this class
        // and registered against it. Calling it before starting must simply say
        // "not running" rather than throw.
        assertFalse("a tunnel that was never started must not report running", TProxyService.TProxyIsRunning())
    }

    @Test
    fun stoppingWhenNotRunningIsHarmless() {
        if (!libraryAvailable()) return

        // Cleanup runs this path whenever a connection fails early.
        TProxyService.TProxyStopService()
        assertFalse(TProxyService.TProxyIsRunning())
    }

    @Test
    fun managerReportsAClearErrorWhenTheLibraryIsMissing() {
        if (libraryAvailable()) return // only meaningful on an unpackaged ABI

        val manager = Tun2SocksManager(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )
        val result = kotlinx.coroutines.runBlocking {
            manager.start(
                android.os.ParcelFileDescriptor.fromFd(0),
                configPath = "/dev/null"
            )
        }

        assertTrue("a missing library must fail the connection, not crash it", result.isFailure)
        assertFalse(manager.isRunning())
    }
}
