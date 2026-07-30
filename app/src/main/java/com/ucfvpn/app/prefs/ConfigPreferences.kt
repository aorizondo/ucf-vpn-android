package com.ucfvpn.app.prefs

import android.content.Context
import android.content.SharedPreferences
import com.ucfvpn.app.ui.viewmodel.UiConfig
import com.ucfvpn.app.ui.viewmodel.WstunnelMode

/**
 * Persists [UiConfig] to [SharedPreferences] so that the user's
 * wstunnel dynamic settings survive process restarts.
 *
 * All keys are prefixed with `ucf_` to avoid collisions.
 */
class ConfigPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Load a saved [UiConfig], falling back to defaults when no value exists. */
    fun load(): UiConfig {
        return UiConfig(
            sstpHost = prefs.getString(KEY_SSTP_HOST, null) ?: DEFAULT.sstpHost,
            sstpPort = prefs.getInt(KEY_SSTP_PORT, DEFAULT.sstpPort),
            sstpUsername = prefs.getString(KEY_SSTP_USERNAME, null) ?: DEFAULT.sstpUsername,
            sstpPassword = prefs.getString(KEY_SSTP_PASSWORD, null) ?: DEFAULT.sstpPassword,
            proxyHost = prefs.getString(KEY_PROXY_HOST, null) ?: DEFAULT.proxyHost,
            proxyPort = prefs.getInt(KEY_PROXY_PORT, DEFAULT.proxyPort),
            proxyUsername = prefs.getString(KEY_PROXY_USERNAME, null) ?: DEFAULT.proxyUsername,
            proxyPassword = prefs.getString(KEY_PROXY_PASSWORD, null) ?: DEFAULT.proxyPassword,
            wstunnelUrl = prefs.getString(KEY_WS_URL, null) ?: DEFAULT.wstunnelUrl,
            wstunnelMode = parseWstunnelMode(
                prefs.getString(KEY_WS_MODE, DEFAULT.wstunnelMode.name)
                    ?: DEFAULT.wstunnelMode.name
            ),
            wstunnelLocalPort = prefs.getInt(KEY_WS_LOCAL_PORT, DEFAULT.wstunnelLocalPort),
            wstunnelRemoteHost = prefs.getString(KEY_WS_REMOTE_HOST, null) ?: DEFAULT.wstunnelRemoteHost,
            wstunnelRemotePort = prefs.getInt(KEY_WS_REMOTE_PORT, DEFAULT.wstunnelRemotePort),
            wstunnelWsPingFrequency = prefs.getString(KEY_WS_PING_FREQ, null)
                ?: DEFAULT.wstunnelWsPingFrequency,
            wstunnelRetryMaxBackoff = prefs.getString(KEY_WS_RETRY_BACKOFF, null)
                ?: DEFAULT.wstunnelRetryMaxBackoff,
            wireGuardEndpoint = prefs.getString(KEY_WG_ENDPOINT, null) ?: DEFAULT.wireGuardEndpoint,
            wireGuardLocalIp = prefs.getString(KEY_WG_LOCAL_IP, null) ?: DEFAULT.wireGuardLocalIp,
            wireGuardDns = prefs.getString(KEY_WG_DNS, null) ?: DEFAULT.wireGuardDns,
            ignoreSslErrors = prefs.getBoolean(KEY_IGNORE_SSL, DEFAULT.ignoreSslErrors),
            autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, DEFAULT.autoReconnect)
        )
    }

    /** Persist the given [config]. */
    fun save(config: UiConfig) {
        prefs.edit()
            .putString(KEY_SSTP_HOST, config.sstpHost)
            .putInt(KEY_SSTP_PORT, config.sstpPort)
            .putString(KEY_SSTP_USERNAME, config.sstpUsername)
            .putString(KEY_SSTP_PASSWORD, config.sstpPassword)
            .putString(KEY_PROXY_HOST, config.proxyHost)
            .putInt(KEY_PROXY_PORT, config.proxyPort)
            .putString(KEY_PROXY_USERNAME, config.proxyUsername)
            .putString(KEY_PROXY_PASSWORD, config.proxyPassword)
            .putString(KEY_WS_URL, config.wstunnelUrl)
            .putString(KEY_WS_MODE, config.wstunnelMode.name)
            .putInt(KEY_WS_LOCAL_PORT, config.wstunnelLocalPort)
            .putString(KEY_WS_REMOTE_HOST, config.wstunnelRemoteHost)
            .putInt(KEY_WS_REMOTE_PORT, config.wstunnelRemotePort)
            .putString(KEY_WS_PING_FREQ, config.wstunnelWsPingFrequency)
            .putString(KEY_WS_RETRY_BACKOFF, config.wstunnelRetryMaxBackoff)
            .putString(KEY_WG_ENDPOINT, config.wireGuardEndpoint)
            .putString(KEY_WG_LOCAL_IP, config.wireGuardLocalIp)
            .putString(KEY_WG_DNS, config.wireGuardDns)
            .putBoolean(KEY_IGNORE_SSL, config.ignoreSslErrors)
            .putBoolean(KEY_AUTO_RECONNECT, config.autoReconnect)
            .apply()
    }

    private fun parseWstunnelMode(name: String): WstunnelMode =
        try {
            WstunnelMode.valueOf(name)
        } catch (_: IllegalArgumentException) {
            DEFAULT.wstunnelMode
        }

    companion object {
        private const val PREFS_NAME = "ucf_vpn_config"

        // Key constants — all prefixed with ucf_
        private const val KEY_SSTP_HOST = "ucf_sstp_host"
        private const val KEY_SSTP_PORT = "ucf_sstp_port"
        private const val KEY_SSTP_USERNAME = "ucf_sstp_username"
        private const val KEY_SSTP_PASSWORD = "ucf_sstp_password"
        private const val KEY_PROXY_HOST = "ucf_proxy_host"
        private const val KEY_PROXY_PORT = "ucf_proxy_port"
        private const val KEY_PROXY_USERNAME = "ucf_proxy_username"
        private const val KEY_PROXY_PASSWORD = "ucf_proxy_password"
        private const val KEY_WS_URL = "ucf_ws_url"
        private const val KEY_WS_MODE = "ucf_ws_mode"
        private const val KEY_WS_LOCAL_PORT = "ucf_ws_local_port"
        private const val KEY_WS_REMOTE_HOST = "ucf_ws_remote_host"
        private const val KEY_WS_REMOTE_PORT = "ucf_ws_remote_port"
        private const val KEY_WS_PING_FREQ = "ucf_ws_ping_freq"
        private const val KEY_WS_RETRY_BACKOFF = "ucf_ws_retry_backoff"
        private const val KEY_WG_ENDPOINT = "ucf_wg_endpoint"
        private const val KEY_WG_LOCAL_IP = "ucf_wg_local_ip"
        private const val KEY_WG_DNS = "ucf_wg_dns"
        private const val KEY_IGNORE_SSL = "ucf_ignore_ssl"
        private const val KEY_AUTO_RECONNECT = "ucf_auto_reconnect"

        /** Default [UiConfig] for fallback when no persisted value exists. */
        private val DEFAULT = UiConfig()
    }
}
