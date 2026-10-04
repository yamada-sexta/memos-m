package org.example.memosm.ui.profile

import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import org.example.memosm.MainActivity
import org.example.memosm.R
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.model.Account
import org.example.memosm.model.UserSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class EditAccountCredentialsActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val settings get() = GlobalContext.get().get<DataStoreManager>()
    private lateinit var server: CredentialsServer
    private lateinit var originalAccounts: List<Account>
    private lateinit var active: Account
    private lateinit var inactive: Account
    private fun label(id: Int) = context.getString(id)
    private fun field(id: Int) = compose.onNode(hasText(label(id)) and hasSetTextAction())

    @Before
    fun seedAccounts() = runBlocking {
        BackupCoordinator.awaitStartupRecovery()
        originalAccounts = settings.getAccounts()
        server = CredentialsServer()
        active = Account(id = "credentials-active", hostUrl = "https://example.invalid/",
            accessToken = "active-token", name = "credentials-active", displayName = "Credential active",
            isActive = true, user = UserSnapshot(name = "users/1", username = "credentials-active",
                displayName = "Credential active"))
        inactive = Account(id = "credentials-inactive", hostUrl = server.url,
            accessToken = "inactive-token", name = "credentials-inactive", displayName = "Credential inactive")
        settings.completeSetup()
        settings.saveAccounts(listOf(active, inactive))
    }

    @After
    fun restoreAccounts() = runBlocking {
        server.close()
        settings.saveAccounts(originalAccounts)
    }

    @Test
    fun fullScreenEditorLoadsInactiveAccountAndRetainsDraftOnRecreation() {
        ActivityScenario.launch<EditAccountCredentialsActivity>(
            EditAccountCredentialsActivity.createIntent(context, inactive.id)
        ).use { scenario ->
            awaitEditor()
            compose.onNode(isDialog()).assertDoesNotExist()
            field(R.string.login_host_url).assertTextContains(inactive.hostUrl)
            field(R.string.login_token).assertTextContains(inactive.accessToken)
            field(R.string.login_host_url).performTextReplacement("https://changed.invalid/")
            field(R.string.login_token).performTextReplacement("unsaved-token")
            scenario.recreate()
            awaitEditor()
            field(R.string.login_host_url).assertTextContains("https://changed.invalid/")
            field(R.string.login_token).assertTextContains("unsaved-token")
            compose.onNodeWithText(label(R.string.common_save)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun swipeRightOpensFullScreenAndToolbarBackReturnsToAccountSwitcher() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openEditor(active)
            compose.onNodeWithContentDescription(label(R.string.memo_detail_back)).performClick()
            awaitResumed(MainActivity::class.java)
            compose.onNodeWithText(label(R.string.profile_accounts)).assertIsDisplayed()
            assertEquals(listOf(active, inactive), runBlocking { settings.getAccounts() })
        }
    }

    @Test
    fun canceledPredictiveBackKeepsDraftAndCompletedGestureReturnsToSwitcher() {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        assumeTrue(Settings.Secure.getInt(context.contentResolver, "navigation_mode", 0) == 2)
        ActivityScenario.launch(MainActivity::class.java).use {
            openEditor(inactive)
            field(R.string.login_host_url).performTextReplacement("https://unsaved.invalid/")
            compose.onNodeWithText(label(R.string.profile_edit_credentials)).performClick()
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            backGesture(cancel = true)
            awaitResumed(EditAccountCredentialsActivity::class.java)
            field(R.string.login_host_url).assertTextContains("https://unsaved.invalid/")
            backGesture(cancel = false)
            awaitResumed(MainActivity::class.java)
            compose.onNodeWithText(label(R.string.profile_accounts)).assertIsDisplayed()
            assertEquals(listOf(active, inactive), runBlocking { settings.getAccounts() })
        }
    }

    @Test
    fun invalidUrlKeepsEditorOpenWithoutSaving() {
        ActivityScenario.launch<EditAccountCredentialsActivity>(
            EditAccountCredentialsActivity.createIntent(context, inactive.id)
        ).use {
            awaitEditor()
            field(R.string.login_host_url).performTextReplacement("https://[")
            compose.onNodeWithText(label(R.string.common_save)).performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.login_error_invalid_url)).assertIsDisplayed()
            assertEquals(listOf(active, inactive), runBlocking { settings.getAccounts() })
        }
    }

    @Test
    fun verifiedSaveUpdatesOnlySwipedAccountAndClosesSwitcher() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openEditor(inactive)
            field(R.string.login_token).performTextReplacement("verified-token")
            compose.onNodeWithText(label(R.string.common_save)).performScrollTo().performClick()
            awaitResumed(MainActivity::class.java)
            compose.waitUntil(10_000) {
                runBlocking { settings.getAccounts().firstOrNull { it.id == inactive.id }?.accessToken == "verified-token" }
            }
            compose.onNodeWithText(label(R.string.profile_accounts)).assertDoesNotExist()
            assertEquals(listOf(active, inactive.copy(accessToken = "verified-token")),
                runBlocking { settings.getAccounts() })
        }
    }

    @Test
    fun removedAccountClosesEditorWithoutSaving() {
        ActivityScenario.launch<EditAccountCredentialsActivity>(
            EditAccountCredentialsActivity.createIntent(context, inactive.id)
        ).use { scenario ->
            awaitEditor()
            runBlocking { settings.saveAccounts(listOf(active)) }
            compose.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
            assertEquals(listOf(active), runBlocking { settings.getAccounts() })
        }
    }

    private fun openEditor(account: Account) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(label(R.string.nav_profile)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label(R.string.nav_profile)).performClick()
        compose.onNodeWithText(active.displayName!!).performClick()
        compose.onNodeWithText(label(R.string.profile_accounts)).assertIsDisplayed()
        compose.onNodeWithText(account.displayName!!).performTouchInput {
            swipe(Offset(1f, centerY), Offset(width - 1f, centerY), durationMillis = 300)
        }
        awaitEditor()
        field(R.string.login_host_url).assertTextContains(account.hostUrl)
    }

    private fun awaitEditor() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(label(R.string.profile_edit_credentials)).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText(label(R.string.login_host_url)) and hasSetTextAction())
                    .fetchSemanticsNodes().isNotEmpty()
        }
        awaitResumed(EditAccountCredentialsActivity::class.java)
    }

    private fun awaitResumed(type: Class<*>) {
        compose.waitUntil(15_000) {
            var resumed = false
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).any(type::isInstance)
            }
            resumed
        }
    }

    private fun backGesture(cancel: Boolean) {
        val metrics = context.resources.displayMetrics
        val downTime = SystemClock.uptimeMillis()
        val y = metrics.heightPixels * 0.5f
        fun inject(action: Int, x: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
        inject(MotionEvent.ACTION_DOWN, 1f)
        for (step in 1..20) {
            SystemClock.sleep(16)
            inject(MotionEvent.ACTION_MOVE, metrics.widthPixels * 0.45f * step / 20)
        }
        inject(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, metrics.widthPixels * 0.45f)
    }

    /** A loopback API makes credential verification independent of an external Memos server. */
    private class CredentialsServer : AutoCloseable {
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/"
        private val worker = thread(isDaemon = true, name = "credential-verification-test") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: java.net.SocketException) { break }
                client.use {
                    it.soTimeout = 5_000
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    val headers = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
                    val authorized = headers.any { header -> header.equals("Authorization: Bearer verified-token", ignoreCase = true) }
                    val status = if (path == "/api/v1/instance/profile" || authorized) "200 OK" else "401 Unauthorized"
                    val body = when (path) {
                        "/api/v1/instance/profile" -> """{"version":"0.30.0"}"""
                        "/api/v1/auth/me" -> """{"user":{"name":"users/2","username":"credentials-inactive"}}"""
                        else -> "{}"
                    }.toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
        override fun close() {
            socket.close()
            worker.join(5_000)
        }
    }
}
