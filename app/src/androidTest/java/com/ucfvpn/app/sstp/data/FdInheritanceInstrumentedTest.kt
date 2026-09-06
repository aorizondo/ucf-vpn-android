package com.ucfvpn.app.sstp.data

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileDescriptor

/**
 * Records a platform fact the whole tun2socks design used to rest on: **a
 * process started with [ProcessBuilder] does NOT inherit its parent's file
 * descriptors.**
 *
 * Measured on the CI emulator (API 29) on 2026-09-06: the child reported `NO`.
 * Android's `ProcessBuilder` closes descriptors above stderr on exec, the same
 * way OpenJDK's `childProcess()` does via `closeDescriptors()`.
 *
 * That invalidated the original `Tun2SocksManager`: it handed hev-socks5-tunnel
 * a descriptor number on its command line (`-f <fd>`) and assumed the subprocess
 * could use it. The number pointed at nothing in the child, so the tunnel could
 * never have carried a single packet however correct the rest of the code was —
 * and nothing revealed it, because the code had never run. hev-socks5-tunnel is
 * therefore driven through its JNI library, which runs in this process and needs
 * no inheritance at all.
 *
 * The test is kept as a regression guard: were Android ever to start passing
 * descriptors through, it would fail and invite a simpler design. It is
 * deliberately independent of hev — it spawns `/system/bin/sh` and asks it
 * whether the descriptor exists in its own `/proc/self/fd`.
 */
@RunWith(AndroidJUnit4::class)
class FdInheritanceInstrumentedTest {

    @Test
    fun subprocessDoesNotInheritFileDescriptors() {
        val ours = FileDescriptor()
        val theirs = FileDescriptor()
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0, ours, theirs)

        val wrappedOurs = ParcelFileDescriptor.dup(ours)
        val wrappedTheirs = ParcelFileDescriptor.dup(theirs)
        Os.close(ours)
        Os.close(theirs)

        try {
            val fd = wrappedTheirs.fd

            // The child reports whether the descriptor it was told about exists.
            val process = ProcessBuilder(
                listOf("/system/bin/sh", "-c", "if [ -e /proc/self/fd/$fd ]; then echo YES; else echo NO; fi")
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()

            assertTrue(
                "A subprocess unexpectedly INHERITED fd $fd (reported: '$output'). " +
                    "Android used to close descriptors on exec, which is why " +
                    "hev-socks5-tunnel is driven through JNI. If this now holds, the " +
                    "simpler subprocess approach is available again.",
                output.contains("NO")
            )
        } finally {
            wrappedOurs.close()
            wrappedTheirs.close()
        }
    }
}
