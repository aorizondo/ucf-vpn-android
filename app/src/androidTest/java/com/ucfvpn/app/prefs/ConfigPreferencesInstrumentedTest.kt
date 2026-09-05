package com.ucfvpn.app.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ucfvpn.app.ui.viewmodel.ProxyType
import com.ucfvpn.app.ui.viewmodel.UiConfig
import com.ucfvpn.app.ui.viewmodel.WstunnelMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [ConfigPreferences] persistence of the full [UiConfig],
 * including the Fase 6 split-tunnel fields (proxyType, privateNetworks,
 * bypassApps, defaultViaProxy).
 *
 * Requires an Android device or emulator with API 26+.
 *
 * Since [ConfigPreferences] stores values in [EncryptedSharedPreferences]
 * (F3 security fix M4), the tests clean/write through the same encrypted
 * mechanism with the same MasterKey so the roundtrip and fallback paths
 * exercise the real production storage.
 */
@RunWith(AndroidJUnit4::class)
class ConfigPreferencesInstrumentedTest {

    private lateinit var context: Context
    private lateinit var prefs: ConfigPreferences

    /** A [UiConfig] with every field set to a non-default value. */
    private val fullConfig = UiConfig(
        // SSTP
        sstpHost = "vpn.example.com",
        sstpPort = 8443,
        sstpUsername = "alice",
        sstpPassword = "s3cret",
        // Proxy
        proxyType = ProxyType.SOCKS5,
        proxyHost = "10.20.30.40",
        proxyPort = 1080,
        proxyUsername = "proxyuser",
        proxyPassword = "proxypass",
        // wstunnel
        wstunnelUrl = "wss://ws.example.com/tunnel",
        wstunnelMode = WstunnelMode.DYNAMIC,
        wstunnelLocalPort = 51821,
        wstunnelRemoteHost = "203.0.113.10",
        wstunnelRemotePort = 51821,
        wstunnelWsPingFrequency = "30s",
        wstunnelRetryMaxBackoff = "60s",
        // Split Tunnel (Fase 6)
        privateNetworks = "10.0.0.0/8,172.16.0.0/12",
        bypassApps = "com.whatsapp,com.android.chrome",
        defaultViaProxy = false,
        // WireGuard
        wireGuardEndpoint = "127.0.0.1:51821",
        wireGuardLocalIp = "10.8.0.3/24",
        wireGuardDns = "8.8.8.8",
        // General
        ignoreSslErrors = false,
        autoReconnect = false,
        // Debug
        debugWstunnelSocks5 = true
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = ConfigPreferences(context)
        // Start clean for each test
        encryptedPrefs(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        encryptedPrefs(context).edit().clear().commit()
    }

    // ──────────────────────────────────────────────────────
    //  Full roundtrip
    // ──────────────────────────────────────────────────────

    @Test
    fun saveAndLoadRoundtripsEveryUiConfigField() {
        prefs.save(fullConfig)

        val loaded = prefs.load()

        // SSTP
        assertEquals("vpn.example.com", loaded.sstpHost)
        assertEquals(8443, loaded.sstpPort)
        assertEquals("alice", loaded.sstpUsername)
        assertEquals("s3cret", loaded.sstpPassword)
        // Proxy
        assertEquals(ProxyType.SOCKS5, loaded.proxyType)
        assertEquals("10.20.30.40", loaded.proxyHost)
        assertEquals(1080, loaded.proxyPort)
        assertEquals("proxyuser", loaded.proxyUsername)
        assertEquals("proxypass", loaded.proxyPassword)
        // wstunnel
        assertEquals("wss://ws.example.com/tunnel", loaded.wstunnelUrl)
        assertEquals(WstunnelMode.DYNAMIC, loaded.wstunnelMode)
        assertEquals(51821, loaded.wstunnelLocalPort)
        assertEquals("203.0.113.10", loaded.wstunnelRemoteHost)
        assertEquals(51821, loaded.wstunnelRemotePort)
        assertEquals("30s", loaded.wstunnelWsPingFrequency)
        assertEquals("60s", loaded.wstunnelRetryMaxBackoff)
        // Split Tunnel (Fase 6)
        assertEquals("10.0.0.0/8,172.16.0.0/12", loaded.privateNetworks)
        assertEquals("com.whatsapp,com.android.chrome", loaded.bypassApps)
        assertFalse("defaultViaProxy should roundtrip false", loaded.defaultViaProxy)
        // WireGuard
        assertEquals("127.0.0.1:51821", loaded.wireGuardEndpoint)
        assertEquals("10.8.0.3/24", loaded.wireGuardLocalIp)
        assertEquals("8.8.8.8", loaded.wireGuardDns)
        // General
        assertFalse("ignoreSslErrors should roundtrip false", loaded.ignoreSslErrors)
        assertFalse("autoReconnect should roundtrip false", loaded.autoReconnect)
        // Debug — NOT persisted by ConfigPreferences (absent from load()/save());
        // the roundtrip returns the default value.
        assertFalse(
            "debugWstunnelSocks5 is not persisted and should fall back to default false",
            loaded.debugWstunnelSocks5
        )
    }

    @Test
    fun saveAndLoadRoundtripsDefaultViaProxyTrue() {
        prefs.save(fullConfig.copy(defaultViaProxy = true))
        assertTrue("defaultViaProxy true should roundtrip", prefs.load().defaultViaProxy)
    }

    @Test
    fun saveAndLoadRoundtripsProxyTypeHTTP() {
        prefs.save(fullConfig.copy(proxyType = ProxyType.HTTP))
        assertEquals(ProxyType.HTTP, prefs.load().proxyType)
    }

    @Test
    fun saveAndLoadRoundtripsEmptyBypassApps() {
        prefs.save(fullConfig.copy(bypassApps = ""))
        assertEquals("", prefs.load().bypassApps)
    }

    // ──────────────────────────────────────────────────────
    //  Defaults when nothing is persisted
    // ──────────────────────────────────────────────────────

    @Test
    fun loadReturnsDefaultsWhenNothingIsSaved() {
        val loaded = prefs.load()
        val defaults = UiConfig()

        assertEquals(defaults.sstpHost, loaded.sstpHost)
        assertEquals(defaults.sstpPort, loaded.sstpPort)
        assertEquals(defaults.proxyType, loaded.proxyType)
        assertEquals(defaults.proxyHost, loaded.proxyHost)
        assertEquals(defaults.proxyPort, loaded.proxyPort)
        assertEquals(defaults.wstunnelUrl, loaded.wstunnelUrl)
        assertEquals(defaults.wstunnelMode, loaded.wstunnelMode)
        assertEquals(defaults.privateNetworks, loaded.privateNetworks)
        assertEquals(defaults.bypassApps, loaded.bypassApps)
        assertEquals(defaults.defaultViaProxy, loaded.defaultViaProxy)
        assertEquals(defaults.wireGuardEndpoint, loaded.wireGuardEndpoint)
        assertEquals(defaults.wireGuardDns, loaded.wireGuardDns)
        assertEquals(defaults.ignoreSslErrors, loaded.ignoreSslErrors)
        assertEquals(defaults.autoReconnect, loaded.autoReconnect)
    }

    // ──────────────────────────────────────────────────────
    //  parseProxyType fallback (invalid persisted value)
    // ──────────────────────────────────────────────────────

    @Test
    fun loadFallsBackToDefaultProxyTypeWhenPersistedValueIsInvalid() {
        // Write an invalid enum name directly into the encrypted prefs to exercise the
        // private parseProxyType fallback path (ProxyType.valueOf throws).
        encryptedPrefs(context)
            .edit()
            .putString("ucf_proxy_type", "NOT_A_REAL_PROXY_TYPE")
            .commit()

        val loaded = prefs.load()
        assertEquals(
            "Invalid proxyType should fall back to the default",
            UiConfig().proxyType,
            loaded.proxyType
        )
    }

    @Test
    fun loadFallsBackToDefaultWstunnelModeWhenPersistedValueIsInvalid() {
        encryptedPrefs(context)
            .edit()
            .putString("ucf_ws_mode", "BOGUS_MODE")
            .commit()

        val loaded = prefs.load()
        assertEquals(
            "Invalid wstunnelMode should fall back to the default",
            UiConfig().wstunnelMode,
            loaded.wstunnelMode
        )
    }

    /**
     * Open the same encrypted prefs file used by [ConfigPreferences]
     * (same name and MasterKey scheme) for cleaning and direct writes.
     */
    private fun encryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val PREFS_NAME = "ucf_vpn_config"
    }
}