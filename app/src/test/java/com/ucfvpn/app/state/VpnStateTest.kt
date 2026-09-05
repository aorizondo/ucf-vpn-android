package com.ucfvpn.app.state

import com.ucfvpn.app.wstunnel.TunnelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnStateTest {

    @Test
    fun `PppNegotiating displayName includes phase`() {
        assertEquals("PPP Negotiating (LCP)", VpnState.PppNegotiating(VpnState.PppPhase.LCP).displayName)
        assertEquals("PPP Negotiating (AUTH)", VpnState.PppNegotiating(VpnState.PppPhase.AUTH).displayName)
        assertEquals("PPP Negotiating (IPCP)", VpnState.PppNegotiating(VpnState.PppPhase.IPCP).displayName)
    }

    @Test
    fun `PppAuthenticated displayName`() {
        assertEquals("PPP Authenticated", VpnState.PppAuthenticated("10.0.0.2").displayName)
    }

    @Test
    fun `WstunnelStarting displayName includes tunnel type`() {
        assertEquals("Wstunnel Starting (SOCKS5)", VpnState.WstunnelStarting(TunnelType.SOCKS5).displayName)
    }

    @Test
    fun `WstunnelRunning displayName includes port`() {
        assertEquals("Wstunnel Running (SOCKS5 :1080)", VpnState.WstunnelRunning(1080).displayName)
    }

    @Test
    fun `new states are not errors`() {
        assertFalse(VpnState.PppNegotiating(VpnState.PppPhase.LCP).isError)
        assertFalse(VpnState.PppAuthenticated("10.0.0.2").isError)
        assertFalse(VpnState.WstunnelStarting(TunnelType.SOCKS5).isError)
        assertFalse(VpnState.WstunnelRunning(1080).isError)
    }

    @Test
    fun `new states are transitioning`() {
        assertTrue(VpnState.PppNegotiating(VpnState.PppPhase.LCP).isTransitioning)
        assertTrue(VpnState.PppAuthenticated("10.0.0.2").isTransitioning)
        assertTrue(VpnState.WstunnelStarting(TunnelType.SOCKS5).isTransitioning)
        assertTrue(VpnState.WstunnelRunning(1080).isTransitioning)
    }

    @Test
    fun `only VpnRunning is connected`() {
        assertTrue(VpnState.VpnRunning.isConnected)
        assertFalse(VpnState.PppAuthenticated("10.0.0.2").isConnected)
        assertFalse(VpnState.WstunnelRunning(1080).isConnected)
    }

    @Test
    fun `error states remain errors`() {
        assertTrue(VpnState.SstpError("x").isError)
        assertTrue(VpnState.ProxyError("x").isError)
        assertTrue(VpnState.WstunnelError("x").isError)
        assertTrue(VpnState.WireGuardError("x").isError)
    }
}