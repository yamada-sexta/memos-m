package org.example.memosm.ui.component

import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.example.memosm.R
import org.example.memosm.ui.theme.MemosMTheme
import org.example.memosm.ui.setup.SsoLoginActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Uses a local OAuth fixture and the device's real browser; no external provider credentials. */
class SsoLoginTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var server: SsoServer
    private val success = AtomicReference<Pair<String, String>?>(null)

    @Before fun startServer() { server = SsoServer() }
    @After fun stopServer() { server.close() }
    private fun label(id: Int) = context.getString(id)
    private fun hostField() = compose.onNode(hasText(label(R.string.login_host_url)) and hasSetTextAction())

    private fun showLogin(permission: LocalNetworkPermissionState = LocalNetworkPermissionState({ true }, {}, { false })) {
        compose.setContent {
            MemosMTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalNetworkPermission provides permission) {
                    LoginContent(onLoginSuccess = { host, token -> success.set(host to token) })
                }
            }
        }
        compose.onNodeWithText(label(R.string.login_sso)).performClick()
        hostField().performTextReplacement(server.url)
    }

    private fun discover() {
        compose.onNodeWithText(label(R.string.login_sso_find_providers)).performScrollTo().performClick()
    }

    @Test fun discoversProvidersAndChangingServerClearsThem() {
        showLogin()
        discover()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Continue with Fixture").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Continue with Fixture").assertIsDisplayed()
        hostField().performTextReplacement("https://other.example/")
        compose.onNodeWithText("Continue with Fixture").assertDoesNotExist()
        assertNull(success.get())
    }

    @Test fun deniedNetworkPermissionStopsDiscovery() {
        val permission = LocalNetworkPermissionState({ false }, {}, { true })
        permission.launchRequest = { permission.onResult(false) }
        showLogin(permission)
        discover()
        compose.onNodeWithText(label(R.string.local_network_permission_denied)).assertIsDisplayed()
        assertTrue(server.requests.isEmpty())
    }

    @Test fun emptyProviderListExplainsThatSsoIsUnavailable() {
        server.providers = false
        showLogin()
        discover()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(label(R.string.login_sso_no_providers)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label(R.string.login_sso_no_providers)).assertIsDisplayed()
        assertNull(success.get())
    }

    @Test fun failedDiscoveryCanBeRetried() {
        server.failDiscovery = true
        showLogin()
        discover()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(label(R.string.login_sso_discovery_failed)).fetchSemanticsNodes().isNotEmpty()
        }
        server.failDiscovery = false
        discover()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Continue with Fixture").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Continue with Fixture").assertIsDisplayed()
    }

    @Test fun browserCallbackSurvivesActivityRecreationAndReturnsOnlyPersonalToken() {
        showLogin()
        discover()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Continue with Fixture").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Continue with Fixture").performClick()
        compose.waitUntil(15_000) { server.requests.any { it.startsWith("/authorize?") } }
        instrumentation.runOnMainSync {
            val activity = listOf(Stage.STOPPED, Stage.PAUSED).flatMap {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
            }.filterIsInstance<SsoLoginActivity>().single()
            activity.recreate()
        }
        // Accessibility performs a real browser link click, allowing its custom-scheme redirect.
        var clicked = false
        compose.waitUntil(30_000) {
            if (!clicked) {
                val nodes = instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("Complete test sign-in").orEmpty()
                clicked = nodes.any { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            }
            clicked
        }
        compose.waitUntil(15_000) { success.get() != null }
        assertEquals(server.url to "fixture-personal-token", success.get())
        assertEquals("fixture-code", server.signInBody.get()?.let {
            org.example.memosm.api.GsonProvider.gson.fromJson(it, com.google.gson.JsonObject::class.java)
                .getAsJsonObject("ssoCredentials")["code"].asString
        })
        assertTrue(server.sessionAuthorizationObserved)
        assertTrue(server.requests.contains("/api/v1/users/1/personalAccessTokens"))
    }

    @Test fun closingBrowserReturnsToLoginWithoutSavingCredentials() {
        showLogin()
        discover()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Continue with Fixture").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Continue with Fixture").performClick()
        compose.waitUntil(15_000) { server.requests.any { it.startsWith("/authorize?") } }
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Continue with Fixture").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Continue with Fixture").assertIsEnabled()
        assertNull(success.get())
        assertFalse(server.requests.contains("/api/v1/auth/signin"))
    }

    private class SsoServer : AutoCloseable {
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/"
        val requests = CopyOnWriteArrayList<String>()
        val signInBody = AtomicReference<String?>(null)
        @Volatile var providers = true
        @Volatile var failDiscovery = false
        @Volatile var sessionAuthorizationObserved = false
        private val worker = thread(isDaemon = true, name = "sso-test-server") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: java.net.SocketException) { break }
                client.use {
                    it.soTimeout = 5_000
                    val reader = it.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return@use
                    val path = requestLine.split(' ')[1]
                    requests += path
                    val headers = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
                    val length = headers.firstOrNull { header -> header.startsWith("Content-Length:", true) }
                        ?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val content = CharArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = reader.read(content, offset, length - offset)
                        if (count < 0) break
                        offset += count
                    }
                    val sessionHeader = headers.any { header -> header.equals("Authorization: Bearer fixture-session-token", true) }
                    var status = "200 OK"
                    var contentType = "application/json"
                    val body = when {
                        path == "/api/v1/instance/profile" -> """{"version":"26.10"}"""
                        path == "/api/v1/identity-providers" -> {
                            if (failDiscovery) { status = "503 Service Unavailable"; "{}" }
                            else if (!providers) """{"identityProviders":[]}"""
                            else """{"identityProviders":[{"name":"identity-providers/fixture","title":"Fixture","type":"OAUTH2","config":{"oauth2Config":{"clientId":"fixture-client","authUrl":"${url}authorize","scopes":["openid"]}}}]}"""
                        }
                        path.startsWith("/authorize?") -> {
                            contentType = "text/html"
                            val uri = Uri.parse(url.trimEnd('/') + path)
                            val redirect = uri.getQueryParameter("redirect_uri")
                            val state = uri.getQueryParameter("state")
                            """<html><meta name="viewport" content="width=device-width"><body><a href="$redirect?state=$state&amp;code=fixture-code">Complete test sign-in</a></body></html>"""
                        }
                        path == "/api/v1/auth/signin" -> {
                            signInBody.set(String(content))
                            """{"user":{"name":"users/1","username":"fixture"},"accessToken":"fixture-session-token","accessTokenExpiresAt":"2026-10-04T18:00:00Z"}"""
                        }
                        path == "/api/v1/auth/me" -> {
                            if (!sessionHeader) status = "401 Unauthorized"
                            """{"user":{"name":"users/1","username":"fixture"}}"""
                        }
                        path == "/api/v1/users/1/personalAccessTokens" -> {
                            sessionAuthorizationObserved = sessionHeader
                            if (!sessionHeader) status = "401 Unauthorized"
                            """{"personalAccessToken":{"name":"users/1/personalAccessTokens/fixture"},"token":"fixture-personal-token"}"""
                        }
                        else -> "{}"
                    }.toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
        override fun close() { socket.close(); worker.join(5_000) }
    }
}
