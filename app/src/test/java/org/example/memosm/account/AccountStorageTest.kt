package org.example.memosm.account

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.offline.*
import org.example.memosm.di.appModule
import org.example.memosm.model.Account
import org.example.memosm.model.UserSnapshot
import org.example.memosm.ui.component.item.media.accountMediaCacheKey
import org.junit.Assert.*
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class AccountStorageTest {
    @Test fun `legacy global cursor is ignored and new cursors are independent`() = runTest {
        val manager = DataStoreManager(MemoryPreferences(preferencesOf(longPreferencesKey("text_sync_cursor") to 123L)))
        assertEquals(0L, manager.textSyncCursor("A").first())
        manager.saveTextSyncCursor("A", 456L)
        manager.saveTextSyncCursor("B", 789L)
        manager.saveLastPreDownloadAt("A", 100L)
        manager.removeDownloadState("A")
        assertEquals(0L, manager.textSyncCursor("A").first())
        assertEquals(0L, manager.lastPreDownloadAt("A").first())
        assertEquals(789L, manager.textSyncCursor("B").first())
    }

    @Test fun `concurrent user refresh cannot undo account activation`() = runTest {
        val manager = DataStoreManager(MemoryPreferences())
        manager.saveAccounts(listOf(Account(id = "A", isActive = true), Account(id = "B")))
        listOf(
            async { manager.updateAccountUser("A", UserSnapshot(name = "users/1")) },
            async { manager.setActiveAccount("B") },
            async { manager.updateAccountLastUsed("B", 42L) }
        ).awaitAll()
        val accounts = manager.getAccounts()
        assertEquals("B", accounts.single { it.isActive }.id)
        assertEquals("users/1", accounts.single { it.id == "A" }.user?.name)
        assertEquals(42L, accounts.single { it.id == "B" }.lastUsed)
    }

    @Test fun `host edit creates a fresh namespace and preserves original credentials and history`() = runTest {
        val manager = DataStoreManager(MemoryPreferences())
        val original = Account(id = "A", hostUrl = "https://old.example", accessToken = "old-token",
            isActive = true, user = UserSnapshot(name = "users/1"))
        manager.saveAccounts(listOf(original, Account(id = "B")))
        manager.saveTextSyncCursor("A", 42L)
        manager.updateAccount("A", "https://new.example", "new-token")
        val accounts = manager.getAccounts()
        val old = accounts.single { it.id == "A" }
        val active = accounts.single { it.isActive }
        assertEquals("https://old.example", old.hostUrl)
        assertEquals("old-token", old.accessToken)
        assertFalse(old.isActive)
        assertNotEquals("A", active.id)
        assertEquals("https://new.example", active.hostUrl)
        assertNull(active.user)
        assertEquals(42L, manager.textSyncCursor("A").first())
        assertEquals(0L, manager.textSyncCursor(active.id).first())
    }

    @Test fun `equivalent server URL and token renewal retain the existing namespace`() = runTest {
        val manager = DataStoreManager(MemoryPreferences())
        manager.saveAccounts(listOf(Account(id = "A", hostUrl = "https://same.example/", isActive = true)))
        manager.updateAccount("A", "https://same.example/api/v1/", "new-token")
        assertEquals("A", manager.getAccounts().single().id)
        assertEquals("new-token", manager.getAccounts().single().accessToken)
    }

    @Test fun `production DI keeps session and notification domains and accounts separate`() = runTest {
        val manager = DataStoreManager(MemoryPreferences())
        val app = koinApplication {
            modules(appModule, module { single { manager } })
        }
        try {
            val sessions = app.koin.get<SessionCacheStore>()
            val notifications = app.koin.get<NotificationCacheStore>()
            sessions.save("A", SessionSnapshotData(currUser = UserSnapshot(name = "users/A")))
            sessions.save("B", SessionSnapshotData(currUser = UserSnapshot(name = "users/B")))
            notifications.save("A", NotificationsSnapshotData(savedAt = 9L))
            assertEquals("users/A", sessions.get("A")?.currUser?.name)
            assertEquals("users/B", sessions.get("B")?.currUser?.name)
            assertEquals(9L, notifications.get("A")?.savedAt)
            sessions.clear("A")
            assertNull(sessions.get("A"))
            assertEquals(9L, notifications.get("A")?.savedAt)
        } finally { app.close() }
    }

    @Test fun `media cache keys isolate same URL for different accounts`() {
        val resource = "https://same-server/file/attachments/1/image.jpg"
        assertNotEquals(accountMediaCacheKey("A", resource), accountMediaCacheKey("B", resource))
        assertNotEquals(accountMediaCacheKey(null, resource), accountMediaCacheKey("A", resource))
    }
}
