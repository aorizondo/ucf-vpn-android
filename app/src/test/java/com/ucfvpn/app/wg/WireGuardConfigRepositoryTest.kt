package com.ucfvpn.app.wg

import com.ucfvpn.app.wg.WireGuardConfig.Companion.isValid
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for WireGuard config parsing, data model, and repository mock.
 *
 * These tests run on the JVM without Android framework dependencies.
 */
class WireGuardConfigRepositoryTest {

    private lateinit var repository: WireGuardConfigRepositoryMock

    // ──────────────────────────────────────────────────────
    //  Sample config matching predator_estocolmo.conf
    // ──────────────────────────────────────────────────────

    private val sampleWgQuickConfig = """
        [Interface]
        PrivateKey = WI0D3WpkRcvhKpWalbhqUHU+eAg0iFFje4YYEpuP900=
        Address = 10.8.0.2/24, fdcc:ad94:bacf:61a4::cafe:2/112
        DNS = 1.1.1.1, 2606:4700:4700::1111
        MTU = 1420

        [Peer]
        PublicKey = OVm14lotGvKKawksQ8UVPhO0phxZ+8WZDlxgAKZ55h0=
        PresharedKey = ixwBqPR4g/RJvV7WWS/0wDEkzv3Eg9HJgV5w6EngkFc=
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 0
        Endpoint = 185.195.236.217:51820
    """.trimIndent()

    private val sampleConfig = WireGuardConfig(
        privateKey = "WI0D3WpkRcvhKpWalbhqUHU+eAg0iFFje4YYEpuP900=",
        address = "10.8.0.2/24",
        dns = listOf("1.1.1.1", "2606:4700:4700::1111"),
        mtu = 1420,
        peerPublicKey = "OVm14lotGvKKawksQ8UVPhO0phxZ+8WZDlxgAKZ55h0=",
        peerPresharedKey = "ixwBqPR4g/RJvV7WWS/0wDEkzv3Eg9HJgV5w6EngkFc=",
        peerEndpoint = "185.195.236.217:51820",
        allowedIps = listOf("0.0.0.0/0", "::/0"),
        persistentKeepalive = 0
    )

    @Before
    fun setUp() {
        repository = WireGuardConfigRepositoryMock()
    }

    // ──────────────────────────────────────────────────────
    //  Data class tests
    // ──────────────────────────────────────────────────────

    @Test
    fun `data class equality`() {
        val a = WireGuardConfig(
            privateKey = "key1",
            address = "10.0.0.1/24",
            dns = listOf("1.1.1.1"),
            mtu = 1420,
            peerPublicKey = "pubkey",
            peerPresharedKey = "psk",
            peerEndpoint = "1.2.3.4:51820",
            allowedIps = listOf("0.0.0.0/0"),
            persistentKeepalive = 25
        )
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class copy modifies fields`() {
        val original = sampleConfig
        val modified = original.copy(mtu = 1280, persistentKeepalive = 25)
        assertEquals(1280, modified.mtu)
        assertEquals(25, modified.persistentKeepalive)
        assertEquals(original.privateKey, modified.privateKey)
        assertEquals(original.peerPublicKey, modified.peerPublicKey)
    }

    @Test
    fun `default config has correct values`() {
        val default = WireGuardConfig.defaultConfig

        assertEquals("", default.privateKey) // empty by specification
        assertEquals("10.8.0.2/24", default.address)
        assertEquals(listOf("1.1.1.1", "2606:4700:4700::1111"), default.dns)
        assertEquals(1420, default.mtu)
        assertEquals("OVm14lotGvKKawksQ8UVPhO0phxZ+8WZDlxgAKZ55h0=", default.peerPublicKey)
        assertEquals(null, default.peerPresharedKey)
        assertEquals("127.0.0.1:51820", default.peerEndpoint)
        assertEquals(listOf("0.0.0.0/0", "::/0"), default.allowedIps)
        assertEquals(0, default.persistentKeepalive)
    }

    @Test
    fun `config isValid returns false when privateKey is blank`() {
        val config = WireGuardConfig.defaultConfig
        assertFalse(config.isValid())
    }

    @Test
    fun `config isValid returns true when fully populated`() {
        assertTrue(sampleConfig.isValid())
    }

    @Test
    fun `config isValid returns false when address is blank`() {
        val config = sampleConfig.copy(address = "")
        assertFalse(config.isValid())
    }

    @Test
    fun `config isValid returns false when peerPublicKey is blank`() {
        val config = sampleConfig.copy(peerPublicKey = "")
        assertFalse(config.isValid())
    }

    // ──────────────────────────────────────────────────────
    //  Config parser tests (fallback Kotlin parser)
    // ──────────────────────────────────────────────────────

    @Test
    fun `parse valid wg-quick config`() {
        val parsed = WireGuardConfigParser.parse(sampleWgQuickConfig)
        assertNotNull(parsed)

        assertEquals("WI0D3WpkRcvhKpWalbhqUHU+eAg0iFFje4YYEpuP900=", parsed!!.privateKey)
        assertEquals("10.8.0.2/24, fdcc:ad94:bacf:61a4::cafe:2/112", parsed.address)
        assertEquals(listOf("1.1.1.1", "2606:4700:4700::1111"), parsed.dns)
        assertEquals(1420, parsed.mtu)
        assertEquals("OVm14lotGvKKawksQ8UVPhO0phxZ+8WZDlxgAKZ55h0=", parsed.peerPublicKey)
        assertEquals("ixwBqPR4g/RJvV7WWS/0wDEkzv3Eg9HJgV5w6EngkFc=", parsed.peerPresharedKey)
        assertEquals("185.195.236.217:51820", parsed.peerEndpoint)
        assertEquals(listOf("0.0.0.0/0", "::/0"), parsed.allowedIps)
        assertEquals(0, parsed.persistentKeepalive)
    }

    @Test
    fun `parse config without PresharedKey`() {
        val configText = """
            [Interface]
            PrivateKey = key123=
            Address = 10.0.0.1/24

            [Peer]
            PublicKey = pubkey123=
            Endpoint = 1.2.3.4:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        val parsed = WireGuardConfigParser.parse(configText)
        assertNotNull(parsed)
        assertEquals("key123=", parsed!!.privateKey)
        assertEquals("10.0.0.1/24", parsed.address)
        assertEquals("pubkey123=", parsed.peerPublicKey)
        assertEquals("1.2.3.4:51820", parsed.peerEndpoint)
        assertEquals(listOf("0.0.0.0/0"), parsed.allowedIps)
        assertNull(parsed.peerPresharedKey)
    }

    @Test
    fun `parse config with equals sign without spaces`() {
        val configText = """
            [Interface]
            PrivateKey=abc=
            Address=10.0.0.1/24
            DNS=8.8.8.8
            MTU=1500

            [Peer]
            PublicKey=pub123=
            Endpoint=10.0.0.2:51820
            AllowedIPs=0.0.0.0/0
        """.trimIndent()

        val parsed = WireGuardConfigParser.parse(configText)
        assertNotNull(parsed)
        assertEquals("abc=", parsed!!.privateKey)
        assertEquals("10.0.0.1/24", parsed.address)
        assertEquals(listOf("8.8.8.8"), parsed.dns)
        assertEquals(1500, parsed.mtu)
        assertEquals("pub123=", parsed.peerPublicKey)
        assertEquals("10.0.0.2:51820", parsed.peerEndpoint)
    }

    @Test
    fun `parse empty config returns null`() {
        val parsed = WireGuardConfigParser.parse("")
        assertNull(parsed)
    }

    @Test
    fun `parse config with missing peer PublicKey returns null`() {
        val configText = """
            [Interface]
            PrivateKey = key123=
            Address = 10.0.0.1/24

            [Peer]
            Endpoint = 1.2.3.4:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        val parsed = WireGuardConfigParser.parse(configText)
        assertNull(parsed)
    }

    @Test
    fun `parse config with multiple DNS servers`() {
        val configText = """
            [Interface]
            PrivateKey = key=
            Address = 10.0.0.1/24
            DNS = 1.1.1.1, 8.8.8.8, 9.9.9.9
            MTU = 1420

            [Peer]
            PublicKey = pub=
            Endpoint = 1.2.3.4:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        val parsed = WireGuardConfigParser.parse(configText)
        assertNotNull(parsed)
        assertEquals(listOf("1.1.1.1", "8.8.8.8", "9.9.9.9"), parsed!!.dns)
    }

    @Test
    fun `parse config with PersistentKeepalive`() {
        val configText = """
            [Interface]
            PrivateKey = key=
            Address = 10.0.0.1/24
            DNS = 1.1.1.1

            [Peer]
            PublicKey = pub=
            Endpoint = 1.2.3.4:51820
            AllowedIPs = 0.0.0.0/0
            PersistentKeepalive = 25
        """.trimIndent()

        val parsed = WireGuardConfigParser.parse(configText)
        assertNotNull(parsed)
        assertEquals(25, parsed!!.persistentKeepalive)
    }

    @Test
    fun `parse config with default MTU when omitted`() {
        val configText = """
            [Interface]
            PrivateKey = key=
            Address = 10.0.0.1/24
            DNS = 1.1.1.1

            [Peer]
            PublicKey = pub=
            Endpoint = 1.2.3.4:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        val parsed = WireGuardConfigParser.parse(configText)
        assertNotNull(parsed)
        assertEquals(1420, parsed!!.mtu) // default MTU
    }

    // ──────────────────────────────────────────────────────
    //  Repository mock tests (save/load roundtrip)
    // ──────────────────────────────────────────────────────

    @Test
    fun `repository save and load roundtrip`() {
        repository.saveConfig(sampleConfig)
        val loaded = repository.loadConfig()
        assertNotNull(loaded)
        assertEquals(sampleConfig, loaded)
    }

    @Test
    fun `loadConfig returns null when not configured`() {
        assertNull(repository.loadConfig())
    }

    @Test
    fun `isConfigured returns false when empty`() {
        assertFalse(repository.isConfigured())
    }

    @Test
    fun `isConfigured returns true after save`() {
        repository.saveConfig(sampleConfig)
        assertTrue(repository.isConfigured())
    }

    @Test
    fun `isConfigured returns false after delete`() {
        repository.saveConfig(sampleConfig)
        repository.deleteConfig()
        assertFalse(repository.isConfigured())
    }

    @Test
    fun `deleteConfig clears everything`() {
        repository.saveConfig(sampleConfig)
        repository.deleteConfig()

        assertNull(repository.loadConfig())
        assertNull(repository.loadPrivateKey())
        assertNull(repository.loadPresharedKey())
        assertFalse(repository.isConfigured())
    }

    @Test
    fun `savePrivateKey and loadPrivateKey roundtrip`() {
        val key = "testPrivateKey123="
        assertTrue(repository.savePrivateKey(key))
        assertEquals(key, repository.loadPrivateKey())
    }

    @Test
    fun `loadPrivateKey returns null when not saved`() {
        assertNull(repository.loadPrivateKey())
    }

    @Test
    fun `savePresharedKey and loadPresharedKey roundtrip`() {
        val key = "testPsk456="
        assertTrue(repository.savePresharedKey(key))
        assertEquals(key, repository.loadPresharedKey())
    }

    @Test
    fun `loadPresharedKey returns null when not saved`() {
        assertNull(repository.loadPresharedKey())
    }

    @Test
    fun `clearKeys removes all keys`() {
        repository.savePrivateKey("pk")
        repository.savePresharedKey("psk")
        repository.clearKeys()

        assertNull(repository.loadPrivateKey())
        assertNull(repository.loadPresharedKey())
    }

    @Test
    fun `clearKeys does not throw when no keys exist`() {
        repository.clearKeys() // should not throw
        assertNull(repository.loadPrivateKey())
    }

    @Test
    fun `saveConfig overwrites previous config`() {
        val config1 = sampleConfig
        val config2 = sampleConfig.copy(mtu = 1280, dns = listOf("8.8.8.8"))

        repository.saveConfig(config1)
        repository.saveConfig(config2)

        val loaded = repository.loadConfig()
        assertNotNull(loaded)
        assertEquals(1280, loaded!!.mtu)
        assertEquals(listOf("8.8.8.8"), loaded.dns)
    }

    @Test
    fun `loadConfig returns config with keys from Keystore`() {
        // Save config with private key cleared, then verify load reconstructs it
        val configWithoutKey = sampleConfig.copy(privateKey = "")
        repository.saveConfig(configWithoutKey)
        repository.savePrivateKey(sampleConfig.privateKey)

        val loaded = repository.loadConfig()
        assertNotNull(loaded)
        assertEquals(sampleConfig.privateKey, loaded!!.privateKey)
    }

    @Test
    fun `config with null PreSharedKey handled correctly`() {
        val config = sampleConfig.copy(peerPresharedKey = null)
        repository.saveConfig(config)

        val loaded = repository.loadConfig()
        assertNotNull(loaded)
        assertNull(loaded!!.peerPresharedKey)
    }

    @Test
    fun `defaultConfig isValid returns false due to empty privateKey`() {
        assertFalse(WireGuardConfig.defaultConfig.isValid())
    }
}
