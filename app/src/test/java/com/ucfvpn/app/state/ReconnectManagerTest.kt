package com.ucfvpn.app.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectManagerTest {
    private var reconnectCallCount = 0

    private fun createReconnectManager(): ReconnectManager {
        reconnectCallCount = 0
        return ReconnectManager(onReconnect = suspend {
            reconnectCallCount++
            delay(10)
        })
    }

    @Test
    fun initialBackoffIsOneSecond() = runTest {
        val manager = createReconnectManager()
        assertEquals(1_000L, manager.getCurrentBackoffMs())
    }

    @Test
    fun stateUpdatesToWaitingWithAttemptInfo() = runTest {
        val manager = createReconnectManager()
        val job = launch { manager.start() }
        delay(100)
        val state = manager.reconnectState.value
        assertTrue(state is ReconnectState.Waiting)
        manager.stop()
        job.cancel()
    }

    @Test
    fun stateUpdatesToStoppedAfterStop() = runTest {
        val manager = createReconnectManager()
        val job = launch { manager.start() }
        delay(100)
        manager.stop()
        assertEquals(ReconnectState.Stopped, manager.reconnectState.value)
        job.cancel()
    }

    @Test
    fun onSuccessResetsBackoff() = runTest {
        val manager = createReconnectManager()
        manager.resetBackoff()
        manager.onSuccess()
        assertEquals(ReconnectState.Idle, manager.reconnectState.value)
        assertEquals(1_000L, manager.getCurrentBackoffMs())
    }

    @Test
    fun stopCancelsReconnectLoop() = runTest {
        val manager = createReconnectManager()
        val job = launch { manager.start() }
        delay(100)
        manager.stop()
        assertFalse(manager.isRunning())
        delay(200)
        assertEquals(ReconnectState.Stopped, manager.reconnectState.value)
        job.cancel()
    }

    @Test
    fun isRunningReturnsCorrectValue() = runTest {
        val manager = createReconnectManager()
        assertFalse(manager.isRunning())
        val job = launch { manager.start() }
        delay(50)
        assertTrue(manager.isRunning())
        manager.stop()
        assertFalse(manager.isRunning())
        job.cancel()
    }

    @Test
    fun onReconnectCallbackIsCalledDuringLoop() = runTest {
        var callCount = 0
        val controlledManager = ReconnectManager(onReconnect = suspend {
            callCount++
            delay(5)
        })
        val job = launch { controlledManager.start() }
        delay(2_000)
        assertTrue(callCount > 0)
        controlledManager.stop()
        job.cancel()
    }

    @Test
    fun reconnectStateVariantsExist() = runTest {
        val idle = ReconnectState.Idle
        val waiting = ReconnectState.Waiting(1, 1000L)
        val reconnecting = ReconnectState.Reconnecting
        val stopped = ReconnectState.Stopped
        assertTrue(idle is ReconnectState.Idle)
        assertTrue(waiting is ReconnectState.Waiting)
        assertTrue(reconnecting is ReconnectState.Reconnecting)
        assertTrue(stopped is ReconnectState.Stopped)
    }
}
