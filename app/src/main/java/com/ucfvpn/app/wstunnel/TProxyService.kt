package com.ucfvpn.app.wstunnel

/**
 * Kotlin side of hev-socks5-tunnel's own JNI layer (`src/hev-jni.c`).
 *
 * The library is driven in-process rather than as a subprocess because a
 * subprocess never receives the TUN descriptor: Android closes descriptors above
 * stderr on exec, as `FdInheritanceInstrumentedTest` measures. The `-f <fd>`
 * command line the old `Tun2SocksManager` used therefore pointed at nothing in
 * the child, and no packet could ever have crossed the tunnel.
 *
 * ## Binding contract
 * `hev-jni.c` registers its natives in `JNI_OnLoad` against the class named by
 * its `PKGNAME` and `CLSNAME` compile-time defines, so the CI build sets those
 * to this package and class. Get either side wrong and `JNI_OnLoad` returns
 * `JNI_ERR`, which surfaces as [UnsatisfiedLinkError] on load — checked by
 * `TProxyServiceInstrumentedTest` so a mismatch fails in CI, not on a device.
 *
 * The method names are fixed by the library and intentionally not renamed to
 * Kotlin conventions: they are matched by string in the native registration.
 */
object TProxyService {

    /**
     * Start the tunnel.
     *
     * @param configPath absolute path of the YAML configuration
     * @param fd descriptor the tunnel reads and writes IP packets on. Valid
     *   because the library runs in this process.
     */
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    /** Stop the tunnel and join its worker thread. */
    external fun TProxyStopService(): Boolean

    /** Whether the tunnel's worker thread is running. */
    external fun TProxyIsRunning(): Boolean

    /** Traffic counters, as reported by the library. */
    external fun TProxyGetStats(): LongArray

    /**
     * Load the native library.
     *
     * @throws UnsatisfiedLinkError if it is missing for this ABI, or if
     *   `JNI_OnLoad` could not bind its natives to this class
     */
    fun load() {
        System.loadLibrary(LIBRARY_NAME)
    }

    /** Matches `libhev-socks5-tunnel.so` under `jniLibs/<abi>/`. */
    const val LIBRARY_NAME = "hev-socks5-tunnel"
}
