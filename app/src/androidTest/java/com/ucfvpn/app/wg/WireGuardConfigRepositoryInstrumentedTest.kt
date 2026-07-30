package com.ucfvpn.app.wg

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [WireGuardConfigRepositoryImpl] focusing on
 * Android Keystore integration and EncryptedSharedPreferences persistence.
 *
 * Requires an Android device or emulator with API 26+.
 */
@RunWith(AndroidJUnit4::class)
class WireGuardConfigRepositoryInstrumentedTest {

    private lateinit var repository: WireGuardConfigRepositoryImpl
    private lateinit var context: Context

    private val testPrivateKey = "WI0D3WpkRcvhKpWalbhqUHU+eAg0iFFje4YYEpuP900="
    private val testPresharedKey = "ixwBqPR4g/RJvV7WWS/0wDEkzv3Eg9HJgV5w6EngkFc="

    private val testConfig = WireGuardConfig(
        privateKey = testPrivateKey,
        address = "10.8.0.2/24",
        dns = listOf("1.1.1.1", "2606:4700:4700::1111"),
        mtu = 1420,
        peerPublicKey = "OVm14lotGvKKawksQ8UVPhO0phxZ+8WZDlxgAKZ55h0=",
        peerPresharedKey = testPresharedKey,
        peerEndpoint = "185.195.236.217:51820",
        allowedIps = listOf("0.0.0.0/0", "::/0"),
        persistentKeepalive = 0
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = WireGuardConfigRepositoryImpl(context)
        // Start clean for each test
        repository.deleteConfig()
    }

    @After
    fun tearDown() {
        repository.clearKeys()
        repository.deleteConfig()
    }

    // ──────────────────────────────────────────────────────
    //  Keystore: Private key storage
    // ──────────────────────────────────────────────────────

    @Test
    fun `private key is stored and retrievable from Android Keystore`() {
        val saved = repository.savePrivateKey(testPrivateKey)
        assertTrue("savePrivateKey should succeed", saved)

        val loaded = repository.loadPrivateKey()
        assertNotNull("loadPrivateKey should return the key", loaded)
        assertEquals("Private key should match after roundtrip", testPrivateKey, loaded)
    }

    @Test
    fun `private key survives multiple loads`() {
        repository.savePrivateKey(testPrivateKey)

        // Load multiple times to verify persistence
        repeat(5) {
            val loaded = repository.loadPrivateKey()
            assertEquals(testPrivateKey, loaded)
        }
    }

    @Test
    fun `loadPrivateKey returns null when not stored`() {
        val loaded = repository.loadPrivateKey()
        assertNull("loadPrivateKey should return null when nothing is stored", loaded)
    }

    @Test
    fun `clearKeys removes private key from Keystore`() {
        repository.savePrivateKey(testPrivateKey)
        repository.clearKeys()

        val loaded = repository.loadPrivateKey()
        assertNull("Private key should be null after clearKeys", loaded)
    }

    // ──────────────────────────────────────────────────────
    //  Keystore: Pre-shared key storage
    // ──────────────────────────────────────────────────────

    @Test
    fun `pre-shared key is stored and retrievable from Android Keystore`() {
        val saved = repository.savePresharedKey(testPresharedKey)
        assertTrue("savePresharedKey should succeed", saved)

        val loaded = repository.loadPresharedKey()
        assertNotNull("loadPresharedKey should return the key", loaded)
        assertEquals("Pre-shared key should match after roundtrip", testPresharedKey, loaded)
    }

    @Test
    fun `loadPresharedKey returns null when not stored`() {
        val loaded = repository.loadPresharedKey()
        assertNull("loadPresharedKey should return null when nothing is stored", loaded)
    }

    @Test
    fun `clearKeys removes pre-shared key from Keystore`() {
        repository.savePresharedKey(testPresharedKey)
        repository.clearKeys()

        val loaded = repository.loadPresharedKey()
        assertNull("Pre-shared key should be null after clearKeys", loaded)
    }

    // ──────────────────────────────────────────────────────
    //  Full config persistence
    // ──────────────────────────────────────────────────────

    @Test
    fun `saveConfig and loadConfig full roundtrip with Keystore`() {
        repository.saveConfig(testConfig)

        val loaded = repository.loadConfig()
        assertNotNull("loadConfig should return a config", loaded)

        with(loaded!!) {
            assertEquals(testPrivateKey, privateKey)
            assertEquals("10.8.0.2/24", address)
            assertEquals(listOf("1.1.1.1", "2606:4700:4700::1111"), dns)
            assertEquals(1420, mtu)
            assertEquals("OVm14lotGvKKawksQ8UVPhO0phxZ+8WZDlxgAKZ55h0=", peerPublicKey)
            assertEquals(testPresharedKey, peerPresharedKey)
            assertEquals("185.195.236.217:51820", peerEndpoint)
            assertEquals(listOf("0.0.0.0/0", "::/0"), allowedIps)
            assertEquals(0, persistentKeepalive)
        }
    }

    @Test
    fun `loadConfig returns null when not configured`() {
        val loaded = repository.loadConfig()
        assertNull("loadConfig should return null before any config is saved", loaded)
    }

    @Test
    fun `isConfigured returns false when not configured`() {
        assertFalse("isConfigured should be false", repository.isConfigured())
    }

    @Test
    fun `isConfigured returns true after saveConfig`() {
        repository.saveConfig(testConfig)
        assertTrue("isConfigured should be true after saving config", repository.isConfigured())
    }

    @Test
    fun `isConfigured returns false after deleteConfig`() {
        repository.saveConfig(testConfig)
        repository.deleteConfig()
        assertFalse("isConfigured should be false after delete", repository.isConfigured())
    }

    @Test
    fun `deleteConfig clears Keystore keys and preferences`() {
        repository.saveConfig(testConfig)
        repository.deleteConfig()

        assertNull(repository.loadConfig())
        assertNull(repository.loadPrivateKey())
        assertNull(repository.loadPresharedKey())
    }

    // ──────────────────────────────────────────────────────
    //  Config without PreSharedKey
    // ──────────────────────────────────────────────────────

    @Test
    fun `config without PreSharedKey roundtrips correctly`() {
        val configWithoutPsk = testConfig.copy(
            peerPresharedKey = null,
            privateKey = "anotherKey12345678901234567890123456="
        )

        repository.saveConfig(configWithoutPsk)
        val loaded = repository.loadConfig()

        assertNotNull(loaded)
        assertNull(loaded!!.peerPresharedKey)
        assertEquals("anotherKey12345678901234567890123456=", loaded.privateKey)
    }

    // ──────────────────────────────────────────────────────
    //  Verify keys are independent
    // ──────────────────────────────────────────────────────

    @Test
    fun `private key and pre-shared key are stored independently`() {
        // Save only private key, not pre-shared
        repository.savePrivateKey(testPrivateKey)

        assertNotNull(repository.loadPrivateKey())
        assertNull(repository.loadPresharedKey())

        // Now save pre-shared key
        repository.savePresharedKey(testPresharedKey)

        assertNotNull(repository.loadPrivateKey())
        assertNotNull(repository.loadPresharedKey())

        // Clear only pre-shared? No, clearKeys clears both.
        // But we can overwrite individually
        repository.savePresharedKey("newPskValue")
        assertEquals("newPskValue", repository.loadPresharedKey())
        assertEquals(testPrivateKey, repository.loadPrivateKey()) // unchanged
    }
}
