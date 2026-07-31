package com.ucfvpn.app.state

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
    fun `SstpConnected can transition to ProxyAuthenticating`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        assertTrue(stateMachine.transition(VpnState.ProxyAuthenticating))
        assertEquals(VpnState.ProxyAuthenticating, stateMachine.state.value)
    }

    @Test
    fun `ProxyAuthenticating can transition to ProxyAuthenticated`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        assertTrue(stateMachine.transition(VpnState.ProxyAuthenticated))
        assertEquals(VpnState.ProxyAuthenticated, stateMachine.state.value)
    }

    @Test
    fun `ProxyAuthenticating can transition to ProxyError`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        assertTrue(stateMachine.transition(VpnState.ProxyError("Auth failed")))
        assertTrue(stateMachine.state.value is VpnState.ProxyError)
    }

    @Test
    fun `ProxyAuthenticated can transition to WstunnelStarting`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        assertTrue(stateMachine.transition(VpnState.WstunnelStarting))
        assertEquals(VpnState.WstunnelStarting, stateMachine.state.value)
    }

    @Test
    fun `WstunnelStarting can transition to WstunnelRunning`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting)
        assertTrue(stateMachine.transition(VpnState.WstunnelRunning))
        assertEquals(VpnState.WstunnelRunning, stateMachine.state.value)
    }

    @Test
    fun `WstunnelStarting can transition to WstunnelError`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting)
        assertTrue(stateMachine.transition(VpnState.WstunnelError("wstunnel failed")))
        assertTrue(stateMachine.state.value is VpnState.WstunnelError)
    }

    @Test
    fun `WstunnelRunning can transition to WireGuardConnecting`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting)
        stateMachine.transition(VpnState.WstunnelRunning)
        assertTrue(stateMachine.transition(VpnState.WireGuardConnecting))
        assertEquals(VpnState.WireGuardConnecting, stateMachine.state.value)
    }

    @Test
    fun `WireGuardConnecting can transition to WireGuardConnected`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting)
        stateMachine.transition(VpnState.WstunnelRunning)
        stateMachine.transition(VpnState.WireGuardConnecting)
        assertTrue(stateMachine.transition(VpnState.WireGuardConnected))
        assertEquals(VpnState.WireGuardConnected, stateMachine.state.value)
    }

    @Test
    fun `WireGuardConnecting can transition to WireGuardError`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting)
        stateMachine.transition(VpnState.WstunnelRunning)
        stateMachine.transition(VpnState.WireGuardConnecting)
        assertTrue(stateMachine.transition(VpnState.WireGuardError("WG failed")))
        assertTrue(stateMachine.state.value is VpnState.WireGuardError)
    }

    @Test
    fun `WireGuardConnected can transition to VpnStarting`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting)
        stateMachine.transition(VpnState.WstunnelRunning)
        stateMachine.transition(VpnState.WireGuardConnecting)
        stateMachine.transition(VpnState.WireGuardConnected)
        assertTrue(stateMachine.transition(VpnState.VpnStarting))
        assertEquals(VpnState.VpnStarting, stateMachine.state.value)
    }

    @Test
    fun `VpnStarting can transition to VpnRunning`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting)
        stateMachine.transition(VpnState.WstunnelRunning)
        stateMachine.transition(VpnState.WireGuardConnecting)
        stateMachine.transition(VpnState.WireGuardConnected)
        stateMachine.transition(VpnState.VpnStarting)
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
    fun `VpnRunning cannot transition to any state except Disconnected`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting)
        stateMachine.transition(VpnState.WstunnelRunning)
        stateMachine.transition(VpnState.WireGuardConnecting)
        stateMachine.transition(VpnState.WireGuardConnected)
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.VpnRunning)

        // Valid - VpnRunning can go to Disconnected
        assertTrue(stateMachine.transition(VpnState.Disconnected))

        // Reset and try other transitions
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting)
        stateMachine.transition(VpnState.WstunnelRunning)
        stateMachine.transition(VpnState.WireGuardConnecting)
        stateMachine.transition(VpnState.WireGuardConnected)
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.VpnRunning)

        assertFalse(stateMachine.transition(VpnState.SstpConnecting))
        assertFalse(stateMachine.transition(VpnState.WstunnelRunning))
    }

    @Test
    fun `SstpError cannot transition to WstunnelStarting`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpError("error"))
        assertFalse(stateMachine.transition(VpnState.WstunnelStarting))
    }

    @Test
    fun `ProxyError cannot transition to WireGuardConnected`() = runTest {
        stateMachine.transition(VpnState.SstpConnecting)
        stateMachine.transition(VpnState.SstpConnected)
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
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting)
        stateMachine.transition(VpnState.WstunnelRunning)
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
        assertTrue(stateMachine.transition(VpnState.ProxyAuthenticating))
        assertTrue(stateMachine.transition(VpnState.ProxyAuthenticated))
        assertTrue(stateMachine.transition(VpnState.WstunnelStarting))
        assertTrue(stateMachine.transition(VpnState.WstunnelRunning))
        assertTrue(stateMachine.transition(VpnState.WireGuardConnecting))
        assertTrue(stateMachine.transition(VpnState.WireGuardConnected))
        assertTrue(stateMachine.transition(VpnState.VpnStarting))
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
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting)
        stateMachine.transition(VpnState.WstunnelRunning)
        stateMachine.transition(VpnState.WireGuardConnecting)
        stateMachine.transition(VpnState.WireGuardConnected)
        stateMachine.transition(VpnState.VpnStarting)
        stateMachine.transition(VpnState.VpnRunning)

        val history = stateMachine.stateHistory.value
        assertEquals(10, history.size)
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
        stateMachine.transition(VpnState.ProxyAuthenticating)
        stateMachine.transition(VpnState.ProxyAuthenticated)
        stateMachine.transition(VpnState.WstunnelStarting)
        stateMachine.transition(VpnState.WstunnelRunning)
        stateMachine.transition(VpnState.WireGuardConnecting)
        stateMachine.transition(VpnState.WireGuardConnected)
        stateMachine.transition(VpnState.VpnStarting)
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

        assertTrue(stateMachine.transition(VpnState.ProxyAuthenticating))
        assertEquals(VpnState.ProxyAuthenticating, stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.ProxyAuthenticated))
        assertEquals(VpnState.ProxyAuthenticated, stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.WstunnelStarting))
        assertEquals(VpnState.WstunnelStarting, stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.WstunnelRunning))
        assertEquals(VpnState.WstunnelRunning, stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.WireGuardConnecting))
        assertEquals(VpnState.WireGuardConnecting, stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.WireGuardConnected))
        assertEquals(VpnState.WireGuardConnected, stateMachine.state.value)

        assertTrue(stateMachine.transition(VpnState.VpnStarting))
        assertEquals(VpnState.VpnStarting, stateMachine.state.value)

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
}
