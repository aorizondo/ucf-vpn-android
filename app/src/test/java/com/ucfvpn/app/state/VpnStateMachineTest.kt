package com.ucfvpn.app.state

import com.ucfvpn.app.wstunnel.TunnelType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnStateMachineTest {

    private val stateMachine = VpnStateMachine()

    // ========== Valid Transition Tests ==========

    @Test
    fun `Disconnected can transition to SstpConnecting`() = runTest {
        assertTrue(stateMachine.transition(VpnState.SstpConnecting))
        assertEquals(VpnState.SstpConnecting, stateMachine.state.value)
    }

    @Test
    fun `SstpConnecting can transition to SstpConnected`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        assertTrue(stateMachine.transition(VpnState.SstpConnected))
        assertEquals(VpnState.SstpConnected, stateMachine.state.value)
    }

    @Test
    fun `SstpConnecting can transition to SstpError`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        assertTrue(stateMachine.transition(VpnState.SstpError("SSTP failed")))
        assertTrue(stateMachine.state.value is VpnState.SstpError)
    }

    @Test
    fun `SstpConnected can transition to PppNegotiating`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        assertTrue(stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP)))
        assertTrue(stateMachine.state.value is VpnState.PppNegotiating)
    }

    @Test
    fun `ProxyAuthenticating can transition to ProxyAuthenticated`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        assertTrue(stateMachine.transition(VpnState.ProxyAuthenticated))
        assertEquals(VpnState.ProxyAuthenticated, stateMachine.state.value)
    }

    @Test
    fun `ProxyAuthenticating can transition to ProxyError`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        assertTrue(stateMachine.transition(VpnState.ProxyError("Auth failed")))
        assertTrue(stateMachine.state.value is VpnState.ProxyError)
    }

    @Test
    fun `ProxyAuthenticated can transition to WstunnelStarting`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        assertTrue(stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5)))
        assertEquals(VpnState.WstunnelStarting(TunnelType.SOCKS5), stateMachine.state.value)
    }

    @Test
    fun `WstunnelStarting can transition to WstunnelRunning`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
        assertTrue(stateMachine.transition(VpnState.WstunnelRunning(1080)))
        assertEquals(VpnState.WstunnelRunning(1080), stateMachine.state.value)
    }

    @Test
    fun `WstunnelStarting can transition to WstunnelError`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
        assertTrue(stateMachine.transition(VpnState.WstunnelError("wstunnel failed")))
        assertTrue(stateMachine.state.value is VpnState.WstunnelError)
    }

    @Test
    fun `WstunnelRunning can transition to WireGuardConnecting`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
        stateMachine.transition(VpnState.WstunnelRunning(1080))
        assertTrue(stateMachine.transition(VpnState.WireGuardConnecting))
        assertEquals(VpnState.WireGuardConnecting, stateMachine.state.value)
    }

    @Test
    fun `WireGuardConnecting can transition to WireGuardConnected`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
        stateMachine.transition(VpnState.WstunnelRunning(1080))
        stateMachine.transition(VpnState.WireGuardConnecting)
        assertTrue(stateMachine.transition(VpnState.WireGuardConnected))
        assertEquals(VpnState.WireGuardConnected, stateMachine.state.value)
    }

    @Test
    fun `WireGuardConnecting can transition to WireGuardError`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
        stateMachine.transition(VpnState.WstunnelRunning(1080))
        stateMachine.transition(VpnState.WireGuardConnecting)
        assertTrue(stateMachine.transition(VpnState.WireGuardError("WG failed")))
        assertTrue(stateMachine.state.value is VpnState.WireGuardError)
    }

    @Test
    fun `WireGuardConnected can transition to VpnRunning`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
        stateMachine.transition(VpnState.WstunnelRunning(1080))
        stateMachine.transition(VpnState.WireGuardConnecting)
        stateMachine.transition(VpnState.WireGuardConnected)
        assertTrue(stateMachine.transition(VpnState.VpnRunning))
        assertEquals(VpnState.VpnRunning, stateMachine.state.value)
    }

    @Test
    fun `VpnStarting can transition to VpnRunning`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
        stateMachine.transition(VpnState.WstunnelRunning(1080))
        assertTrue(stateMachine.transition(VpnState.VpnRunning))
        assertEquals(VpnState.VpnRunning, stateMachine.state.value)
    }

    // ========== Invalid Transition Tests ==========

    @Test
    fun `Disconnected cannot transition to SstpConnected directly`() = runTest {
        assertFalse(stateMachine.transition(VpnState.SstpConnected))
    }

    @Test
    fun `SstpConnecting cannot transition to VpnRunning`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        assertFalse(stateMachine.transition(VpnState.VpnRunning))
    }

    @Test
    fun `VpnRunning can only disconnect, report an error, or restart the sequence`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
        stateMachine.transition(VpnState.WstunnelRunning(1080))
        stateMachine.transition(VpnState.VpnRunning)

        // Valid - VpnRunning can go to Disconnected
        assertTrue(stateMachine.transition(VpnState.Disconnected))

        // Reset and try other transitions
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
        stateMachine.transition(VpnState.WstunnelRunning(1080))
        stateMachine.transition(VpnState.VpnRunning)

        // A live tunnel that drops must be able to restart the sequence,
        // otherwise ReconnectManager can never reconnect.
        assertTrue(stateMachine.transition(VpnState.SstpConnecting))

        // But it cannot jump backwards into an arbitrary mid-stack state.
        assertFalse(stateMachine.transition(VpnState.WstunnelRunning(1080)))
    }

    @Test
    fun `VpnRunning can report a dropped tunnel as an error`() = runTest {
        driveToVpnRunning()

        assertTrue(stateMachine.transition(VpnState.SstpError("tunnel dropped")))
        assertEquals(VpnState.SstpError("tunnel dropped"), stateMachine.state.value)
    }

    /** Walk the full happy path so tests can start from a live tunnel. */
    private suspend fun driveToVpnRunning() {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
        stateMachine.transition(VpnState.WstunnelRunning(1080))
        stateMachine.transition(VpnState.VpnRunning)
    }

    @Test
    fun `SstpError cannot transition to WstunnelStarting`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpError("error"))
        assertFalse(stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5)))
    }

    @Test
    fun `ProxyError cannot transition to WireGuardConnected`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyError("error"))
        assertFalse(stateMachine.transition(VpnState.WireGuardConnected))
    }

    // ========== Manual Disconnect Tests ==========

    @Test
    fun `Any state can transition to Disconnected`() = runTest {
        // From SstpConnecting
        stateMachine.transition(VpnState.SstpConnecting)
        assertTrue(stateMachine.transition(VpnState.Disconnected))
        assertEquals(VpnState.Disconnected, stateMachine.state.value)

        // From WstunnelRunning
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
        stateMachine.transition(VpnState.WstunnelRunning(1080))
        assertTrue(stateMachine.transition(VpnState.Disconnected))
    }

    // ========== Error Recovery Tests ==========

    @Test
    fun `Error states can transition to SstpConnecting for auto-reconnect`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpError("error"))
        assertTrue(stateMachine.transition(VpnState.SstpConnecting))
    }

    @Test
    fun `Error states can transition to Disconnected`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpError("error"))
        assertTrue(stateMachine.transition(VpnState.Disconnected))
    }

    @Test
    fun `SstpError can recovery through full sequence`() = runTest {
        // Start and fail
        assertTrue(stateMachine.transition(VpnState.SstpConnecting))
        assertTrue(stateMachine.transition(VpnState.SstpError("Failed")))

        // Reconnect
        assertTrue(stateMachine.transition(VpnState.SstpConnecting))
        assertTrue(stateMachine.transition(VpnState.SstpConnected))
        assertTrue(stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP)))
        assertTrue(stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH)))
        assertTrue(stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP)))
        assertTrue(stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2")))
        assertTrue(stateMachine.transition(VpnState.VpnStarting))
        assertTrue(stateMachine.transition(VpnState.ProxyAuthenticating))
        assertTrue(stateMachine.transition(VpnState.ProxyAuthenticated))
        assertTrue(stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5)))
        assertTrue(stateMachine.transition(VpnState.WstunnelRunning(1080)))
        assertTrue(stateMachine.transition(VpnState.WireGuardConnecting))
        assertTrue(stateMachine.transition(VpnState.WireGuardConnected))
        assertTrue(stateMachine.transition(VpnState.VpnRunning))

        assertEquals(VpnState.VpnRunning, stateMachine.state.value)
    }

    // ========== State History Tests ==========

    @Test
    fun `State history records transitions`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)

        val history = stateMachine.stateHistory.value
        assertEquals(2, history.size)
        assertEquals(VpnState.Disconnected, history[0].from)
        assertEquals(VpnState.SstpConnecting, history[0].to)
        assertEquals(VpnState.SstpConnecting, history[1].from)
        assertEquals(VpnState.SstpConnected, history[1].to)
    }

    @Test
    fun `State history is limited to 20 entries`() = runTest {
        // Go through many transitions
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
        stateMachine.transition(VpnState.WstunnelRunning(1080))
        stateMachine.transition(VpnState.VpnRunning)

        val history = stateMachine.stateHistory.value
        assertEquals(14, history.size)
        assertTrue(history.size <= 20)
    }

    // ========== connect() and disconnect() Tests ==========

    @Test
    fun `connect from Disconnected starts connection sequence`() = runTest {
        assertTrue(stateMachine.connect())
        assertEquals(VpnState.SstpConnecting, stateMachine.state.value)
    }

    @Test
    fun `connect from VpnRunning returns true`() = runTest {
        // Setup to VpnRunning
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
        stateMachine.transition(VpnState.WstunnelRunning(1080))
        stateMachine.transition(VpnState.VpnRunning)

        assertTrue(stateMachine.connect()) // Should return true, already connected
        assertEquals(VpnState.VpnRunning, stateMachine.state.value)
    }

    @Test
    fun `connect from intermediate state returns false`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        assertFalse(stateMachine.connect())
    }

    @Test
    fun `disconnect from any state returns true`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        assertTrue(stateMachine.disconnect())
        assertEquals(VpnState.Disconnected, stateMachine.state.value)

        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        assertTrue(stateMachine.disconnect())
        assertEquals(VpnState.Disconnected, stateMachine.state.value)
    }

    @Test
    fun `disconnect from Disconnected returns true`() = runTest {
        assertTrue(stateMachine.disconnect())
        assertEquals(VpnState.Disconnected, stateMachine.state.value)
    }

    // ========== Reconnect Enabled Tests ==========

    @Test
    fun `setReconnectEnabled updates internal flag`() = runTest {
        assertFalse(stateMachine.isReconnectEnabled())
        stateMachine.setReconnectEnabled(true)
        assertTrue(stateMachine.isReconnectEnabled())
        stateMachine.setReconnectEnabled(false)
        assertFalse(stateMachine.isReconnectEnabled())
    }

    // ========== Full Connection Sequence Test ==========

    @Test
    fun `Full connection sequence from Disconnected to VpnRunning`() = runTest {
        assertEquals(VpnState.Disconnected, stateMachine.state.value)

        assertTrue(stateMachine.connect())
        assertEquals(VpnState.SstpConnecting, stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.SstpConnected))
        assertEquals(VpnState.SstpConnected, stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP)))
        assertEquals(VpnState.PppNegotiating(VpnState.PppPhase.LCP), stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH)))
        assertEquals(VpnState.PppNegotiating(VpnState.PppPhase.AUTH), stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP)))
        assertEquals(VpnState.PppNegotiating(VpnState.PppPhase.IPCP), stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2")))
        assertEquals(VpnState.PppAuthenticated("10.0.0.2"), stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.ProxyAuthenticating))
        assertEquals(VpnState.ProxyAuthenticating, stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.ProxyAuthenticated))
        assertEquals(VpnState.ProxyAuthenticated, stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5)))
        assertEquals(VpnState.WstunnelStarting(TunnelType.SOCKS5), stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.WstunnelRunning(1080)))
        assertEquals(VpnState.WstunnelRunning(1080), stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.WireGuardConnecting))
        assertEquals(VpnState.WireGuardConnecting, stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.WireGuardConnected))
        assertEquals(VpnState.WireGuardConnected, stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.VpnRunning))
        assertEquals(VpnState.VpnRunning, stateMachine.state.value)
    }

    // ========== isValidTransition Tests ==========

    @Test
    fun `isValidTransition returns correct values`() = runTest {
        assertTrue(stateMachine.isValidTransition(VpnState.Disconnected, VpnState.SstpConnecting))
        assertFalse(stateMachine.isValidTransition(VpnState.Disconnected, VpnState.SstpConnected))
        assertTrue(stateMachine.isValidTransition(VpnState.SstpConnecting, VpnState.SstpConnected))
        assertTrue(stateMachine.isValidTransition(VpnState.SstpConnecting, VpnState.SstpError("error")))
        assertFalse(stateMachine.isValidTransition(VpnState.SstpConnected, VpnState.SstpConnecting))
    }

    @Test
    fun `isValidTransition allows error to reconnect`() = runTest {
        assertTrue(stateMachine.isValidTransition(VpnState.SstpError("error"), VpnState.SstpConnecting))
        assertTrue(stateMachine.isValidTransition(VpnState.ProxyError("error"), VpnState.SstpConnecting))
        assertTrue(stateMachine.isValidTransition(VpnState.WstunnelError("error"), VpnState.SstpConnecting))
        assertTrue(stateMachine.isValidTransition(VpnState.WireGuardError("error"), VpnState.SstpConnecting))
    }

    // ========== getCurrentState Tests ==========

    @Test
    fun `getCurrentState returns current state synchronously`() = runTest {
        assertEquals(VpnState.Disconnected, stateMachine.getCurrentState())

        stateMachine.transition(VpnState.SstpConnecting)
        assertEquals(VpnState.SstpConnecting, stateMachine.getCurrentState())

        stateMachine.transition(VpnState.SstpConnected)
        assertEquals(VpnState.SstpConnected, stateMachine.getCurrentState())
    }

    // ========== Fase 5: PPP + Layer Retry Tests ==========

    @Test
    fun `PppNegotiating phases progress to PppAuthenticated`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        assertTrue(stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP)))
        assertTrue(stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH)))
        assertTrue(stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP)))
        assertTrue(stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2")))
        assertEquals(VpnState.PppAuthenticated("10.0.0.2"), stateMachine.state.value)
    }

    @Test
    fun `PppAuthenticated goes to VpnStarting, never straight to the portal`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))

        // The captive portal lives inside the UCF network, which the device only
        // joins through the SSTP tunnel. Reaching it before the TUN exists sent
        // the request out over mobile data, where that address does not exist,
        // so away from the campus the connection could never complete.
        assertFalse(
            "the portal must not be contacted before the tunnel is up",
            stateMachine.transition(VpnState.ProxyAuthenticating)
        )

        assertTrue(stateMachine.transition(VpnState.VpnStarting))
        assertTrue(stateMachine.transition(VpnState.ProxyAuthenticating))
        assertEquals(VpnState.ProxyAuthenticating, stateMachine.state.value)
    }

    @Test
    fun `WstunnelRunning can transition to VpnRunning`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
        stateMachine.transition(VpnState.WstunnelRunning(1080))
        assertTrue(stateMachine.transition(VpnState.VpnRunning))
        assertEquals(VpnState.VpnRunning, stateMachine.state.value)
    }

    @Test
    fun `SstpError can transition to PppNegotiating for PPP retry`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.SstpError("PPP failed"))
        assertTrue(stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP)))
        assertTrue(stateMachine.state.value is VpnState.PppNegotiating)
    }

    @Test
    fun `ProxyError can transition to ProxyAuthenticating for proxy retry`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyError("auth failed"))
        assertTrue(stateMachine.transition(VpnState.ProxyAuthenticating))
        assertEquals(VpnState.ProxyAuthenticating, stateMachine.state.value)
    }

    @Test
    fun `WstunnelError can transition to WstunnelStarting for wstunnel retry`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
        stateMachine.transition(VpnState.WstunnelError("wstunnel failed"))
        assertTrue(stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5)))
        assertTrue(stateMachine.state.value is VpnState.WstunnelStarting)
    }

    @Test
    fun `VpnStarting can transition to WireGuardError on VPN failure`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.LCP))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.AUTH))
        stateMachine.transition(VpnState.PppNegotiating(VpnState.PppPhase.IPCP))
        stateMachine.transition(VpnState.PppAuthenticated("10.0.0.2"))
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
        stateMachine.transition(VpnState.WstunnelRunning(1080))
        assertTrue(stateMachine.transition(VpnState.WireGuardError("VPN failed")))
        assertTrue(stateMachine.state.value is VpnState.WireGuardError)
    }
}
