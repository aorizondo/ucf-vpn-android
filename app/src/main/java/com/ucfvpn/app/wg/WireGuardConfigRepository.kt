package com.ucfvpn.app.wg

/**
 * Repository for persisting and retrieving WireGuard tunnel configuration.
 *
 * Implements a two-layer security model:
 * - **Sensitive keys** (privateKey, preSharedKey) → Android Keystore
 * - **Non-sensitive config** (addresses, DNS, endpoints) → EncryptedSharedPreferences
 *
 * Two implementations exist:
 * 1. [WireGuardConfigRepositoryImpl] — production, uses Android framework APIs
 * 2. [WireGuardConfigRepositoryMock] — in-memory, for unit testing
 */
interface WireGuardConfigRepository {
    /**
     * Persist a complete WireGuard configuration.
     * Private key and pre-shared key are stored in Android Keystore;
     * all other fields go into EncryptedSharedPreferences.
     */
    fun saveConfig(config: WireGuardConfig)

    /**
     * Load the full WireGuard configuration, including keys from Keystore.
     * Returns null when no configuration has been saved.
     */
    fun loadConfig(): WireGuardConfig?

    /**
     * Remove the stored configuration and all associated keys.
     */
    fun deleteConfig()

    /**
     * Store the WireGuard private key in Android Keystore.
     * @return true if storage succeeded, false otherwise
     */
    fun savePrivateKey(key: String): Boolean

    /**
     * Retrieve the WireGuard private key from Android Keystore.
     * @return the key as a Base64 string, or null if not stored
     */
    fun loadPrivateKey(): String?

    /**
     * Store the WireGuard pre-shared key in Android Keystore.
     * @return true if storage succeeded, false otherwise
     */
    fun savePresharedKey(key: String): Boolean

    /**
     * Retrieve the WireGuard pre-shared key from Android Keystore.
     * @return the key as a Base64 string, or null if not stored
     */
    fun loadPresharedKey(): String?

    /**
     * Remove all keys from Android Keystore.
     */
    fun clearKeys()

    /**
     * Whether a valid configuration has been saved.
     */
    fun isConfigured(): Boolean
}
