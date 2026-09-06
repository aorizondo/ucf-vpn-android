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
 * Answers one question the whole tun2socks design rests on: **does a process
 * started with [ProcessBuilder] inherit a file descriptor from its parent?**
 *
 * Both `Tun2SocksManager` and the split-tunnel data path hand a descriptor
 * number to hev-socks5-tunnel on its command line (`-f <fd>`) and assume the
 * subprocess can use it. If Android's `ProcessBuilder` closes descriptors above
 * stderr on exec — as OpenJDK's `childProcess()` does via `closeDescriptors()` —
 * that number would point at nothing in the child and the tunnel could never
 * carry a packet, no matter how correct the rest of the code is.
 *
 * This cannot be settled by reading our own source, and it decides whether the
 * subprocess approach is viable at all or the JNI library that
 * hev-socks5-tunnel's `Android.mk` also builds is required instead.
 *
 * The check is deliberately independent of hev: it spawns `/system/bin/sh` and
 * asks it whether the descriptor exists in its own `/proc/self/fd`.
 */
@RunWith(AndroidJUnit4::class)
class FdInheritanceInstrumentedTest {

    @Test
    fun subprocessInheritsAnExplicitlyPassedFileDescriptor() {
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
                "A subprocess did NOT inherit fd $fd (reported: '$output'). " +
                    "Passing a descriptor number on the command line cannot work, so " +
                    "hev-socks5-tunnel must be driven through its JNI library instead " +
                    "of as a subprocess.",
                output.contains("YES")
            )
        } finally {
            wrappedOurs.close()
            wrappedTheirs.close()
        }
    }
}
