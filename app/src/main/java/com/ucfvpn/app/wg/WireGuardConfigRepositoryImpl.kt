package com.ucfvpn.app.wg

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Production implementation of [WireGuardConfigRepository] using Android platform security.
 *
 * **Security architecture:**
 * - **WireGuard private key & pre-shared key** → encrypted with an AES-256 key
 *   that lives exclusively in Android Keystore (hardware-backed on supported devices).
 *   The encrypted blobs are stored in [EncryptedSharedPreferences].
 * - **Non-sensitive config** (addresses, endpoints, DNS) → stored directly in
 *   [EncryptedSharedPreferences], which provides transparent AES-256 encryption
 *   with its own Keystore-backed master key.
 *
 * This provides defense in depth: even if the EncryptedSharedPreferences master key
 * is compromised, the WireGuard keys remain protected by a separate Keystore entry
 * that cannot be extracted.
 *
 * @param context Application context (non-UI, retained for process lifetime)
 */
class WireGuardConfigRepositoryImpl(context: Context) : WireGuardConfigRepository {

    companion object {
        private const val ENCRYPTED_PREFS_NAME = "ucf_vpn_wg_config"
        private const val KEY_ALIAS_MASTER = "ucf_vpn_wg_master_key"

        // EncryptedSharedPreferences keys for sensitive key blobs
        private const val PREF_ENCRYPTED_PRIVATE_KEY = "wg_encrypted_private_key"
        private const val PREF_ENCRYPTED_PRESHARED_KEY = "wg_encrypted_preshared_key"

        // EncryptedSharedPreferences keys for non-sensitive config
        private const val PREF_ADDRESS = "wg_address"
        private const val PREF_DNS = "wg_dns"
        private const val PREF_MTU = "wg_mtu"
        private const val PREF_PEER_PUBLIC_KEY = "wg_peer_public_key"
        private const val PREF_PEER_ENDPOINT = "wg_peer_endpoint"
        private const val PREF_ALLOWED_IPS = "wg_allowed_ips"
        private const val PREF_PERSISTENT_KEEPALIVE = "wg_persistent_keepalive"

        // AES/GCM constants
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private val keystore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val encryptedPrefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        encryptedPrefs = EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ──────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────

    override fun saveConfig(config: WireGuardConfig) {
        encryptedPrefs.edit()
            .putString(PREF_ADDRESS, config.address)
            .putString(PREF_DNS, config.dns.joinToString(","))
            .putInt(PREF_MTU, config.mtu)
            .putString(PREF_PEER_PUBLIC_KEY, config.peerPublicKey)
            .putString(PREF_PEER_ENDPOINT, config.peerEndpoint)
            .putString(PREF_ALLOWED_IPS, config.allowedIps.joinToString(","))
            .putInt(PREF_PERSISTENT_KEEPALIVE, config.persistentKeepalive)
            .apply()

        if (config.privateKey.isNotBlank()) {
            savePrivateKey(config.privateKey)
        }
        if (config.peerPresharedKey != null && config.peerPresharedKey.isNotBlank()) {
            savePresharedKey(config.peerPresharedKey)
        }
    }

    override fun loadConfig(): WireGuardConfig? {
        val privateKey = loadPrivateKey() ?: run {
            Timber.d("No private key found in Keystore")
            return null
        }

        val address = encryptedPrefs.getString(PREF_ADDRESS, null) ?: return null
        val dnsRaw = encryptedPrefs.getString(PREF_DNS, "") ?: ""
        val mtu = encryptedPrefs.getInt(PREF_MTU, 1420)
        val peerPublicKey = encryptedPrefs.getString(PREF_PEER_PUBLIC_KEY, null) ?: return null
        val peerPresharedKey = loadPresharedKey()
        val peerEndpoint = encryptedPrefs.getString(PREF_PEER_ENDPOINT, null) ?: return null
        val allowedIpsRaw = encryptedPrefs.getString(PREF_ALLOWED_IPS, "") ?: ""
        val persistentKeepalive = encryptedPrefs.getInt(PREF_PERSISTENT_KEEPALIVE, 0)

        return WireGuardConfig(
            privateKey = privateKey,
            address = address,
            dns = parseCsv(dnsRaw),
            mtu = mtu,
            peerPublicKey = peerPublicKey,
            peerPresharedKey = peerPresharedKey,
            peerEndpoint = peerEndpoint,
            allowedIps = parseCsv(allowedIpsRaw),
            persistentKeepalive = persistentKeepalive
        )
    }

    override fun deleteConfig() {
        encryptedPrefs.edit().clear().apply()
        clearKeys()
    }

    override fun savePrivateKey(key: String): Boolean {
        return try {
            val encrypted = encryptWithKeystoreKey(key)
            encryptedPrefs.edit()
                .putString(PREF_ENCRYPTED_PRIVATE_KEY, encrypted)
                .apply()
            Timber.d("Private key saved to Keystore")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to save private key to Keystore")
            false
        }
    }

    override fun loadPrivateKey(): String? {
        return try {
            val encrypted = encryptedPrefs.getString(PREF_ENCRYPTED_PRIVATE_KEY, null) ?: return null
            decryptWithKeystoreKey(encrypted)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load private key from Keystore")
            null
        }
    }

    override fun savePresharedKey(key: String): Boolean {
        return try {
            val encrypted = encryptWithKeystoreKey(key)
            encryptedPrefs.edit()
                .putString(PREF_ENCRYPTED_PRESHARED_KEY, encrypted)
                .apply()
            Timber.d("Pre-shared key saved to Keystore")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to save pre-shared key to Keystore")
            false
        }
    }

    override fun loadPresharedKey(): String? {
        return try {
            val encrypted = encryptedPrefs.getString(PREF_ENCRYPTED_PRESHARED_KEY, null) ?: return null
            decryptWithKeystoreKey(encrypted)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load pre-shared key from Keystore")
            null
        }
    }

    override fun clearKeys() {
        try {
            keystore.deleteEntry(KEY_ALIAS_MASTER)
            encryptedPrefs.edit()
                .remove(PREF_ENCRYPTED_PRIVATE_KEY)
                .remove(PREF_ENCRYPTED_PRESHARED_KEY)
                .apply()
            Timber.d("Keystore keys cleared")
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear Keystore keys")
        }
    }

    override fun isConfigured(): Boolean {
        return loadPrivateKey() != null
            && encryptedPrefs.getString(PREF_ADDRESS, null) != null
            && encryptedPrefs.getString(PREF_PEER_PUBLIC_KEY, null) != null
            && encryptedPrefs.getString(PREF_PEER_ENDPOINT, null) != null
    }

    // ──────────────────────────────────────────────
    //  Keystore cryptography helpers
    // ──────────────────────────────────────────────

    /**
     * Encrypt a plaintext string using the AES key from Android Keystore.
     *
     * Returns a Base64-encoded string containing: IV (12 bytes) || ciphertext.
     * The IV is randomly generated per encryption operation.
     */
    private fun encryptWithKeystoreKey(plaintext: String): String {
        val secretKey = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey) // random IV generated
        val iv = cipher.iv // 12 bytes for GCM
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // Combine IV + ciphertext into a single byte array, then Base64 encode
        val combined = iv + ciphertext
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypt a Base64-encoded blob (IV + ciphertext) using the AES key from Android Keystore.
     */
    private fun decryptWithKeystoreKey(encryptedBlob: String): String {
        val secretKey = getOrCreateKeystoreKey()
        val combined = Base64.decode(encryptedBlob, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    /**
     * Retrieve the AES encryption key from Android Keystore, or generate it if absent.
     * The key is hardware-backed when the device supports it (TEE/StrongBox).
     */
    private fun getOrCreateKeystoreKey(): SecretKey {
        if (keystore.containsAlias(KEY_ALIAS_MASTER)) {
            val entry = keystore.getEntry(KEY_ALIAS_MASTER, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }
        return generateKeystoreKey()
    }

    /**
     * Generate a new AES-256 key in Android Keystore for GCM encryption.
     * The key material never leaves the secure hardware (when available).
     */
    private fun generateKeystoreKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS_MASTER,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    // ──────────────────────────────────────────────
    //  Utility
    // ──────────────────────────────────────────────

    private fun parseCsv(raw: String): List<String> {
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
