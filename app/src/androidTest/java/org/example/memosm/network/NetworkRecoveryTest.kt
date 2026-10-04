package org.example.memosm.network

import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.example.memosm.api.ServerRateLimit
import org.example.memosm.viewmodel.ConnectionState
import org.example.memosm.MainActivity
import org.example.memosm.api.AuthInterceptor
import org.example.memosm.api.MemosApiFactory
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.network.ConnectivityObserver
import org.example.memosm.model.Account
import org.example.memosm.model.Memo
import org.example.memosm.model.PasswordCredentials
import org.example.memosm.model.SignInRequest
import org.example.memosm.model.toUserSnapshot
import org.example.memosm.viewmodel.MemosViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

/** Run against a disposable server with -e recoveryHost http://10.0.2.2:15230/. */
class NetworkRecoveryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String = instrumentation.uiAutomation
        .executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }

    private fun await(message: String, timeoutMs: Long = 20_000, predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue(message, predicate())
    }

    @Test fun recoversAfterAppNetworkAccessIsRestored() = exerciseRestriction(doze = false)

    @Test fun recoversAfterScreenOffWithBatterySaver() = exerciseRestriction(doze = false, batterySaver = true)

    @Test fun recoversAfterScreenOffWithBatterySaverAndDoze() = exerciseRestriction(doze = true, batterySaver = true)

    @Test fun remoteDeletionIsRemovedFromCacheAndDisplayedList() = exerciseRestriction(doze = false, deleteRemote = true)

    @Test fun recoversAfterRateLimitCooldownWithoutRestart() = exerciseRestriction(doze = false, rateLimited = true)

    private fun exerciseRestriction(doze: Boolean, batterySaver: Boolean = false, deleteRemote: Boolean = false, rateLimited: Boolean = false) = runBlocking {
        val host = InstrumentationRegistry.getArguments().getString("recoveryHost")
        assumeNotNull(host)
        BackupCoordinator.awaitStartupRecovery()
        val context = instrumentation.targetContext
        val settings = GlobalContext.get().get<DataStoreManager>()
        val originalAccounts = settings.getAccounts()
        val originalText = settings.preDownloadText.first()
        val originalAttachments = settings.preDownloadAttachments.first()
        val originalPower = shell("settings get global low_power").trim()
        val originalChain = shell("cmd connectivity get-chain3-enabled").trim().endsWith(":enabled")
        val originalAccess = shell("cmd connectivity get-package-networking-enabled ${context.packageName}").trim().endsWith(":allow")
        val blocked = AtomicBoolean(false)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onBlockedStatusChanged(network: Network, value: Boolean) { blocked.set(value) }
        }
        val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS).build()
        val unauthenticated = MemosApiFactory.create(host!!, client)
        val login = unauthenticated.signIn(SignInRequest(passwordCredentials =
            PasswordCredentials("recovery", "recovery-test-only")))
        val api = MemosApiFactory.create(host, client.newBuilder()
            .addInterceptor(AuthInterceptor(login.accessToken)).build())
        val memo = api.createMemo(Memo(content = "Network recovery regression"))
        val account = Account(id = "network-recovery-integration", hostUrl = host,
            accessToken = login.accessToken, isActive = true, user = api.getCurrentSession().user!!.toUserSnapshot())
        var newMemo: Memo? = null
        try {
            settings.completeSetup()
            settings.savePreDownloadText(false)
            settings.savePreDownloadAttachments(false)
            settings.saveAccounts(originalAccounts.map { it.copy(isActive = false) } + account)
            connectivity.registerDefaultNetworkCallback(callback)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var viewModel: MemosViewModel
                scenario.onActivity { viewModel = ViewModelProvider(it)[MemosViewModel::class.java] }
                await("Initial fetch failed") { viewModel.uiState.value.isOnline &&
                    viewModel.uiState.value.userMemoList.list.items.any { it.name == memo.name } }
                if (rateLimited) {
                    newMemo = api.createMemo(Memo(content = "Created during rate-limit recovery test"))
                    ServerRateLimit.shared.record(host.toHttpUrl(), account.accessToken, 4_000)
                    await("Rate limit was labelled offline") {
                        viewModel.uiState.value.connectionState == ConnectionState.RATE_LIMITED
                    }
                    repeat(3) { scenario.onActivity { viewModel.fetchUserMemos(refresh = true) } }
                    await("Pull to refresh stuck during cooldown") { !viewModel.uiState.value.isRefreshing }
                    assertTrue(viewModel.uiState.value.isOnline)
                    assertTrue(viewModel.uiState.value.userMemoList.list.items.any { it.name == memo.name })
                    await("Cooldown recovery required another refresh or restart") {
                        viewModel.uiState.value.connectionState == ConnectionState.ONLINE &&
                            viewModel.uiState.value.userMemoList.list.items.any { it.name == newMemo?.name }
                    }
                } else if (deleteRemote) {
                    api.deleteMemo(memo.name!!)
                    scenario.onActivity { viewModel.fetchUserMemos(refresh = true) }
                    val repository = GlobalContext.get().get<org.example.memosm.data.cache.MemoCacheRepository>()
                    await("Remote deletion remained in the list or offline cache") {
                        viewModel.uiState.value.userMemoList.list.items.none { it.name == memo.name } &&
                            runBlocking { repository.getCachedMemo(account.id, memo.name!!) == null }
                    }
                    assertTrue("Deleted memo survived in offline search",
                        repository.searchCachedMemos(account.id, "Network recovery regression").none { it.name == memo.name })
                } else {
                    val observer = GlobalContext.get().get<ConnectivityObserver>()
                    shell("cmd connectivity set-package-networking-enabled false ${context.packageName}")
                    shell("cmd connectivity set-chain3-enabled true")
                    scenario.moveToState(Lifecycle.State.CREATED)
                    if (batterySaver) {
                        shell("dumpsys battery unplug")
                        shell("cmd power set-mode 1")
                        shell("cmd power sleep")
                    }
                    if (doze) shell("dumpsys deviceidle force-idle")
                    await("Android did not block this app's network") { blocked.get() }
                    assertTrue("A real request should fail while this UID is blocked",
                        runCatching { api.getInstanceProfile() }.isFailure)
                    await("App ignored Android's blocked-network callback") { !observer.isOnline.value }
                    await("App kept displaying an online connection while blocked") { !viewModel.uiState.value.isOnline }
                    if (doze) shell("dumpsys deviceidle unforce")
                    if (batterySaver) {
                        shell("cmd power wakeup")
                        shell("wm dismiss-keyguard")
                    }
                    shell("cmd connectivity set-package-networking-enabled true ${context.packageName}")
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    await("Recovery required restarting the app") {
                        viewModel.uiState.value.isOnline && !viewModel.uiState.value.userMemoList.list.isLoading
                    }
                    scenario.onActivity { viewModel.fetchUserMemos(refresh = true) }
                    await("Manual refresh remained stuck") { !viewModel.uiState.value.isRefreshing &&
                        !viewModel.uiState.value.userMemoList.list.isLoading && viewModel.uiState.value.isOnline }
                }
            }
        } finally {
            connectivity.unregisterNetworkCallback(callback)
            shell("cmd connectivity set-package-networking-enabled $originalAccess ${context.packageName}")
            shell("cmd connectivity set-chain3-enabled $originalChain")
            if (doze) shell("dumpsys deviceidle unforce")
            if (batterySaver) {
                shell("cmd power wakeup")
                shell("cmd power set-mode ${if (originalPower == "1") "1" else "0"}")
                shell("dumpsys battery reset")
            }
            settings.saveAccounts(originalAccounts)
            settings.savePreDownloadText(originalText)
            settings.savePreDownloadAttachments(originalAttachments)
            runCatching { api.deleteMemo(memo.name!!) }
            newMemo?.name?.let { runCatching { api.deleteMemo(it) } }
            GlobalContext.get().get<org.example.memosm.data.cache.MemoCacheRepository>().clearCache(account.id)
        }
    }
}
