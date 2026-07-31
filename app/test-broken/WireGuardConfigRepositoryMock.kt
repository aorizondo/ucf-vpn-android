package com.ucfvpn.app.wg

/**
 * In-memory mock implementation of [WireGuardConfigRepository] for unit testing.
 *
 * Stores all config and keys in plain HashMaps with no encryption.
 * This allows pure JVM unit tests (no Android framework required).
 *
 * Thread-safe: uses synchronized access for all mutable operations.
 */
class WireGuardConfigRepositoryMock : WireGuardConfigRepository {

    private val lock = Any()
    private var config: WireGuardConfig? = null
    private var privateKey: String? = null
    private var presharedKey: String? = null

    override fun saveConfig(config: WireGuardConfig) {
        synchronized(lock) {
            this.config = config
            if (config.privateKey.isNotBlank()) {
                this.privateKey = config.privateKey
            }
            if (config.peerPresharedKey != null && config.peerPresharedKey.isNotBlank()) {
                this.presharedKey = config.peerPresharedKey
            }
        }
    }

    override fun loadConfig(): WireGuardConfig? {
        synchronized(lock) {
            val pk = privateKey ?: return null
            val cfg = config ?: return null
            return cfg.copy(
                privateKey = pk,
                peerPresharedKey = presharedKey
            )
        }
    }

    override fun deleteConfig() {
        synchronized(lock) {
            config = null
            privateKey = null
            presharedKey = null
        }
    }

    override fun savePrivateKey(key: String): Boolean {
        synchronized(lock) {
            privateKey = key
            return true
        }
    }

    override fun loadPrivateKey(): String? {
        synchronized(lock) {
            return privateKey
        }
    }

    override fun savePresharedKey(key: String): Boolean {
        synchronized(lock) {
            presharedKey = key
            return true
        }
    }

    override fun loadPresharedKey(): String? {
        synchronized(lock) {
            return presharedKey
        }
    }

    override fun clearKeys() {
        synchronized(lock) {
            privateKey = null
            presharedKey = null
        }
    }

    override fun isConfigured(): Boolean {
        synchronized(lock) {
            return privateKey != null
                && config != null
                && config!!.address.isNotBlank()
                && config!!.peerPublicKey.isNotBlank()
                && config!!.peerEndpoint.isNotBlank()
        }
    }
}
