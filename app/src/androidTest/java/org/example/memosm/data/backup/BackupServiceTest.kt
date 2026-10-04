package org.example.memosm.data.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.example.memosm.api.GsonProvider
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.DraftManager
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.CachedMemo
import org.example.memosm.data.cache.MemoCacheDatabase
import org.example.memosm.data.media.CachedAttachment
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.PendingOpType
import org.example.memosm.model.Account
import org.example.memosm.model.Attachment
import org.example.memosm.model.Draft
import org.example.memosm.model.Memo
import org.example.memosm.model.UserSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupServiceTest {
    private val account = Account(id = "A", hostUrl = "https://example.invalid", accessToken = "token-A", isActive = true, user = UserSnapshot(name = "users/1"))
    private fun memo(name: String, text: String) = Memo(name = name, content = text)

    private class Fixture : Closeable {
        private val base: Context = ApplicationProvider.getApplicationContext()
        val root = File(base.cacheDir, "backup-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var failPreferences = false
        private val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "settings.preferences_pb") })
        private val failingStore = object : DataStore<Preferences> {
            override val data = store.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (failPreferences) error("Injected preferences failure")
                return store.updateData(transform)
            }
        }
        val settings = DataStoreManager(failingStore)
        val database = Room.inMemoryDatabaseBuilder(context, MemoCacheDatabase::class.java).build()
        val drafts = DraftManager(context)
        val service = BackupService(context, settings, database, drafts)
        override fun close() { scope.cancel(); database.close(); root.deleteRecursively(); BackupCoordinator.recoveryError.value = null }
    }

    private suspend fun seed(source: Fixture) {
        source.settings.saveAccounts(listOf(account))
        source.settings.savePageSize(42)
        source.settings.saveTextSyncCursor("A", 123L)
        source.database.memoDao().insertMemo(CachedMemo.fromMemo(memo("memos/1", "server cache"), "A", CacheListType.USER, 0))
        source.database.memoDao().insertMemo(CachedMemo.fromMemo(memo("memos/2", "unsent optimistic edit"), "A", CacheListType.USER, 1))
        source.database.pendingOpDao().insert(PendingOp.new("A", PendingOpType.UPDATE, "memos/2", payloadJson = GsonProvider.gson.toJson(memo("memos/2", "queued edit"))))
        val image = File(source.context.filesDir, "image").apply { writeText("draft image bytes") }
        source.drafts.replaceDrafts("A", listOf(Draft(id = "draft", content = "unpublished draft", attachments = listOf(Attachment(filename = "image.png", type = "image/png", localPath = image.absolutePath)))))
        source.database.cachedAttachmentDao().upsert(CachedAttachment("A", "attachments/1", "memos/1", "https://example.invalid/file", image.absolutePath, image.length()))
    }

    @Test fun fullRoundTripNeverCreatesServerWrites() = runBlocking {
        Fixture().use { source -> Fixture().use { target ->
            seed(source)
            val archive = source.service.export(BackupSelection(setOf("A"))).getOrThrow()
            target.service.inspect(archive).getOrThrow().use { backup ->
                assertFalse(BackupCategory.QUEUED_EDITS in backup.manifest.categories)
                target.service.restore(backup, RestoreSelection(setOf("A"), backup.manifest.categories)).getOrThrow()
            }
            assertEquals("token-A", target.settings.getAccounts().single().accessToken)
            assertEquals(42, target.settings.pageSize.first())
            assertEquals(0L, target.settings.textSyncCursor("A").first())
            assertEquals(listOf("server cache"), target.database.memoDao().getMemos("A", "USER").map { it.content })
            assertTrue(target.database.pendingOpDao().getOps("A").isEmpty())
            assertTrue(target.database.attachmentUploadDao().getForAccount("A").isEmpty())
            val restored = target.drafts.getDrafts("A").single()
            assertEquals("unpublished draft", restored.content)
            assertNull(restored.attachments.single().clientId)
            assertEquals("draft image bytes", File(restored.attachments.single().localPath!!).readText())
            File(target.database.cachedAttachmentDao().getAllForAccount("A").single().localPath).delete()
            assertTrue(File(restored.attachments.single().localPath!!).exists())
            target.context.cacheDir.deleteRecursively()
            assertEquals("draft", target.drafts.getDrafts("A").single().id)
            // A successful server refresh replaces the restored cache, including server-side deletions.
            target.database.memoDao().cacheRemoteMemos("A", "USER", listOf(CachedMemo.fromMemo(memo("memos/3", "new remote version"), "A", CacheListType.USER, 0)), true)
            assertEquals(listOf("memos/3"), target.database.memoDao().getMemos("A", "USER").map { it.name })
        } }
    }

    @Test fun selectiveRestorePreservesUncheckedDataAndExistingPendingWork() = runBlocking {
        Fixture().use { source -> Fixture().use { target ->
            seed(source)
            target.settings.saveAccounts(listOf(account.copy(accessToken = "keep-token"), account.copy(id = "B", isActive = false, accessToken = "token-B")))
            target.settings.savePageSize(12)
            target.drafts.replaceDrafts("A", listOf(Draft(id = "keep", content = "keep draft")))
            target.database.memoDao().insertMemo(CachedMemo.fromMemo(memo("memos/1", "existing unsent work"), "A", CacheListType.USER, 0))
            val op = PendingOp.new("A", PendingOpType.UPDATE, "memos/1", payloadJson = GsonProvider.gson.toJson(memo("memos/1", "existing unsent work")))
            target.database.pendingOpDao().insert(op)
            target.database.memoDao().insertMemo(CachedMemo.fromMemo(memo("memos/1", "other account"), "B", CacheListType.USER, 0))
            val archive = source.service.export(BackupSelection(setOf("A"))).getOrThrow()
            target.service.inspect(archive).getOrThrow().use { backup ->
                target.service.restore(backup, RestoreSelection(setOf("A"), setOf(BackupCategory.CACHE))).getOrThrow()
            }
            assertEquals("keep-token", target.settings.getAccounts().first { it.id == "A" }.accessToken)
            assertEquals(12, target.settings.pageSize.first())
            assertEquals("keep draft", target.drafts.getDrafts("A").single().content)
            assertEquals("existing unsent work", target.database.memoDao().getMemos("A", "USER").single().content)
            assertEquals(op, target.database.pendingOpDao().getOps("A").single())
            assertEquals("other account", target.database.memoDao().getMemos("B", "USER").single().content)
        } }
    }

    @Test fun optionalQueuedEditsAreRestoredOnlyIntoReviewStorage() = runBlocking {
        Fixture().use { source -> Fixture().use { target ->
            seed(source)
            val categories = setOf(BackupCategory.ACCOUNTS, BackupCategory.QUEUED_EDITS)
            val archive = source.service.export(BackupSelection(setOf("A"), categories)).getOrThrow()
            target.service.inspect(archive).getOrThrow().use { backup ->
                target.service.restore(backup, RestoreSelection(setOf("A"), categories)).getOrThrow()
            }
            assertEquals("queued edit", target.settings.restoredEdits("A").first().single().asJsonObject.getAsJsonObject("payload").get("content").asString)
            assertTrue(target.database.pendingOpDao().getOps("A").isEmpty())
            assertTrue(target.database.attachmentUploadDao().getForAccount("A").isEmpty())
            assertTrue(target.database.memoDao().getMemos("A", "USER").isEmpty())
        } }
    }

    @Test fun interruptedCrossStoreRestoreCanBeRetriedWithoutLosingDrafts() = runBlocking {
        Fixture().use { source -> Fixture().use { target ->
            seed(source)
            val archive = source.service.export(BackupSelection(setOf("A"))).getOrThrow()
            target.service.inspect(archive).getOrThrow().use { backup ->
                target.failPreferences = true
                assertTrue(target.service.restore(backup, RestoreSelection(setOf("A"), backup.manifest.categories)).isFailure)
                assertNotNull(BackupCoordinator.recoveryError.value)
                assertTrue(File(target.context.noBackupFilesDir, "backup_restore/transaction.mmbackup").exists())
                target.failPreferences = false
                target.service.recoverInterruptedRestore().getOrThrow()
            }
            assertEquals("token-A", target.settings.getAccounts().single().accessToken)
            assertEquals("unpublished draft", target.drafts.getDrafts("A").single().content)
            assertFalse(File(target.context.noBackupFilesDir, "backup_restore/transaction.mmbackup").exists())
            assertNull(BackupCoordinator.recoveryError.value)
            target.service.recoverInterruptedRestore().getOrThrow()
            assertEquals(1, target.drafts.getDrafts("A").size)
        } }
    }

    @Test fun cloudSnapshotContainsOnlyAccountsAndDraftsMigrateOutOfCache() = runBlocking {
        Fixture().use { source ->
            seed(source)
            val legacyDirectory = File(source.context.cacheDir, "drafts").apply { mkdirs() }
            File(source.context.filesDir, "drafts/drafts_A.json").copyTo(File(legacyDirectory, "drafts_A.json"))
            File(source.context.filesDir, "drafts/drafts_A.json").delete()
            assertEquals("draft", source.drafts.getDrafts("A").single().id)
            assertTrue(File(source.context.filesDir, "drafts/drafts_A.json").exists())
            assertFalse(File(legacyDirectory, "drafts_A.json").exists())
            val archive = source.service.export(BackupSelection(setOf("A"), setOf(BackupCategory.ACCOUNTS))).getOrThrow()
            source.service.inspect(archive).getOrThrow().use { backup ->
                assertEquals(setOf(BackupCategory.ACCOUNTS), backup.manifest.categories)
                val data = backup.manifest.accounts.single()
                assertEquals("token-A", data.account!!.accessToken)
                assertTrue(data.memos.isEmpty() && data.drafts.isEmpty() && data.media.isEmpty() && data.queuedEdits.isEmpty())
                assertNull(backup.manifest.settings)
            }
        }
    }
}
