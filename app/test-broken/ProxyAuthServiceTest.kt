package com.ucfvpn.app.proxy

import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProxyAuthServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: ProxyAuthService

    @Before
    fun setUp() {
        server = MockWebServer()
        val baseUrl = server.url("").toString().removeSuffix("/")
        service = ProxyAuthService(baseUrl = baseUrl)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── Full login flow ──

    @Test
    fun `login full flow succeeds and transitions to AUTHENTICATED`() = runTest {
        enqueueLoginResponses("csrf-step1", "csrf-step3")

        val result = service.login("testuser", "testpass")

        assertTrue("Login should succeed", result.isSuccess)
        assertEquals(ProxyAuthState.AUTHENTICATED, service.authState.value)
    }

    @Test
    fun `login makes exactly 4 HTTP requests in correct order`() = runTest {
        enqueueLoginResponses("csrf-1", "csrf-2")

        service.login("u", "p")

        // Request 1: GET /auth/login?next=/
        val req1 = server.takeRequest()
        assertEquals("GET", req1.method)
        assertEquals(
            "/auth/login?next=/",
            req1.requestUrl?.encodedPath + "?" + req1.requestUrl?.encodedQuery
        )

        // Request 2: POST /auth/login?next=/
        val req2 = server.takeRequest()
        assertEquals("POST", req2.method)
        val body2 = req2.body.readUtf8()
        assertTrue(body2.contains("csrfmiddlewaretoken=csrf-1"))
        assertTrue(body2.contains("username=u"))
        assertTrue(body2.contains("password=p"))
        assertTrue(body2.contains("first_step=False"))

        // Request 3: GET /
        val req3 = server.takeRequest()
        assertEquals("GET", req3.method)
        assertEquals("/", req3.requestUrl?.encodedPath)

        // Request 4: POST /
        val req4 = server.takeRequest()
        assertEquals("POST", req4.method)
        val body4 = req4.body.readUtf8()
        assertTrue(body4.contains("csrfmiddlewaretoken=csrf-2"))
        assertTrue(body4.contains("manual=Crear+una+sesion+para+este+dispositivo"))
    }

    // ── CSRF extraction ──

    @Test
    fun `CSRF token extracted from HTML with standard format`() = runTest {
        enqueueLoginResponses("abc123", "def456")

        service.login("u", "p")

        server.takeRequest() // skip GET
        val req2 = server.takeRequest() // POST
        server.takeRequest() // skip GET
        server.takeRequest() // skip POST
        assertTrue(req2.body.readUtf8().contains("csrfmiddlewaretoken=abc123"))
    }

    @Test
    fun `CSRF token extracted when input tag has extra whitespace`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """<input type="hidden" name="csrfmiddlewaretoken"    value="multi-space-token" />"""
            )
        )
        server.enqueue(MockResponse())
        server.enqueue(
            MockResponse().setBody(
                """<input name="csrfmiddlewaretoken" value="csrf-step3" />"""
            )
        )
        server.enqueue(MockResponse())

        service.login("u", "p")

        server.takeRequest() // GET
        val req2 = server.takeRequest() // POST
        assertTrue(req2.body.readUtf8().contains("csrfmiddlewaretoken=multi-space-token"))
    }

    @Test
    fun `login fails when CSRF token not found in response`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """<html><p>Login page without CSRF field</p></html>"""
            )
        )

        val result = service.login("u", "p")
        assertTrue(result.isFailure)
        assertEquals(ProxyAuthState.ERROR, service.authState.value)
    }

    // ── Cookie persistence ──

    @Test
    fun `cookies are persisted and sent in subsequent requests`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""<input name="csrfmiddlewaretoken" value="csrf-1" />""")
                .addHeader("Set-Cookie", "sessionid=s3ss10n; Path=/")
        )
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "csrftoken=csrf-cookie; Path=/")
        )
        server.enqueue(
            MockResponse()
                .setBody("""<input name="csrfmiddlewaretoken" value="csrf-2" />""")
        )
        server.enqueue(MockResponse())

        service.login("u", "p")

        server.takeRequest() // req 1
        server.takeRequest() // req 2

        val req3 = server.takeRequest()
        val cookieHeader = req3.getHeader("Cookie")
        assertNotNull("Cookie header should be present", cookieHeader)
        assertTrue(cookieHeader!!.contains("sessionid=s3ss10n"))
    }

    // ── Session expiry and re-authentication ──

    @Test
    fun `checkAndReauth triggers re-auth when session expired`() = runTest {
        enqueueLoginResponses("csrf-a", "csrf-b")
        service.login("u", "p")
        assertEquals(ProxyAuthState.AUTHENTICATED, service.authState.value)

        // Session check: GET / → 302 redirect to login page
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", server.url("/auth/login?next=/").toString())
        )
        // Redirect target (GET /auth/login?next=/)
        server.enqueue(
            MockResponse()
                .setBody("""<input name="csrfmiddlewaretoken" value="csrf-expired-redirect" />""")
        )
        // performLogin step 1: GET /auth/login?next=/ → extract CSRF
        server.enqueue(
            MockResponse()
                .setBody("""<input name="csrfmiddlewaretoken" value="csrf-reauth-1" />""")
        )
        // performLogin step 2: POST /auth/login?next=/
        server.enqueue(MockResponse())
        // performLogin step 3: GET / with CSRF
        server.enqueue(
            MockResponse()
                .setBody("""<input name="csrfmiddlewaretoken" value="csrf-reauth-2" />""")
        )
        // performLogin step 4: POST /
        server.enqueue(MockResponse())
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", server.url("/auth/login?next=/").toString())
        )
        // Redirect target (GET /auth/login?next=/)
        server.enqueue(
            MockResponse()
                .setBody("""<input name="csrfmiddlewaretoken" value="csrf-reauth-1" />""")
        )
        // Re-auth: POST /auth/login?next=/
        server.enqueue(MockResponse())
        // Re-auth: GET / with CSRF
        server.enqueue(
            MockResponse()
                .setBody("""<input name="csrfmiddlewaretoken" value="csrf-reauth-2" />""")
        )
        // Re-auth: POST /
        server.enqueue(MockResponse())

        val result = service.checkAndReauth()

        assertTrue("checkAndReauth should succeed", result)
        assertEquals(ProxyAuthState.AUTHENTICATED, service.authState.value)
    }

    @Test
    fun `checkAndReauth fails when no stored credentials`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", server.url("/auth/login?next=/").toString())
        )
        server.enqueue(
            MockResponse().setBody("""<input name="csrfmiddlewaretoken" value="x" />""")
        )

        val result = service.checkAndReauth()
        assertFalse("Should fail without stored credentials", result)
        assertEquals(ProxyAuthState.ERROR, service.authState.value)
    }

    @Test
    fun `checkAndReauth returns true when session is still valid`() = runTest {
        enqueueLoginResponses("csrf-a", "csrf-b")
        service.login("u", "p")
        assertEquals(ProxyAuthState.AUTHENTICATED, service.authState.value)

        // Session check: GET / returns 200 (session valid, no redirect)
        server.enqueue(MockResponse().setResponseCode(200))

        val result = service.checkAndReauth()
        assertTrue("Session should be valid", result)
        assertEquals(ProxyAuthState.AUTHENTICATED, service.authState.value)
    }

    // ── Error handling ──

    @Test
    fun `login fails with ERROR state on server 500`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = service.login("u", "p")
        assertTrue(result.isFailure)
        assertEquals(ProxyAuthState.ERROR, service.authState.value)
    }

    @Test
    fun `login fails with ERROR state on non-success POST status`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""<input name="csrfmiddlewaretoken" value="tok" />""")
        )
        server.enqueue(MockResponse().setResponseCode(403))

        val result = service.login("u", "p")
        assertTrue(result.isFailure)
        assertEquals(ProxyAuthState.ERROR, service.authState.value)
    }

    @Test
    fun `login fails with ERROR state on empty response body`() = runTest {
        server.enqueue(MockResponse().setBody(""))

        val result = service.login("u", "p")
        assertTrue(result.isFailure)
        assertEquals(ProxyAuthState.ERROR, service.authState.value)
    }

    // ── State transitions and reset ──

    @Test
    fun `authState starts IDLE and reaches AUTHENTICATED after login`() = runTest {
        assertEquals(ProxyAuthState.IDLE, service.authState.value)

        val stateLog = mutableListOf<ProxyAuthState>()
        val collectorJob = launch {
            service.authState.collect { stateLog.add(it) }
        }

        enqueueLoginResponses("cs1", "cs2")
        service.login("u", "p")

        assertEquals(ProxyAuthState.AUTHENTICATED, service.authState.value)
        assertTrue("Should pass through AUTHENTICATING", stateLog.contains(ProxyAuthState.AUTHENTICATING))
        assertTrue("Should reach AUTHENTICATED", stateLog.contains(ProxyAuthState.AUTHENTICATED))

        collectorJob.cancel()
    }

    @Test
    fun `reset clears state to IDLE and loses credentials`() = runTest {
        enqueueLoginResponses("cs1", "cs2")
        service.login("u", "p")
        assertEquals(ProxyAuthState.AUTHENTICATED, service.authState.value)

        service.reset()
        assertEquals(ProxyAuthState.IDLE, service.authState.value)

        // Without new login, checkAndReauth should fail (no stored credentials)
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", server.url("/auth/login?next=/").toString())
        )
        server.enqueue(
            MockResponse().setBody("""<input name="csrfmiddlewaretoken" value="x" />""")
        )

        val result = service.checkAndReauth()
        assertFalse("Should fail after reset clears credentials", result)
        assertEquals(ProxyAuthState.ERROR, service.authState.value)
    }

    // ── Helpers ──

    private fun enqueueLoginResponses(csrf1: String, csrf3: String) {
        server.enqueue(
            MockResponse()
                .setBody("""<input name="csrfmiddlewaretoken" value="$csrf1" />""")
        )
        server.enqueue(MockResponse())
        server.enqueue(
            MockResponse()
                .setBody("""<input name="csrfmiddlewaretoken" value="$csrf3" />""")
        )
        server.enqueue(MockResponse())
    }
}
