package com.ucfvpn.app.orchestrator

import com.ucfvpn.app.proxy.ProxyAuthState
import com.ucfvpn.app.proxy.ProxyAuthService
import com.ucfvpn.app.sstp.client.SstpState
import com.ucfvpn.app.sstp.client.SstpTunnel
import com.ucfvpn.app.state.ReconnectManager
import com.ucfvpn.app.state.VpnState
import com.ucfvpn.app.state.VpnStateMachine
import com.ucfvpn.app.vpn.VpnConfig
import com.ucfvpn.app.vpn.VpnGatewayService
import com.ucfvpn.app.vpn.WireGuardState
import com.ucfvpn.app.vpn.WireGuardManager
import com.ucfvpn.app.wg.WireGuardConfig
import com.ucfvpn.app.wg.WireGuardConfigRepository
import com.ucfvpn.app.wstunnel.WstunnelConfig
import com.ucfvpn.app.wstunnel.WstunnelManager
import com.ucfvpn.app.wstunnel.WstunnelState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeoutException

/**
 * Unit tests for VpnOrchestrator.
 *
 * Tests the full VPN stack orchestration with mocked components.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VpnOrchestratorTest {

    // ── Mock components ────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    private lateinit var mockContext: android.content.Context
    private lateinit var mockVpnService: VpnGatewayService
    private lateinit var mockSstpTunnel: SstpTunnel
    private lateinit var mockProxyAuthService: ProxyAuthService
    private lateinit var mockWstunnelManager: WstunnelManager
    private lateinit var mockWireGuardConfigRepository: WireGuardConfigRepository
    private lateinit var mockReconnectManager: ReconnectManager

    // State flows for mocks
    private val sstpStateFlow = MutableStateFlow(SstpState.DISCONNECTED)
    private val proxyAuthStateFlow = MutableStateFlow(ProxyAuthState.IDLE)
    private val wstunnelStateFlow = MutableStateFlow(WstunnelState.STOPPED)
    private val wireGuardStateFlow = MutableStateFlow(WireGuardState.STOPPED)

    // Test dispatcher
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    // Test configuration
    private val testConfig = AppConfig(
        sstpServer = "npv.ucf.edu.cu",
        sstpPort = 443,
        sstpUsername = "testuser",
        sstpPassword = "testpass",
        proxyUsername = "proxyuser",
        proxyPassword = "proxypass",
        wgConfig = WireGuardConfig(
            privateKey = "test-private-key",
            address = "10.8.0.2/24",
            dns = listOf("1.1.1.1"),
            peerPublicKey = "test-peer-key",
            peerEndpoint = "127.0.0.1:51820",
            allowedIps = listOf("0.0.0.0/0")
        ),
        wstunnelConfig = WstunnelConfig()
    )

    // ── Setup ─────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    @Before
    fun setup() {
        // Create mock context
        mockContext = mockk(relaxed = true)

        // Create mock VpnGatewayService
        mockVpnService = mockk(relaxed = true) {
            every { getWireGuardState() } returns WireGuardState.STOPPED
            every { shutdown() } returns Unit
        }

        // Create mock SstpTunnel
        mockSstpTunnel = mockk(relaxed = true) {
            every { state } returns SstpState.DISCONNECTED
            every { localAddress } returns null
            every { isConnected } returns false
            every { onStateChanged } returns null
            every { onPppFrameReceived } returns null
        }

        // Create mock ProxyAuthService
        mockProxyAuthService = mockk(relaxed = true) {
            every { authState } returns proxyAuthStateFlow
            every { reset() } returns Unit
        }

        // Create mock WstunnelManager
        mockWstunnelManager = mockk(relaxed = true) {
            every { state } returns wstunnelStateFlow
            every { stop() } returns Unit
        }

        // Create mock WireGuardConfigRepository
        mockWireGuardConfigRepository = mockk(relaxed = true)

        // Create mock ReconnectManager
        mockReconnectManager = mockk(relaxed = true)
    }

    private fun createOrchestrator(
        stateMachine: VpnStateMachine = VpnStateMachine(),
        reconnectManager: ReconnectManager? = null
    ): VpnOrchestrator {
        return VpnOrchestrator(
            context = mockContext,
            vpnService = mockVpnService,
            sstpTunnel = mockSstpTunnel,
            proxyAuthService = mockProxyAuthService,
            wstunnelManager = mockWstunnelManager,
            wireGuardConfigRepository = mockWireGuardConfigRepository,
            stateMachine = stateMachine,
            reconnectManager = reconnectManager
        )
    }

    // ── Constructor Tests ─────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `orchestrator initializes with Disconnected state`() = testScope.runTest {
        val orchestrator = createOrchestrator()
        advanceUntilIdle()

        assertEquals(VpnState.Disconnected, orchestrator.state.value)
        assertFalse(orchestrator.isRunning())
    }

    @Test
    fun `orchestrator exposes state flow from state machine`() = testScope.runTest {
        val stateMachine = VpnStateMachine()
        val orchestrator = createOrchestrator(stateMachine = stateMachine)
        advanceUntilIdle()

        assertEquals(stateMachine.state.value, orchestrator.state.value)
    }

    // ── Full Connection Sequence Tests ─────────────────────────────
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `start initiates full connection sequence`() = testScope.runTest {
        val orchestrator = createOrchestrator()

        // Setup mock behaviors for successful connection
        setupSuccessfulConnectionMocks()

        // Start connection
        orchestrator.start(testConfig)
        advanceUntilIdle()

        // Verify all components were called
        verify { mockSstpTunnel.connect(testConfig.sstpServer, testConfig.sstpPort) }
        verify { mockProxyAuthService.login(testConfig.proxyUsername, testConfig.proxyPassword) }
        verify { mockWstunnelManager.start(testConfig.wstunnelConfig) }
        verify { mockVpnService.startWithWireGuard(testConfig.wgConfig, any()) }
    }

    @Test
    fun `connection sequence transitions through all states`() = testScope.runTest {
        val stateMachine = VpnStateMachine()
        val orchestrator = createOrchestrator(stateMachine = stateMachine)

        // Track state transitions
        val states = mutableListOf<VpnState>()
        testScope.launch {
            orchestrator.state.collect { state ->
                states.add(state)
            }
        }

        // Setup mocks to properly transition states
        setupSuccessfulConnectionMocks()

        // Start connection
        orchestrator.start(testConfig)
        advanceUntilIdle()

        // Verify state transitions
        assertTrue("Should have SstpConnecting", states.contains(VpnState.SstpConnecting))
        assertTrue("Should have SstpConnected", states.contains(VpnState.SstpConnected))
        assertTrue("Should have ProxyAuthenticating", states.contains(VpnState.ProxyAuthenticating))
        assertTrue("Should have ProxyAuthenticated", states.contains(VpnState.ProxyAuthenticated))
        assertTrue("Should have WstunnelStarting", states.contains(VpnState.WstunnelStarting))
        assertTrue("Should have WstunnelRunning", states.contains(VpnState.WstunnelRunning))
        assertTrue("Should have WireGuardConnecting", states.contains(VpnState.WireGuardConnecting))
        assertTrue("Should have WireGuardConnected", states.contains(VpnState.WireGuardConnected))
        assertTrue("Should have VpnStarting", states.contains(VpnState.VpnStarting))
        assertTrue("Should have VpnRunning", states.contains(VpnState.VpnRunning))
    }

    @Test
    fun `stop performs clean disconnection of all components`() = testScope.runTest {
        val orchestrator = createOrchestrator()

        // Start then stop
        orchestrator.start(testConfig)
        advanceUntilIdle()
        orchestrator.stop()
        advanceUntilIdle()

        // Verify cleanup sequence
        verify { mockVpnService.shutdown() }
        verify { mockWstunnelManager.stop() }
        verify { mockProxyAuthService.reset() }
        verify { mockSstpTunnel.disconnect() }
        assertFalse(orchestrator.isRunning())
    }

    @Test
    fun `isRunning returns true when connected`() = testScope.runTest {
        val orchestrator = createOrchestrator()
        setupSuccessfulConnectionMocks()

        orchestrator.start(testConfig)
        advanceUntilIdle()

        assertTrue(orchestrator.isRunning())
    }

    @Test
    fun `isRunning returns false after stop`() = testScope.runTest {
        val orchestrator = createOrchestrator()
        setupSuccessfulConnectionMocks()

        orchestrator.start(testConfig)
        advanceUntilIdle()
        orchestrator.stop()
        advanceUntilIdle()

        assertFalse(orchestrator.isRunning())
    }

    // ── Error Handling Tests ───────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `SSTP error transitions to SstpError state`() = testScope.runTest {
        val stateMachine = VpnStateMachine()
        val orchestrator = createOrchestrator(stateMachine = stateMachine)

        // Mock SSTP connection to fail
        every { mockSstpTunnel.connect(any(), any()) } answers {
            // Simulate error state on tunnel
            sstpStateFlow.value = SstpState.ERROR
        }

        orchestrator.start(testConfig)
        advanceUntilIdle()

        val state = orchestrator.state.value
        assertTrue("State should be SstpError but was $state", state is VpnState.SstpError)
    }

    @Test
    fun `proxy error transitions to ProxyError state`() = testScope.runTest {
        val stateMachine = VpnStateMachine()
        val orchestrator = createOrchestrator(stateMachine = stateMachine)

        // Setup SSTP to succeed
        every { mockSstpTunnel.connect(any(), any()) } answers {
            sstpStateFlow.value = SstpState.CONNECTED
        }
        every { mockSstpTunnel.state } returns SstpState.CONNECTED
        every { mockSstpTunnel.localAddress } returns "10.0.0.1"

        // Mock proxy auth to fail
        coEvery { mockProxyAuthService.login(any(), any()) } returns Result.failure(
            Exception("Auth failed")
        )

        orchestrator.start(testConfig)
        advanceUntilIdle()

        val state = orchestrator.state.value
        assertTrue("State should be ProxyError but was $state", state is VpnState.ProxyError)
    }

    @Test
    fun `wstunnel error transitions to WstunnelError state`() = testScope.runTest {
        val stateMachine = VpnStateMachine()
        val orchestrator = createOrchestrator(stateMachine = stateMachine)

        // Setup SSTP to succeed
        every { mockSstpTunnel.connect(any(), any()) } answers {
            sstpStateFlow.value = SstpState.CONNECTED
        }
        every { mockSstpTunnel.state } returns SstpState.CONNECTED
        every { mockSstpTunnel.localAddress } returns "10.0.0.1"

        // Setup proxy to succeed
        coEvery { mockProxyAuthService.login(any(), any()) } returns Result.success(Unit)
        proxyAuthStateFlow.value = ProxyAuthState.AUTHENTICATED

        // Mock wstunnel to fail
        coEvery { mockWstunnelManager.start(any()) } returns Result.failure(
            Exception("wstunnel failed")
        )

        orchestrator.start(testConfig)
        advanceUntilIdle()

        val state = orchestrator.state.value
        assertTrue("State should be WstunnelError but was $state", state is VpnState.WstunnelError)
    }

    @Test
    fun `WireGuard error transitions to WireGuardError state`() = testScope.runTest {
        val stateMachine = VpnStateMachine()
        val orchestrator = createOrchestrator(stateMachine = stateMachine)

        // Setup SSTP to succeed
        every { mockSstpTunnel.connect(any(), any()) } answers {
            sstpStateFlow.value = SstpState.CONNECTED
        }
        every { mockSstpTunnel.state } returns SstpState.CONNECTED
        every { mockSstpTunnel.localAddress } returns "10.0.0.1"

        // Setup proxy to succeed
        coEvery { mockProxyAuthService.login(any(), any()) } returns Result.success(Unit)
        proxyAuthStateFlow.value = ProxyAuthState.AUTHENTICATED

        // Setup wstunnel to succeed
        coEvery { mockWstunnelManager.start(any()) } returns Result.success(Unit)
        wstunnelStateFlow.value = WstunnelState.RUNNING

        // Mock WireGuard to fail
        every { mockVpnService.startWithWireGuard(any(), any()) } returns Result.failure(
            Exception("WireGuard failed")
        )

        orchestrator.start(testConfig)
        advanceUntilIdle()

        val state = orchestrator.state.value
        assertTrue("State should be WireGuardError but was $state", state is VpnState.WireGuardError)
    }

    // ── Cleanup Tests ─────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `cleanup sequence stops components in correct order`() = testScope.runTest {
        val orchestrator = createOrchestrator()

        // Start then stop
        orchestrator.start(testConfig)
        advanceUntilIdle()
        orchestrator.stop()
        advanceUntilIdle()

        // Verify order: VPN shutdown first, then WireGuard, then wstunnel, proxy, SSTP
        val inOrder = inOrder(
            mockVpnService,
            mockWstunnelManager,
            mockProxyAuthService,
            mockSstpTunnel
        )

        inOrder.verify { mockVpnService.shutdown() }
        inOrder.verify { mockWstunnelManager.stop() }
        inOrder.verify { mockProxyAuthService.reset() }
        inOrder.verify { mockSstpTunnel.disconnect() }
    }

    @Test
    fun `cleanup continues even if component throws exception`() = testScope.runTest {
        val orchestrator = createOrchestrator()

        // Make SSTP disconnect throw
        every { mockSstpTunnel.disconnect() } throws Exception("SSTP disconnect error")

        // Start then stop
        orchestrator.start(testConfig)
        advanceUntilIdle()
        orchestrator.stop()
        advanceUntilIdle()

        // Other components should still be cleaned up
        verify { mockWstunnelManager.stop() }
        verify { mockProxyAuthService.reset() }
        assertFalse(orchestrator.isRunning())
    }

    // ── Reconnect Manager Integration Tests ────────────────────────
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `reconnect manager is enabled on start`() = testScope.runTest {
        val stateMachine = VpnStateMachine()
        val orchestrator = createOrchestrator(stateMachine = stateMachine)

        setupSuccessfulConnectionMocks()
        orchestrator.start(testConfig)
        advanceUntilIdle()

        assertTrue(stateMachine.isReconnectEnabled())
    }

    @Test
    fun `reconnect manager is stopped on disconnect`() = testScope.runTest {
        val stateMachine = VpnStateMachine()
        val orchestrator = createOrchestrator(stateMachine = stateMachine)

        setupSuccessfulConnectionMocks()
        orchestrator.start(testConfig)
        advanceUntilIdle()
        orchestrator.stop()
        advanceUntilIdle()

        verify { mockReconnectManager.stop() }
    }

    // ── Logging Tests ─────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `connection log is populated during connection`() = testScope.runTest {
        val orchestrator = createOrchestrator()
        setupSuccessfulConnectionMocks()

        orchestrator.start(testConfig)
        advanceUntilIdle()

        val log = orchestrator.connectionLog.value
        assertTrue("Log should contain SSTP messages", log.any { it.contains("SSTP") })
        assertTrue("Log should contain Proxy messages", log.any { it.contains("Proxy") })
        assertTrue("Log should contain wstunnel messages", log.any { it.contains("wstunnel") })
        assertTrue("Log should contain WireGuard messages", log.any { it.contains("WireGuard") })
    }

    @Test
    fun `clearLogs empties the log buffer`() = testScope.runTest {
        val orchestrator = createOrchestrator()
        setupSuccessfulConnectionMocks()

        orchestrator.start(testConfig)
        advanceUntilIdle()

        orchestrator.clearLogs()

        assertTrue(orchestrator.connectionLog.value.isEmpty())
    }

    // ── Helper Methods ─────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────

    private fun setupSuccessfulConnectionMocks() {
        // SSTP
        every { mockSstpTunnel.connect(any(), any()) } answers {
            sstpStateFlow.value = SstpState.CONNECTED
        }
        every { mockSstpTunnel.state } returns SstpState.CONNECTED
        every { mockSstpTunnel.localAddress } returns "10.0.0.1"
        every { mockSstpTunnel.isConnected } returns true

        // Proxy Auth
        coEvery { mockProxyAuthService.login(any(), any()) } returns Result.success(Unit)
        proxyAuthStateFlow.value = ProxyAuthState.AUTHENTICATED

        // Wstunnel
        coEvery { mockWstunnelManager.start(any()) } returns Result.success(Unit)
        wstunnelStateFlow.value = WstunnelState.RUNNING

        // WireGuard/VpnService
        every { mockVpnService.startWithWireGuard(any(), any()) } returns Result.success(Unit)
        every { mockVpnService.getWireGuardState() } returns WireGuardState.CONNECTED
    }
}
