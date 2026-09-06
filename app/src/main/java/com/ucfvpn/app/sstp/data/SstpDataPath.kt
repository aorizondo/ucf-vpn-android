package com.ucfvpn.app.sstp.data

import android.os.ParcelFileDescriptor
import com.ucfvpn.app.sstp.ppp.PppConstants
import com.ucfvpn.app.sstp.ppp.buildPppFrame
import timber.log.Timber
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Moves IP packets between the TUN interface, the SSTP tunnel and the SOCKS5
 * path, which is what actually makes split tunnelling work.
 *
 * ## Why this exists
 * The routes added to `VpnService.Builder` only decide what Android puts INTO
 * the TUN; they say nothing about where it goes afterwards. The whole TUN
 * descriptor used to be handed to hev-socks5-tunnel, so every packet went to
 * the SOCKS5 proxy and the internal UCF sites the split is meant to reach were
 * unreachable while the VPN was up.
 *
 * ## How packets flow
 * ```
 *                     ┌── internal (10/8, ...) ──► PPP 0x0021 ──► SSTP tunnel
 * TUN ──► SplitRouter ┤
 *                     └── everything else ───────► socketpair ──► hev-socks5-tunnel
 * ```
 * and back the other way: frames arriving from SSTP and packets written by
 * hev-socks5-tunnel are both written to the TUN.
 *
 * ## The socketpair
 * A `VpnService` owns exactly one TUN descriptor and it cannot be shared, so
 * hev-socks5-tunnel is given one end of an `AF_UNIX`/`SOCK_SEQPACKET` socket
 * pair instead of the real TUN. It reads and writes raw IP packets on that
 * descriptor exactly as it would on a TUN, and SEQPACKET preserves the packet
 * boundaries a datagram interface needs.
 *
 * @param tunFd the TUN interface from `VpnService.Builder.establish()`
 * @param socks5Side the descriptor handed to hev-socks5-tunnel
 * @param router decides SSTP vs SOCKS5 per packet
 * @param sendToSstp delivers a raw PPP frame to the SSTP tunnel
 * @param mtu buffer size for reads; must be at least the interface MTU
 */
class SstpDataPath(
    private val tunFd: ParcelFileDescriptor,
    private val socks5Side: ParcelFileDescriptor,
    private val router: SplitRouter,
    private val sendToSstp: (ByteArray) -> Unit,
    private val mtu: Int = DEFAULT_MTU
) {
    private val running = AtomicBoolean(false)

    private var tunInput: FileInputStream? = null
    private var tunOutput: FileOutputStream? = null
    private var socksInput: FileInputStream? = null
    private var socksOutput: FileOutputStream? = null

    private var tunThread: Thread? = null
    private var socksThread: Thread? = null

    /** Packets routed into the SSTP tunnel since the last [start]. */
    @Volatile
    var internalPackets: Long = 0L
        private set

    /** Packets routed into the SOCKS5 path since the last [start]. */
    @Volatile
    var externalPackets: Long = 0L
        private set

    /**
     * Start both forwarding loops. Idempotent: a second call while running is
     * ignored.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) {
            Timber.tag(TAG).w("data path already running")
            return
        }

        internalPackets = 0
        externalPackets = 0

        tunInput = FileInputStream(tunFd.fileDescriptor)
        tunOutput = FileOutputStream(tunFd.fileDescriptor)
        socksInput = FileInputStream(socks5Side.fileDescriptor)
        socksOutput = FileOutputStream(socks5Side.fileDescriptor)

        tunThread = thread(name = "sstp-tun-reader", isDaemon = true) { pumpTun() }
        socksThread = thread(name = "sstp-socks-reader", isDaemon = true) { pumpSocks() }

        Timber.tag(TAG).d("data path started (mtu=%d)", mtu)
    }

    /**
     * Stop both loops and release the streams.
     *
     * The descriptors themselves belong to the caller: closing the streams here
     * unblocks the reads, and the owner closes the TUN and the socket pair.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return

        // Closing the streams is what breaks the blocking reads.
        closeQuietly { tunInput?.close() }
        closeQuietly { socksInput?.close() }
        closeQuietly { tunOutput?.close() }
        closeQuietly { socksOutput?.close() }

        tunThread?.join(THREAD_JOIN_TIMEOUT_MS)
        socksThread?.join(THREAD_JOIN_TIMEOUT_MS)

        tunInput = null
        tunOutput = null
        socksInput = null
        socksOutput = null
        tunThread = null
        socksThread = null

        Timber.tag(TAG).d(
            "data path stopped (internal=%d external=%d)", internalPackets, externalPackets
        )
    }

    /**
     * Feed an IP packet that arrived from the SSTP tunnel, writing it to the TUN
     * so the originating app receives the reply.
     *
     * @param packet the payload of a PPP frame with protocol 0x0021
     */
    fun onPacketFromSstp(packet: ByteArray) {
        if (!running.get()) return
        try {
            tunOutput?.write(packet)
        } catch (e: Exception) {
            if (running.get()) Timber.tag(TAG).w(e, "failed to write an SSTP packet to the TUN")
        }
    }

    /** TUN → SSTP (internal) or → hev-socks5-tunnel (everything else). */
    private fun pumpTun() {
        val buffer = ByteArray(mtu)
        val input = tunInput ?: return
        while (running.get()) {
            val read = try {
                input.read(buffer)
            } catch (e: Exception) {
                if (running.get()) Timber.tag(TAG).w(e, "TUN read failed")
                break
            }
            if (read <= 0) break

            val packet = buffer.copyOf(read)
            try {
                if (router.isInternal(packet)) {
                    // PPP protocol 0x0021 carries a raw IPv4 packet.
                    sendToSstp(buildPppFrame(PppConstants.PROTOCOL_IP, packet))
                    internalPackets++
                } else {
                    socksOutput?.write(packet)
                    externalPackets++
                }
            } catch (e: Exception) {
                if (running.get()) Timber.tag(TAG).w(e, "failed to forward a TUN packet")
            }
        }
    }

    /** hev-socks5-tunnel → TUN. */
    private fun pumpSocks() {
        val buffer = ByteArray(mtu)
        val input = socksInput ?: return
        while (running.get()) {
            val read = try {
                input.read(buffer)
            } catch (e: Exception) {
                if (running.get()) Timber.tag(TAG).w(e, "SOCKS5 read failed")
                break
            }
            if (read <= 0) break

            try {
                tunOutput?.write(buffer, 0, read)
            } catch (e: Exception) {
                if (running.get()) Timber.tag(TAG).w(e, "failed to write a SOCKS5 packet to the TUN")
            }
        }
    }

    private inline fun closeQuietly(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "ignored error while closing the data path")
        }
    }

    companion object {
        private const val TAG = "SstpDataPath"

        /** Matches the default TUN MTU, with room for the largest expected packet. */
        private const val DEFAULT_MTU = 1500

        private const val THREAD_JOIN_TIMEOUT_MS = 2_000L
    }
}
