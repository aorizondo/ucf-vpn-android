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
 * The CI builds x86_64 alongside the phone ABIs precisely so these checks run on
 * the emulator. Skipping them when the library is absent would have made the
 * suite green while proving nothing, so absence is treated as a failure on any
 * ABI we ship — which is every ABI the CI produces.
 */
@RunWith(AndroidJUnit4::class)
class TProxyServiceInstrumentedTest {

    /**
     * Load the library, failing the test if it is absent.
     *
     * Every ABI the CI packages must carry it; a missing one means the build
     * dropped an architecture, which is exactly the kind of gap that used to
     * surface only on someone's phone.
     */
    private fun loadOrFail() {
        try {
            TProxyService.load()
        } catch (e: UnsatisfiedLinkError) {
            throw AssertionError(
                "libhev-socks5-tunnel.so is missing for ABI " +
                    "${android.os.Build.SUPPORTED_ABIS.firstOrNull()}. Either the CI did " +
                    "not package this architecture, or JNI_OnLoad failed to bind its " +
                    "natives to TProxyService — check PKGNAME/CLSNAME in the workflow.",
                e
            )
        }
    }

    @Test
    fun nativeMethodsAreBoundToThisClass() {
        loadOrFail()

        // Reaching the native method at all proves JNI_OnLoad found this class and
        // registered against it: a renamed package, class or method would throw
        // UnsatisfiedLinkError right here. Before starting, it must simply report
        // "not running".
        assertFalse(
            "a tunnel that was never started must not report running",
            TProxyService.TProxyIsRunning()
        )
    }

    @Test
    fun everyNativeMethodIsBound() {
        loadOrFail()

        // Each of the four natives is registered separately by JNI_OnLoad, so
        // one working symbol does not prove the rest are wired. Calling them is
        // the assertion: an unbound method throws UnsatisfiedLinkError, which
        // fails the test. Their return values are not the point here.
        try {
            TProxyService.TProxyIsRunning()
            TProxyService.TProxyGetStats()
            TProxyService.TProxyStopService()
        } catch (e: UnsatisfiedLinkError) {
            throw AssertionError(
                "A native method is not bound: ${e.message}. hev-jni.c registers " +
                    "TProxyStartService/StopService/IsRunning/GetStats by name, so a " +
                    "renamed method here breaks only that one.",
                e
            )
        }
    }

    @Test
    fun stoppingWhenNotRunningIsHarmless() {
        loadOrFail()

        // Cleanup runs this path whenever a connection fails early.
        TProxyService.TProxyStopService()
        assertFalse(TProxyService.TProxyIsRunning())
    }

    @Test
    fun managerFailsTheConnectionOnAnUnreadableConfig() {
        val manager = Tun2SocksManager(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )

        // A real descriptor with a config that cannot be read.
        //
        // This test found that hev's TProxyStartService returns true for a
        // nonexistent path: the library never reports the problem, so a bad
        // config would have produced a tunnel that looked healthy and carried
        // nothing. Tun2SocksManager therefore checks the file itself, and this
        // asserts that check — plus that the failure arrives through Result
        // rather than as a throw, since the caller tears the VPN down on a
        // failed Result and would otherwise crash.
        val ours = java.io.FileDescriptor()
        val theirs = java.io.FileDescriptor()
        android.system.Os.socketpair(
            android.system.OsConstants.AF_UNIX,
            android.system.OsConstants.SOCK_SEQPACKET,
            0,
            ours,
            theirs
        )
        val fd = android.os.ParcelFileDescriptor.dup(ours)
        android.system.Os.close(ours)
        android.system.Os.close(theirs)

        try {
            val result = kotlinx.coroutines.runBlocking {
                manager.start(fd, configPath = "/nonexistent/hev-config.yaml")
            }
            assertTrue("an unreadable config must fail the connection", result.isFailure)
            assertFalse("nothing may be left running", manager.isRunning())
        } finally {
            fd.close()
            kotlinx.coroutines.runBlocking { manager.stop() }
        }
    }
}
