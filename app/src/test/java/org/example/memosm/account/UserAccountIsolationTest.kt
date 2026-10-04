package org.example.memosm.account

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import org.example.memosm.api.MemosApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.offline.SessionCacheStore
import org.example.memosm.data.offline.SessionSnapshotData
import org.example.memosm.data.store.DataStoreSnapshotStore
import org.example.memosm.model.*
import org.example.memosm.viewmodel.*
import org.example.memosm.viewmodel.delegates.UserDelegateImpl
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserAccountIsolationTest {
    @Test fun `returning from settings reloads another activitys cached preferences offline`() = runTest {
        val manager = DataStoreManager(MemoryPreferences())
        val account = Account(id = "settings-account", isActive = true)
        manager.saveAccounts(listOf(account))
        val sessions = AccountSession(backgroundScope)
        sessions.activate(account, stub<MemosApi> { name, _ -> error("Unexpected network call: $name") }, OkHttpClient())
        val oldSettings = UserGeneralSetting(locale = "en", memoVisibility = Visibility.PRIVATE)
        val newSettings = UserGeneralSetting(locale = "ja", memoVisibility = Visibility.PUBLIC)
        val state = MutableStateFlow(MemosUiState(session = SessionState(userSettings = oldSettings)))
        val cache = SessionCacheStore(DataStoreSnapshotStore(manager, "session", SessionSnapshotData::class.java))
        cache.save(account.id, SessionSnapshotData(userSettings = newSettings))
        val delegate = UserDelegateImpl(backgroundScope, state, manager, cache, sessions, {})
        delegate.restoreCachedSessionNow()
        assertEquals(oldSettings, state.value.session.userSettings)
        delegate.restoreCachedSessionNow(replace = true)
        assertEquals(newSettings, state.value.session.userSettings)
    }

    @Test fun `late profile response never updates another account or its snapshot`() = runTest {
        val release = CompletableDeferred<Unit>()
        val api = stub<MemosApi> { name, _ ->
            check(name == "getCurrentSession")
            withContext(NonCancellable) { release.await() }
            CurrentSessionResponse(UserSnapshot(name = "users/A", displayName = "Alice"))
        }
        val manager = DataStoreManager(MemoryPreferences())
        val a = Account(id = "A", isActive = true)
        val b = Account(id = "B")
        manager.saveAccounts(listOf(a, b))
        val sessions = AccountSession(backgroundScope)
        sessions.activate(a, api, OkHttpClient())
        val state = MutableStateFlow(MemosUiState(accounts = listOf(a, b)))
        val cache = SessionCacheStore(DataStoreSnapshotStore(manager, "session", SessionSnapshotData::class.java))
        val delegate = UserDelegateImpl(backgroundScope, state, manager, cache, sessions, {})
        var callback = false
        delegate.fetchCurrentUser { callback = true }
        runCurrent()
        sessions.activate(b, api, OkHttpClient())
        state.value = state.value.forAccount(b)
        release.complete(Unit)
        runCurrent()
        assertNull(state.value.session.currUser)
        assertNull(manager.getAccounts().first { it.id == "B" }.user)
        assertNull(cache.get("B"))
        assertFalse(callback)
    }

    @Test fun `old stats cannot overwrite a newer activation of the same account`() = runTest {
        val release = CompletableDeferred<Unit>()
        val api = stub<MemosApi> { name, _ ->
            check(name == "getUserStats")
            withContext(NonCancellable) { release.await() }
            UserStats(totalMemoCount = 999)
        }
        val manager = DataStoreManager(MemoryPreferences())
        val cache = SessionCacheStore(DataStoreSnapshotStore(manager, "session", SessionSnapshotData::class.java))
        val sessions = AccountSession(backgroundScope)
        val a = Account(id = "A")
        sessions.activate(a, api, OkHttpClient())
        val state = MutableStateFlow(MemosUiState())
        val delegate = UserDelegateImpl(backgroundScope, state, manager, cache, sessions, {})
        backgroundScope.launch { delegate.fetchUserStats("users/A") }
        runCurrent()
        sessions.activate(Account(id = "B"), api, OkHttpClient())
        sessions.activate(a, api, OkHttpClient())
        state.value = state.value.copy(session = SessionState(userStats = UserStats(totalMemoCount = 2)))
        release.complete(Unit)
        runCurrent()
        assertEquals(2, state.value.session.userStats?.totalMemoCount)
        assertNull(cache.get("A"))
    }

    @Test fun `inactive account edit leaves active session alone and active deletion rebinds`() = runTest {
        val api = stub<MemosApi> { name, _ -> error("Unexpected API: $name") }
        val manager = DataStoreManager(MemoryPreferences())
        val a = Account(id = "A", isActive = true)
        val b = Account(id = "B")
        manager.saveAccounts(listOf(a, b))
        val sessions = AccountSession(backgroundScope)
        val original = sessions.activate(a, api, OkHttpClient())
        val state = MutableStateFlow(MemosUiState(accounts = listOf(a, b)))
        val cache = SessionCacheStore(DataStoreSnapshotStore(manager, "session", SessionSnapshotData::class.java))
        val switches = mutableListOf<String?>()
        val delegate = UserDelegateImpl(backgroundScope, state, manager, cache, sessions, { account ->
            switches += account?.id
            state.value = state.value.forAccount(account)
            if (account == null) sessions.clear() else sessions.activate(account, api, OkHttpClient())
        })
        delegate.updateAccountCredentials(b, b.hostUrl, "new-token")
        runCurrent()
        assertSame(original, sessions.current)
        assertTrue(switches.isEmpty())
        delegate.removeAccount(a)
        runCurrent()
        assertEquals(listOf("B"), switches)
        assertEquals("new-token", sessions.current?.account?.accessToken)
        delegate.removeAccount(b)
        runCurrent()
        assertNull(sessions.current)
        assertEquals(listOf("B", null), switches)
        assertEquals(SessionState(), state.value.session)
    }
}
