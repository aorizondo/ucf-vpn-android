package com.ucfvpn.app.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.net.CookieManager

class ProxyAuthService(
    baseUrl: String = "https://internet.ucf.edu.cu",
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(CookieManager()))
        .followRedirects(true)
        .build()
) {
    private val _authState = MutableStateFlow(ProxyAuthState.IDLE)
    val authState: StateFlow<ProxyAuthState> = _authState.asStateFlow()

    private val authMutex = Mutex()

    private var storedUsername: String? = null
    private var storedPassword: String? = null

    private val loginUrl = "$baseUrl/auth/login?next=/"
    private val portalHome = "$baseUrl/"

    companion object {
        private val CSRF_REGEX = Regex("""name="csrfmiddlewaretoken"\s+value="([^"]+)"""")
    }

    suspend fun login(username: String, password: String): Result<Unit> {
        return authMutex.withLock {
            _authState.value = ProxyAuthState.AUTHENTICATING
            try {
                storedUsername = username
                storedPassword = password
                performLogin(username, password)
                _authState.value = ProxyAuthState.AUTHENTICATED
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Proxy authentication failed")
                _authState.value = ProxyAuthState.ERROR
                Result.failure(e)
            }
        }
    }

    suspend fun checkAndReauth(): Boolean {
        return authMutex.withLock {
            try {
                if (_authState.value == ProxyAuthState.AUTHENTICATED && !isSessionExpired()) {
                    return@withLock true
                }

                val username = storedUsername
                val password = storedPassword

                if (username == null || password == null) {
                    Timber.w("Cannot reauth: no stored credentials")
                    _authState.value = ProxyAuthState.ERROR
                    return@withLock false
                }

                _authState.value = ProxyAuthState.EXPIRED
                _authState.value = ProxyAuthState.AUTHENTICATING
                performLogin(username, password)
                _authState.value = ProxyAuthState.AUTHENTICATED
                true
            } catch (e: Exception) {
                Timber.e(e, "Session check/re-auth failed")
                _authState.value = ProxyAuthState.ERROR
                false
            }
        }
    }

    fun reset() {
        storedUsername = null
        storedPassword = null
        _authState.value = ProxyAuthState.IDLE
    }

    // ── Private helpers ──

    private suspend fun performLogin(username: String, password: String) {
        // Step 1: GET login page → extract CSRF token
        val csrf1 = fetchCsrfToken(loginUrl)

        // Step 2: POST credentials → authenticate
        val loginBody = FormBody.Builder()
            .add("csrfmiddlewaretoken", csrf1)
            .add("username", username)
            .add("password", password)
            .add("first_step", "False")
            .build()
        executePost(loginUrl, loginBody)

        // Step 3: GET home page → extract new CSRF token
        val csrf2 = fetchCsrfToken(portalHome)

        // Step 4: POST create session → manual session creation
        val sessionBody = FormBody.Builder()
            .add("csrfmiddlewaretoken", csrf2)
            .add("manual", "Crear una sesion para este dispositivo")
            .build()
        executePost(portalHome, sessionBody)
    }

    private suspend fun fetchCsrfToken(url: String): String {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).get().build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                    ?: throw IOException("Empty response body from $url")
                CSRF_REGEX.find(body)?.groupValues?.getOrNull(1)
                    ?: throw IOException("CSRF token not found in response from $url")
            }
        }
    }

    private suspend fun executePost(url: String, body: FormBody) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).post(body).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("POST to $url failed with status ${response.code}")
                }
            }
        }
    }

    private suspend fun isSessionExpired(): Boolean {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(portalHome).get().build()
            okHttpClient.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                finalUrl.contains("/auth/login")
            }
        }
    }
}
