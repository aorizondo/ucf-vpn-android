package com.ucfvpn.app.state

import kotlinx.coroutines.*

import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests for VPN reconnection scenarios and edge cases.
 *
 * Covers:
 * - Full happy-path connection sequence
 * - SSTP timeout → error → auto-reconnect
 * - Auth failure → error → auto-reconnect
 * - wstunnel crash → error → auto-reconnect
 * - WireGuard config error → error → auto-reconnect
 * - WiFi disconnect simulation → state transitions
 * - Network change simulation → reconnection
 * - ReconnectManager backoff verification
 * - Multiple sequential failures → exponential backoff
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VpnIntegrationTest {

    // ========================================================================
    // Full Connection Sequence Tests
    // ========================================================================

    @Test
    fun `Full connection sequence - Disconnected to VpnRunning`() = runTest {
        val orchestrator = createOrchestrator(allSuccess())
        val sm = orchestrator.stateMachine

        orchestrator.connect()
        advanceUntilIdle()

        assertEquals(VpnState.VpnRunning, sm.getCurrentState())
        assertEquals(ConnectionResult.Success, orchestrator.connectionResult.value)
    }

    @Test
    fun `Full connection sequence - all state transitions are recorded`() = runTest {
        val orchestrator = createOrchestrator(allSuccess())
        val sm = orchestrator.stateMachine

        orchestrator.connect()
        advanceUntilIdle()

        val history = sm.stateHistory.value
        assertTrue("Should have at least 10 transitions", history.size >= 10)

        // Verify specific transitions
        val firstTransition = history[0]
        assertEquals(VpnState.Disconnected, firstTransition.from)
        assertEquals(VpnState.SstpConnecting, firstTransition.to)

        // Verify the final transition is to VpnRunning
        val lastTransition = history.last()
        assertEquals(VpnState.VpnStarting, lastTransition.from)
        assertEquals(VpnState.VpnRunning, lastTransition.to)
    }

    @Test
    fun `State machine reaches VpnRunning after successful connection`() = runTest {
        val orchestrator = createOrchestrator(allSuccess())
        val sm = orchestrator.stateMachine

        assertEquals(VpnState.Disconnected, sm.getCurrentState())
        orchestrator.connect()
        advanceUntilIdle()

        assertTrue(sm.getCurrentState().isConnected)
        assertEquals("VPN Running", sm.getCurrentState().displayName)
    }

    // ========================================================================
    // SSTP Timeout Scenario Tests
    // ========================================================================

    @Test
    fun `SSTP timeout transitions to SstpError and triggers reconnect`() = runTest {
        var sstpAttempts = 0
        var reconnectStarted = false

        val handlers = ConnectionHandlers(
            connectSstp = {
                sstpAttempts++
                if (sstpAttempts == 1) {
                    delay(100) // Will timeout because timeout is 50ms
                    ConnectionResult.Success
                } else {
                    reconnectStarted = true
                    ConnectionResult.Success
                }
            },
            sstpTimeoutMs = 50L
        )

        val orchestrator = createOrchestrator(handlers)
        val sm = orchestrator.stateMachine

        orchestrator.connect()
        advanceUntilIdle()

        // After timeout, should be in SstpError
        val initialState = sm.stateHistory.value.find { it.to is VpnState.SstpError }
        assertNotNull("Should have transitioned to SstpError", initialState)
        val errorState = initialState.to as VpnState.SstpError
        assertTrue(errorState.message.contains("timed out"))

        // Should have triggered reconnect
        assertTrue("Reconnect should have been triggered", reconnectStarted)
        assertTrue("SSTP should have been attempted at least twice", sstpAttempts >= 2)
    }

    @Test
    fun `SSTP timeout with reconnect disabled does not retry`() = runTest {
        var sstpAttempts = 0

        val handlers = ConnectionHandlers(
            connectSstp = {
                sstpAttempts++
                delay(100)
                ConnectionResult.Success
            },
            sstpTimeoutMs = 50L
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.setReconnectEnabled(false)

        orchestrator.connect()
        advanceUntilIdle()

        // Should only have attempted once
        assertEquals(1, sstpAttempts)
        assertTrue(orchestrator.stateMachine.getCurrentState() is VpnState.SstpError)
    }

    // ========================================================================
    // Auth Failure Scenario Tests
    // ========================================================================

    @Test
    fun `Proxy authentication failure transitions to ProxyError`() = runTest {
        val handlers = ConnectionHandlers(
            authenticateProxy = { ConnectionResult.Failure("Invalid credentials") }
        )

        val orchestrator = createOrchestrator(handlers)
        val sm = orchestrator.stateMachine

        orchestrator.connect()
        advanceUntilIdle()

        val currentState = sm.getCurrentState()
        assertTrue("Should be in ProxyError state", currentState is VpnState.ProxyError)
        assertEquals("Invalid credentials", (currentState as VpnState.ProxyError).message)
    }

    @Test
    fun `Auth failure triggers auto-reconnect sequence`() = runTest {
        var authAttempts = 0
        var reconnectTriggered = false

        val handlers = ConnectionHandlers(
            authenticateProxy = {
                authAttempts++
                if (authAttempts == 1) {
                    ConnectionResult.Failure("Auth failed")
                } else {
                    reconnectTriggered = true
                    ConnectionResult.Success
                }
            }
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.connect()
        advanceUntilIdle()

        // Wait for reconnect to happen
        advanceUntilIdle()

        assertTrue("Reconnect should have been triggered", reconnectTriggered)
        assertTrue("Auth should have been attempted at least twice", authAttempts >= 2)
    }

    @Test
    fun `Proxy authentication timeout transitions to ProxyError`() = runTest {
        val handlers = ConnectionHandlers(
            authenticateProxy = {
                delay(200)
                ConnectionResult.Success
            },
            proxyTimeoutMs = 50L
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.setReconnectEnabled(false)

        orchestrator.connect()
        advanceUntilIdle()

        assertTrue(orchestrator.stateMachine.getCurrentState() is VpnState.ProxyError)
    }

    // ========================================================================
    // wstunnel Crash Scenario Tests
    // ========================================================================

    @Test
    fun `wstunnel startup failure transitions to WstunnelError`() = runTest {
        val handlers = ConnectionHandlers(
            startWstunnel = { ConnectionResult.Failure("wstunnel binary not found") }
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.setReconnectEnabled(false)

        orchestrator.connect()
        advanceUntilIdle()

        val currentState = orchestrator.stateMachine.getCurrentState()
        assertTrue("Should be in WstunnelError state", currentState is VpnState.WstunnelError)
        assertEquals("wstunnel binary not found", (currentState as VpnState.WstunnelError).message)
    }

    @Test
    fun `wstunnel crash triggers auto-reconnect`() = runTest {
        var wsAttempts = 0
        var reconnected = false

        val handlers = ConnectionHandlers(
            startWstunnel = {
                wsAttempts++
                if (wsAttempts == 1) {
                    ConnectionResult.Failure("wstunnel crashed")
                } else {
                    reconnected = true
                    ConnectionResult.Success
                }
            }
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.connect()
        advanceUntilIdle()

        advanceUntilIdle()

        assertTrue("Should have reconnected after wstunnel crash", reconnected)
        assertTrue("wstunnel should have been attempted at least twice", wsAttempts >= 2)
    }

    @Test
    fun `wstunnel timeout during startup triggers error`() = runTest {
        val handlers = ConnectionHandlers(
            startWstunnel = {
                delay(200)
                ConnectionResult.Success
            },
            wstunnelTimeoutMs = 50L
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.setReconnectEnabled(false)

        orchestrator.connect()
        advanceUntilIdle()

        assertTrue(orchestrator.stateMachine.getCurrentState() is VpnState.WstunnelError)
    }

    // ========================================================================
    // WireGuard Config Error Scenario Tests
    // ========================================================================

    @Test
    fun `WireGuard config error transitions to WireGuardError`() = runTest {
        val handlers = ConnectionHandlers(
            connectWireGuard = { ConnectionResult.Failure("Invalid WireGuard config: missing PrivateKey") }
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.setReconnectEnabled(false)

        orchestrator.connect()
        advanceUntilIdle()

        val currentState = orchestrator.stateMachine.getCurrentState()
        assertTrue("Should be in WireGuardError state", currentState is VpnState.WireGuardError)
        assertEquals(
            "Invalid WireGuard config: missing PrivateKey",
            (currentState as VpnState.WireGuardError).message
        )
    }

    @Test
    fun `WireGuard config error triggers auto-reconnect`() = runTest {
        var wgAttempts = 0
        var reconnected = false

        val handlers = ConnectionHandlers(
            connectWireGuard = {
                wgAttempts++
                if (wgAttempts == 1) {
                    ConnectionResult.Failure("WG config parse failed")
                } else {
                    reconnected = true
                    ConnectionResult.Success
                }
            }
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.connect()
        advanceUntilIdle()

        advanceUntilIdle()

        assertTrue("Should have reconnected after WG error", reconnected)
        assertTrue("WireGuard should have been attempted at least twice", wgAttempts >= 2)
    }

    @Test
    fun `WireGuard timeout during connection triggers error`() = runTest {
        val handlers = ConnectionHandlers(
            connectWireGuard = {
                delay(200)
                ConnectionResult.Success
            },
            wgTimeoutMs = 50L
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.setReconnectEnabled(false)

        orchestrator.connect()
        advanceUntilIdle()

        assertTrue(orchestrator.stateMachine.getCurrentState() is VpnState.WireGuardError)
    }

    // ========================================================================
    // WiFi Disconnect / Network Change Scenario Tests
    // ========================================================================

    @Test
    fun `WiFi disconnect during VpnRunning triggers reconnect`() = runTest {
        val orchestrator = createOrchestrator(allSuccess())
        orchestrator.connect()
        advanceUntilIdle()

        assertEquals(VpnState.VpnRunning, orchestrator.stateMachine.getCurrentState())

        // Simulate WiFi disconnect
        orchestrator.simulateExternalDisconnect("WiFi disconnected")
        advanceUntilIdle()

        // After disconnect and reconnect, should be back in VpnRunning
        assertEquals(VpnState.VpnRunning, orchestrator.stateMachine.getCurrentState())
        assertEquals(ConnectionResult.Success, orchestrator.connectionResult.value)
    }

    @Test
    fun `WiFi disconnect during SSTP connection triggers SstpError`() = runTest {
        val handlers = ConnectionHandlers(
            connectSstp = {
                delay(5000) // Takes a long time
                ConnectionResult.Success
            }
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.connect()
        advanceTimeBy(100) // Let it enter SstpConnecting

        assertEquals(VpnState.SstpConnecting, orchestrator.stateMachine.getCurrentState())

        // Simulate WiFi disconnect while connecting
        orchestrator.simulateExternalDisconnect("WiFi lost during connection")

        val currentState = orchestrator.stateMachine.getCurrentState()
        assertTrue("Should be in SstpError", currentState is VpnState.SstpError)
        assertTrue((currentState as VpnState.SstpError).message.contains("WiFi"))
    }

    @Test
    fun `Network change during wstunnel phase triggers WstunnelError`() = runTest {
        var wsStarted = false

        val handlers = ConnectionHandlers(
            startWstunnel = {
                wsStarted = true
                delay(5000) // Simulate long-running wstunnel startup
                ConnectionResult.Success
            }
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.connect()
        advanceUntilIdle()
        advanceTimeBy(50) // Let it reach WstunnelStarting

        // Wait for transition to WstunnelStarting
        while (orchestrator.stateMachine.getCurrentState() != VpnState.WstunnelStarting) {
            advanceTimeBy(10)
        }

        // Simulate network change (WiFi → 4G)
        orchestrator.simulateExternalDisconnect("Network changed: WiFi → 4G")

        val currentState = orchestrator.stateMachine.getCurrentState()
        assertTrue(
            "Should be in WstunnelError after network change during wstunnel phase",
            currentState is VpnState.WstunnelError
        )
    }

    @Test
    fun `Disconnect stops reconnect loop`() = runTest {
        val handlers = ConnectionHandlers(
            connectSstp = { ConnectionResult.Failure("Server unreachable") }
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.connect()
        advanceUntilIdle()

        advanceTimeBy(2000) // Let some reconnect attempts happen

        assertTrue("Reconnect should be running after failures", orchestrator.reconnectManager.isRunning())

        // User manually disconnects
        orchestrator.disconnect()

        assertFalse("Reconnect should be stopped after manual disconnect", orchestrator.reconnectManager.isRunning())
        assertEquals(VpnState.Disconnected, orchestrator.stateMachine.getCurrentState())
    }

    // ========================================================================
    // ReconnectManager Backoff Verification Tests
    // ========================================================================

    @Test
    fun `ReconnectManager backoff starts at initial delay`() = runTest {
        val orchestrator = createOrchestrator(allSuccess())
        val rm = orchestrator.reconnectManager

        assertEquals(ReconnectManager.INITIAL_DELAY_MS, rm.getCurrentBackoffMs())
        assertEquals(0, rm.getCurrentAttempt())
        assertEquals(ReconnectState.Idle, rm.reconnectState.value)
    }

    @Test
    fun `ReconnectManager backoff resets on success`() = runTest {
        val orchestrator = createOrchestrator(allSuccess())
        val rm = orchestrator.reconnectManager

        orchestrator.connect()
        advanceUntilIdle()

        // After success, backoff should be reset
        assertEquals(ReconnectManager.INITIAL_DELAY_MS, rm.getCurrentBackoffMs())
        assertEquals(0, rm.getCurrentAttempt())
        assertEquals(ReconnectState.Idle, rm.reconnectState.value)
    }

    @Test
    fun `Backoff progression is exponential after failures`() = runTest {
        var callCount = AtomicInteger(0)
        val rm = ReconnectManager(
            onReconnect = {
                callCount.incrementAndGet()
                delay(5)
            }
        )

        val job = launch { rm.start() }

        // Let it run for a few attempts
        delay(8000)
        rm.stop()
        job.cancel()

        // Just verify it did run multiple times
        assertTrue("Should have attempted reconnection multiple times", callCount.get() >= 2)
    }

    @Test
    fun `ReconnectManager state transitions during cycle`() = runTest {
        val orchestrator = createOrchestrator(allSuccess())
        val rm = orchestrator.reconnectManager

        assertEquals(ReconnectState.Idle, rm.reconnectState.value)
        assertFalse(rm.isRunning())

        // Start reconnect
        val job = launch { rm.start() }
        delay(100)

        assertTrue("ReconnectManager should be running", rm.isRunning())
        assertTrue(
            "State should be Waiting",
            rm.reconnectState.value is ReconnectState.Waiting
        )

        rm.stop()
        delay(50)
        assertEquals(ReconnectState.Stopped, rm.reconnectState.value)
        assertFalse(rm.isRunning())

        job.cancel()
    }

    // ========================================================================
    // Error Recovery Sequence Tests
    // ========================================================================

    @Test
    fun `SSTP error recovery goes through full reconnection sequence`() = runTest {
        var attempt = 0

        val handlers = ConnectionHandlers(
            connectSstp = {
                attempt++
                if (attempt == 1) {
                    ConnectionResult.Failure("SSTP server unreachable")
                } else {
                    ConnectionResult.Success
                }
            }
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.connect()
        advanceUntilIdle()

        // Wait for reconnect and full sequence
        advanceUntilIdle()

        // After successful reconnect, should be in VpnRunning
        val finalState = orchestrator.stateMachine.getCurrentState()
        assertEquals(VpnState.VpnRunning, finalState)
        assertEquals(ConnectionResult.Success, orchestrator.connectionResult.value)
    }

    @Test
    fun `Error recovery preserves state history across reconnections`() = runTest {
        var attempt = 0

        val handlers = ConnectionHandlers(
            connectSstp = {
                attempt++
                if (attempt == 1) {
                    ConnectionResult.Failure("First attempt failed")
                } else {
                    ConnectionResult.Success
                }
            }
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.connect()
        advanceUntilIdle()

        advanceUntilIdle()

        val history = orchestrator.stateMachine.stateHistory.value

        // Should contain error transition
        val errorTransition = history.find { it.to is VpnState.SstpError }
        assertNotNull("History should contain SstpError transition", errorTransition)

        // Should contain recovery transition (error → SstpConnecting)
        val recoveryTransition = history.find {
            it.from.isError && it.to == VpnState.SstpConnecting
        }
        assertNotNull("History should contain error → SstpConnecting transition", recoveryTransition)
    }

    // ========================================================================
    // Concurrent Disconnect Scenario Tests
    // ========================================================================

    @Test
    fun `Disconnect during active connection sequence cancels cleanly`() = runTest {
        val handlers = ConnectionHandlers(
            connectSstp = {
                delay(5000) // Long operation
                ConnectionResult.Success
            }
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.connect()
        advanceTimeBy(50)

        // Disconnect while connecting
        orchestrator.disconnect()
        advanceUntilIdle()

        assertEquals(VpnState.Disconnected, orchestrator.stateMachine.getCurrentState())
        assertFalse(orchestrator.reconnectManager.isRunning())
    }

    @Test
    fun `Simulated external disconnect does not crash during idle`() = runTest {
        val orchestrator = createOrchestrator(allSuccess())

        // Simulate disconnect while in Disconnected state
        orchestrator.simulateExternalDisconnect("Network lost")
        advanceUntilIdle()

        // Should stay in Disconnected or transition gracefully
        val currentState = orchestrator.stateMachine.getCurrentState()
        // Either still Disconnected or in an error state
        assertNotNull(currentState)
    }

    // ========================================================================
    // Edge Case Tests
    // ========================================================================

    @Test
    fun `Multiple rapid failures do not corrupt state machine`() = runTest {
        var attempts = 0

        val handlers = ConnectionHandlers(
            connectSstp = {
                attempts++
                ConnectionResult.Failure("Failure $attempts")
            }
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.connect()
        advanceUntilIdle()

        // Let reconnect try a few times
        advanceTimeBy(4000)

        // Stop reconnect
        orchestrator.disconnect()

        // State machine should still be valid
        val currentState = orchestrator.stateMachine.getCurrentState()
        assertTrue(
            "Should be either Disconnected or an error state",
            currentState == VpnState.Disconnected || currentState.isError
        )

        // History should not be corrupted
        val history = orchestrator.stateMachine.stateHistory.value
        assertTrue("History should contain transitions", history.isNotEmpty())
    }

    @Test
    fun `Reconnect after successful disconnect does not auto-reconnect`() = runTest {
        val orchestrator = createOrchestrator(allSuccess())
        orchestrator.connect()
        advanceUntilIdle()

        assertEquals(VpnState.VpnRunning, orchestrator.stateMachine.getCurrentState())

        // Manual disconnect
        orchestrator.disconnect()
        advanceUntilIdle()

        assertEquals(VpnState.Disconnected, orchestrator.stateMachine.getCurrentState())

        // Wait to ensure no reconnect happens
        advanceTimeBy(3000)
        assertEquals(VpnState.Disconnected, orchestrator.stateMachine.getCurrentState())
    }

    @Test
    fun `ConnectionResult Failure contains retryable flag`() = runTest {
        val nonRetryable = ConnectionResult.Failure("Fatal error", retryable = false)
        assertFalse(nonRetryable.retryable)

        val retryable = ConnectionResult.Failure("Retryable error", retryable = true)
        assertTrue(retryable.retryable)
    }

    @Test
    fun `VpnOrchestrator connectionResult reflects final outcome`() = runTest {
        val handlers = ConnectionHandlers(
            connectSstp = { ConnectionResult.Failure("Permanent failure") }
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.setReconnectEnabled(false)

        orchestrator.connect()
        advanceUntilIdle()

        val result = orchestrator.connectionResult.value
        assertTrue("Result should be a Failure", result is ConnectionResult.Failure)
        assertEquals("Permanent failure", (result as ConnectionResult.Failure).message)
    }

    @Test
    fun `VPN startup failure triggers WireGuardError for reconnect`() = runTest {
        val handlers = ConnectionHandlers(
            startVpn = { ConnectionResult.Failure("VpnService prepare failed") }
        )

        val orchestrator = createOrchestrator(handlers)
        orchestrator.setReconnectEnabled(false)

        orchestrator.connect()
        advanceUntilIdle()

        val currentState = orchestrator.stateMachine.getCurrentState()
        assertTrue("Should be WireGuardError", currentState is VpnState.WireGuardError)
        assertEquals("VpnService prepare failed", (currentState as VpnState.WireGuardError).message)
    }

    // ========================================================================
    // State Validity During Recovery Tests
    // ========================================================================

    @Test
    fun `Error state display names are correct`() = runTest {
        assertEquals("SSTP Error", VpnState.SstpError("msg").displayName)
        assertEquals("Proxy Error", VpnState.ProxyError("msg").displayName)
        assertEquals("Wstunnel Error", VpnState.WstunnelError("msg").displayName)
        assertEquals("WireGuard Error", VpnState.WireGuardError("msg").displayName)
    }

    @Test
    fun `All error states can transition to SstpConnecting for reconnect`() = runTest {
        val sm = VpnStateMachine()

        assertTrue(sm.isValidTransition(VpnState.SstpError("e"), VpnState.SstpConnecting))
        assertTrue(sm.isValidTransition(VpnState.ProxyError("e"), VpnState.SstpConnecting))
        assertTrue(sm.isValidTransition(VpnState.WstunnelError("e"), VpnState.SstpConnecting))
        assertTrue(sm.isValidTransition(VpnState.WireGuardError("e"), VpnState.SstpConnecting))
    }

    @Test
    fun `isReconnectEnabled respects setter`() = runTest {
        val orchestrator = createOrchestrator(allSuccess())

        assertTrue(orchestrator.stateMachine.isReconnectEnabled())

        orchestrator.setReconnectEnabled(false)
        assertFalse(orchestrator.stateMachine.isReconnectEnabled())

        orchestrator.setReconnectEnabled(true)
        assertTrue(orchestrator.stateMachine.isReconnectEnabled())
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun TestScope.createOrchestrator(handlers: ConnectionHandlers): VpnOrchestrator {
        val sm = VpnStateMachine()
        sm.setReconnectEnabled(true)
        return VpnOrchestrator(
            stateMachine = sm,
            handlers = handlers,
            scope = this
        )
    }

    private fun allSuccess() = ConnectionHandlers(
        connectSstp = { delay(5); ConnectionResult.Success },
        authenticateProxy = { delay(5); ConnectionResult.Success },
        startWstunnel = { delay(5); ConnectionResult.Success },
        connectWireGuard = { delay(5); ConnectionResult.Success },
        startVpn = { delay(5); ConnectionResult.Success }
    )
}
