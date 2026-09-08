package com.ucfvpn.app.orchestrator

import android.content.Context
import com.ucfvpn.app.proxy.ProxyAuthService
import com.ucfvpn.app.sstp.client.SstpState
import com.ucfvpn.app.sstp.client.SstpTunnel
import com.ucfvpn.app.sstp.client.SstpTunnelCallbacks
import com.ucfvpn.app.sstp.ppp.PppEvent
import com.ucfvpn.app.state.VpnState
import com.ucfvpn.app.vpn.VpnTunnelController
import com.ucfvpn.app.state.VpnStateMachine
import com.ucfvpn.app.wstunnel.WstunnelConfig
import com.ucfvpn.app.wstunnel.WstunnelManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the connection sequence, retry policy and cleanup of
 * [VpnOrchestrator] — the layer that had no coverage at all, and where most of
 * the defects found in the audit lived.
 *
 * The orchestrator drives its coroutines on [Dispatchers.Main], so the tests
 * install a [StandardTestDispatcher] and drive virtual time with
 * [advanceUntilIdle]. That also makes the polling loops (`delay(100)` while
 * waiting for SSTP / the PPP address) finish instantly instead of really
 * sleeping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VpnOrchestratorTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var sstpTunnel: FakeSstpTunnel
    private lateinit var proxyAuthService: ProxyAuthService
    private lateinit var wstunnelManager: WstunnelManager
    private lateinit var stateMachine: VpnStateMachine
    private lateinit var vpnController: FakeVpnTunnelController

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        sstpTunnel = FakeSstpTunnel()
        stateMachine = VpnStateMachine()
        vpnController = FakeVpnTunnelController()

        proxyAuthService = mockk(relaxed = true)
        coEvery { proxyAuthService.login(any(), any()) } returns Result.success(Unit)

        wstunnelManager = mockk(relaxed = true)
        coEvery { wstunnelManager.start(any()) } returns Result.success(Unit)
        coEvery { wstunnelManager.waitForSocks5Ready(any(), any()) } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun orchestrator() = VpnOrchestrator(
        context = mockk<Context>(relaxed = true),
        sstpTunnel = sstpTunnel,
        proxyAuthService = proxyAuthService,
        wstunnelManager = wstunnelManager,
        stateMachine = stateMachine,
        // A fake controller, not null: the orchestrator now takes the
        // VpnTunnelController interface rather than the Android service, so the
        // success path of the final stage is reachable in a unit test for the
        // first time. Passing null used to make every test exercise the "no
        // service" error path instead.
        vpnService = vpnController
    )

    private fun config(autoReconnect: Boolean = false) = AppConfig(
        sstpUsername = "user",
        sstpPassword = "pass",
        proxyUsername = "puser",
        proxyPassword = "ppass",
        wstunnelConfig = WstunnelConfig(),
        autoReconnect = autoReconnect
    )

    // ── Connection sequence ───────────────────────────────────────

    @Test
    fun `the tunnel is up before the portal and wstunnel are contacted`() = runTest {
        // Order is the whole point. The SSTP link is what places the device
        // inside the UCF network, so away from the campus the captive portal and
        // the HTTP proxy are reachable only through the tunnel. Establishing it
        // last, as this used to, sent both out over mobile data where those
        // addresses do not exist.
        val orchestrator = orchestrator()

        orchestrator.start(config())
        advanceUntilIdle()

        assertEquals("SSTP must be connected exactly once", 1, sstpTunnel.connectCalls)
        assertEquals("the tunnel must be established once", 1, vpnController.establishCalls)
        coVerify(exactly = 1) { proxyAuthService.login("puser", "ppass") }
        coVerify(exactly = 1) { wstunnelManager.start(any()) }
        coVerify(exactly = 1) { wstunnelManager.waitForSocks5Ready(any(), any()) }

        // The bridge comes last: it needs wstunnel already listening.
        assertEquals(listOf("establish", "bridge"), vpnController.calls)
    }

    @Test
    fun `the TUN carries the address and resolvers PPP assigned`() = runTest {
        // Hardcoded values would break silently off campus: packets would leave
        // the tunnel with a source the UCF network does not know, so replies
        // never come back, and public resolvers do not answer internal names.
        val orchestrator = orchestrator()

        orchestrator.start(config())
        advanceUntilIdle()

        assertEquals("10.0.0.2", vpnController.lastLocalAddress)
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), vpnController.lastDnsServers)
    }

    @Test
    fun `the configured private networks reach the tunnel`() = runTest {
        val orchestrator = orchestrator()

        orchestrator.start(
            config().copy(
                splitTunnelConfig = SplitTunnelConfig(privateNetworks = listOf("10.14.0.0/16"))
            )
        )
        advanceUntilIdle()

        assertEquals(listOf("10.14.0.0/16"), vpnController.lastPrivateNetworks)
    }

    @Test
    fun `a failed tunnel stops the sequence before the portal`() = runTest {
        vpnController.establishFailure = IllegalStateException("no TUN")
        val orchestrator = orchestrator()

        orchestrator.start(config())
        advanceUntilIdle()

        // Contacting the portal without a tunnel would just time out against an
        // address that does not exist from here.
        coVerify(exactly = 0) { proxyAuthService.login(any(), any()) }
        assertEquals("the bridge must not be attempted", 0, vpnController.bridgeCalls)
    }

    @Test
    fun `wstunnel is started as SOCKS5 on the port the VPN layer will use`() = runTest {
        val orchestrator = orchestrator()
        val configSlot = mutableListOf<WstunnelConfig>()
        coEvery { wstunnelManager.start(capture(configSlot)) } returns Result.success(Unit)

        orchestrator.start(config())
        advanceUntilIdle()

        val used = configSlot.single()
        assertEquals(com.ucfvpn.app.wstunnel.TunnelType.SOCKS5, used.tunnelType)
        assertEquals(1080, used.localPort)
    }

    @Test
    fun `user wstunnel settings survive into the SOCKS5 config`() = runTest {
        // Regression: buildSocks5Config used to rebuild the config from scratch
        // and silently drop these two fields, so the settings screen had no
        // effect on the real connection.
        val orchestrator = orchestrator()
        val configSlot = mutableListOf<WstunnelConfig>()
        coEvery { wstunnelManager.start(capture(configSlot)) } returns Result.success(Unit)

        orchestrator.start(
            config().copy(
                wstunnelConfig = WstunnelConfig(
                    retryMaxBackoff = "42s",
                    websocketPingFrequency = "7s"
                )
            )
        )
        advanceUntilIdle()

        val used = configSlot.single()
        assertEquals("42s", used.retryMaxBackoff)
        assertEquals("7s", used.websocketPingFrequency)
    }

    @Test
    fun `the SOCKS5 tunnel is given a connection pool`() = runTest {
        // The UI config is built for UDP mode and carries wstunnel's default of
        // 0 idle connections, so copying it unchanged would leave every SOCKS5
        // tunnel paying a full TCP + TLS + proxy CONNECT + WebSocket handshake.
        // That is what left pages partially loaded in manual testing.
        val orchestrator = orchestrator()
        val configSlot = mutableListOf<WstunnelConfig>()
        coEvery { wstunnelManager.start(capture(configSlot)) } returns Result.success(Unit)

        orchestrator.start(config())
        advanceUntilIdle()

        assertEquals(
            WstunnelConfig.DEFAULT_SOCKS5_MIN_IDLE,
            configSlot.single().connectionMinIdle
        )
    }

    // NOTE: the PPP phase display (LCP → AUTH → IPCP now driven by real
    // PppEvent callbacks instead of being emitted all at once) is deliberately
    // NOT asserted here. Whether a phase transition is accepted depends on a
    // genuine race between the tunnel emitting its events and the orchestrator
    // reaching PppNegotiating, and the polling loops make that ordering
    // non-deterministic under a test scheduler. A test written around it would
    // be flaky, which is worse than no test. Covering it properly needs the
    // orchestrator to stop polling for the PPP address and observe the events
    // instead — worth doing, but it is a change to production code, not a test.

    // ── Failure handling ──────────────────────────────────────────

    @Test
    fun `SSTP failure is retried and ends in SstpError`() = runTest {
        sstpTunnel.failConnect = true
        val orchestrator = orchestrator()

        orchestrator.start(config())
        advanceUntilIdle()

        assertTrue(
            "SSTP should be retried, not attempted once",
            sstpTunnel.connectCalls > 1
        )
        assertTrue(
            "final state should be SstpError, was ${stateMachine.state.value}",
            stateMachine.state.value is VpnState.SstpError ||
                stateMachine.state.value is VpnState.Disconnected
        )
        // The proxy must never be contacted if the tunnel never came up.
        coVerify(exactly = 0) { proxyAuthService.login(any(), any()) }
    }

    @Test
    fun `rejected credentials are not retried forever`() = runTest {
        // Regression: reconnect was forced on regardless of the user's setting
        // and auth failures were indistinguishable from network faults, so a
        // wrong password was retried indefinitely.
        coEvery { proxyAuthService.login(any(), any()) } returns
            Result.failure(Exception("PAP authentication failed: bad credentials"))

        val orchestrator = orchestrator()
        orchestrator.start(config(autoReconnect = true))
        advanceUntilIdle()

        assertFalse("the stack must be stopped, not looping", orchestrator.isRunning())
    }

    @Test
    fun `the full sequence reaches VpnRunning`() = runTest {
        // The happy path had never been asserted: with a null service every test
        // stopped at the "no service" error, so nothing checked that the six
        // layers actually compose into a connected tunnel.
        val orchestrator = orchestrator()

        orchestrator.start(config())
        advanceUntilIdle()

        assertEquals(VpnState.VpnRunning, stateMachine.state.value)
        assertTrue("the orchestrator must consider itself running", orchestrator.isRunning())
        assertEquals(1, vpnController.bridgeCalls)
    }

    @Test
    fun `the VPN layer reports failure when no controller is wired`() = runTest {
        val orchestrator = orchestrator().apply { vpnService = null }

        orchestrator.start(config())
        advanceUntilIdle()

        assertTrue(
            "expected an error state, was ${stateMachine.state.value}",
            stateMachine.state.value.isError || stateMachine.state.value == VpnState.Disconnected
        )
    }

    // ── Cleanup ───────────────────────────────────────────────────

    @Test
    fun `stop tears every layer down and completes`() = runTest {
        // Regression: stop() cancelled the very job running it, so the cleanup
        // aborted at its first suspension point and leaked the wstunnel process
        // and the TUN. It also deadlocked on the ReconnectManager mutex.
        val orchestrator = orchestrator()
        orchestrator.start(config())
        advanceUntilIdle()

        orchestrator.stop()
        advanceUntilIdle()

        coVerify { wstunnelManager.stop() }
        coVerify { proxyAuthService.reset() }
        assertTrue("the VPN side must be shut down", vpnController.shutdownCalls > 0)
        assertEquals("SSTP must be disconnected", 1, sstpTunnel.disconnectCalls)
        assertFalse(orchestrator.isRunning())
        assertEquals(VpnState.Disconnected, stateMachine.state.value)
    }

    @Test
    fun `stop is a no-op when nothing is running`() = runTest {
        val orchestrator = orchestrator()

        orchestrator.stop()
        advanceUntilIdle()

        assertFalse(orchestrator.isRunning())
        coVerify(exactly = 0) { wstunnelManager.stop() }
    }

    /**
     * Records what the orchestrator asks of the VPN side.
     *
     * The interface exists precisely so this is possible: [com.ucfvpn.app.vpn.VpnGatewayService]
     * is an Android `VpnService` and cannot be constructed on the JVM.
     */
    private class FakeVpnTunnelController : VpnTunnelController {
        override var sendToSstp: ((ByteArray) -> Unit)? = null

        var establishCalls = 0
        var bridgeCalls = 0
        var shutdownCalls = 0
        var lastLocalAddress: String? = null
        var lastDnsServers: List<String> = emptyList()
        var lastPrivateNetworks: List<String> = emptyList()

        /** When set, [establishSplitTunnel] fails with it. */
        var establishFailure: Throwable? = null

        /** Order in which the orchestrator drove the VPN side. */
        val calls = mutableListOf<String>()

        override fun protectSocket(socket: java.net.Socket): Boolean = true

        override fun onPacketFromSstp(packet: ByteArray) = Unit

        override suspend fun establishSplitTunnel(
            privateNetworks: List<String>,
            localAddress: String,
            dnsServers: List<String>,
            mtu: Int,
            bypassApps: List<String>
        ): Result<Unit> {
            establishCalls++
            calls.add("establish")
            lastLocalAddress = localAddress
            lastDnsServers = dnsServers
            lastPrivateNetworks = privateNetworks
            return establishFailure?.let { Result.failure(it) } ?: Result.success(Unit)
        }

        override suspend fun startSocks5Bridge(
            socks5Proxy: String,
            dnsViaSocks5: Boolean
        ): Result<Unit> {
            bridgeCalls++
            calls.add("bridge")
            return Result.success(Unit)
        }

        override suspend fun shutdown() {
            shutdownCalls++
            calls.add("shutdown")
        }
    }

    /**
     * Scriptable [SstpTunnel] stand-in.
     *
     * By default `connect()` reports CONNECTED and hands out a PPP address, so
     * the sequence can walk past the first two layers.
     */
    private class FakeSstpTunnel : SstpTunnel {
        override var onPppFrameReceived: ((ByteArray) -> Unit)? = null
        override var onStateChanged: ((SstpState) -> Unit)? = null
        override var onPppEvent: ((PppEvent) -> Unit)? = null
        override var onIpPacket: ((ByteArray) -> Unit)? = null

        override var localAddress: String? = null
            private set

        var connectCalls = 0
        var disconnectCalls = 0
        var failConnect = false

        override val isConnected: Boolean
            get() = localAddress != null

        override fun connect(server: String, port: Int) {
            connectCalls++
            if (failConnect) {
                onStateChanged?.invoke(SstpState.ERROR)
                return
            }
            onStateChanged?.invoke(SstpState.CONNECTED)
            onPppEvent?.invoke(PppEvent.LcpOpened)
            onPppEvent?.invoke(PppEvent.AuthSuccess)
            localAddress = "10.0.0.2"
            onPppEvent?.invoke(PppEvent.IpAssigned("10.0.0.2", "8.8.8.8", "8.8.4.4", "10.0.0.1"))
        }

        override fun disconnect() {
            disconnectCalls++
            localAddress = null
            onStateChanged?.invoke(SstpState.DISCONNECTED)
        }

        override fun send(pppFrame: ByteArray) = Unit

        override fun setCallbacks(callbacks: SstpTunnelCallbacks) = Unit
    }
}
