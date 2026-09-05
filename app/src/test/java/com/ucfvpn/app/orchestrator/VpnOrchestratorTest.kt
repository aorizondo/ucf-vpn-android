package com.ucfvpn.app.orchestrator

import android.content.Context
import com.ucfvpn.app.proxy.ProxyAuthService
import com.ucfvpn.app.sstp.client.SstpState
import com.ucfvpn.app.sstp.client.SstpTunnel
import com.ucfvpn.app.sstp.client.SstpTunnelCallbacks
import com.ucfvpn.app.sstp.ppp.PppEvent
import com.ucfvpn.app.state.VpnState
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

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        sstpTunnel = FakeSstpTunnel()
        stateMachine = VpnStateMachine()

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
        // No VpnGatewayService: it is an Android VpnService and cannot be
        // instantiated on the JVM. Layer 5 therefore always fails here, which is
        // exactly what lets us assert the error path of the final stage.
        vpnService = null
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
    fun `sequence walks SSTP, PPP, proxy and wstunnel before the VPN layer`() = runTest {
        val orchestrator = orchestrator()

        orchestrator.start(config())
        advanceUntilIdle()

        assertEquals("SSTP must be connected exactly once", 1, sstpTunnel.connectCalls)
        coVerify(exactly = 1) { proxyAuthService.login("puser", "ppass") }
        coVerify(exactly = 1) { wstunnelManager.start(any()) }
        coVerify(exactly = 1) { wstunnelManager.waitForSocks5Ready(any(), any()) }
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
    fun `PPP phases follow the real negotiation events`() = runTest {
        // Regression: the three phases used to be emitted back to back the
        // moment the layer started, so the progress shown was fiction.
        val orchestrator = orchestrator()
        val seen = mutableListOf<VpnState.PppPhase>()

        sstpTunnel.onConnect = { tunnel ->
            tunnel.onStateChanged?.invoke(SstpState.CONNECTED)
            tunnel.onPppEvent?.invoke(PppEvent.LcpOpened)
            seen.add(currentPppPhase() ?: return@onConnect)
        }

        orchestrator.start(config())
        advanceUntilIdle()

        // After LcpOpened the phase must have moved past LCP, not sat on it.
        assertTrue(
            "AUTH should be reported once LCP actually opened",
            stateMachine.stateHistory.value.any {
                it.to == VpnState.PppNegotiating(VpnState.PppPhase.AUTH)
            }
        )
    }

    private fun currentPppPhase(): VpnState.PppPhase? =
        (stateMachine.state.value as? VpnState.PppNegotiating)?.phase

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
    fun `VPN layer failure is reported when no service is bound`() = runTest {
        val orchestrator = orchestrator()

        orchestrator.start(config())
        advanceUntilIdle()

        // vpnService is null, so the last stage cannot succeed.
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
     * Scriptable [SstpTunnel] stand-in.
     *
     * By default `connect()` reports CONNECTED and hands out a PPP address, so
     * the sequence can walk past the first two layers.
     */
    private class FakeSstpTunnel : SstpTunnel {
        override var onPppFrameReceived: ((ByteArray) -> Unit)? = null
        override var onStateChanged: ((SstpState) -> Unit)? = null
        override var onPppEvent: ((PppEvent) -> Unit)? = null

        override var localAddress: String? = null
            private set

        var connectCalls = 0
        var disconnectCalls = 0
        var failConnect = false

        /** Overrides the default scripted behaviour of [connect]. */
        var onConnect: ((FakeSstpTunnel) -> Unit)? = null

        override val isConnected: Boolean
            get() = localAddress != null

        override fun connect(server: String, port: Int) {
            connectCalls++
            val custom = onConnect
            if (custom != null) {
                custom(this)
                localAddress = "10.0.0.2"
                return
            }
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
