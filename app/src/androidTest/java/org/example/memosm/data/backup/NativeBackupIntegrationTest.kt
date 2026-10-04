package org.example.memosm.data.backup

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
import org.example.memosm.model.Draft
import org.example.memosm.model.Memo
import org.example.memosm.model.UserSnapshot
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Run only on a disposable installation with -e nativePhase seed|cloud|device. See docs/backup.md. */
class NativeBackupIntegrationTest {
    @Test fun nativeTransportScenario() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("nativePhase")
        assumeTrue(phase != null)
        BackupCoordinator.awaitStartupRecovery()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val settings = koin.get<DataStoreManager>()
        val database = koin.get<MemoCacheDatabase>()
        val drafts = koin.get<DraftManager>()
        val id = "native-backup-test"
        if (phase == "seed") {
            require(settings.getAccounts().isEmpty()) { "Native backup test requires a fresh disposable installation" }
            settings.saveAccounts(listOf(Account(id = id, hostUrl = "https://example.invalid", accessToken = "native-test-token", isActive = true, user = UserSnapshot(name = "users/1"))))
            settings.savePageSize(42)
            val memo = Memo(name = "memos/1", content = "native server cache")
            database.memoDao().insertMemo(CachedMemo.fromMemo(memo, id, CacheListType.USER, 0))
            database.memoDao().insertMemo(CachedMemo.fromMemo(memo.copy(name = "memos/2", content = "optimistic edit"), id, CacheListType.USER, 1))
            database.pendingOpDao().insert(PendingOp.new(id, PendingOpType.UPDATE, "memos/2", payloadJson = GsonProvider.gson.toJson(memo.copy(name = "memos/2", content = "do not replay"))))
            drafts.replaceDrafts(id, listOf(Draft(id = "native-draft", content = "native unpublished draft")))
            val image = File(context.filesDir, "native-image").apply { writeText("native media bytes") }
            database.cachedAttachmentDao().upsert(CachedAttachment(id, "attachments/1", "memos/1", "https://example.invalid/file", image.absolutePath, image.length()))
        } else {
            assertNull(BackupCoordinator.recoveryError.value)
            assertEquals("native-test-token", settings.getAccounts().single().accessToken)
            assertTrue(database.pendingOpDao().getOps(id).isEmpty())
            assertTrue(database.attachmentUploadDao().getForAccount(id).isEmpty())
            if (phase == "cloud") {
                assertTrue(database.memoDao().getMemos(id, "USER").isEmpty())
                assertTrue(drafts.getDrafts(id).isEmpty())
                assertTrue(database.cachedAttachmentDao().getAllForAccount(id).isEmpty())
                assertEquals(DataStoreManager.DEFAULT_PAGE_SIZE, settings.pageSize.first())
            } else {
                require(phase == "device")
                assertEquals("native server cache", database.memoDao().getMemos(id, "USER").single().content)
                assertEquals("native unpublished draft", drafts.getDrafts(id).single().content)
                assertEquals("native media bytes", File(database.cachedAttachmentDao().getAllForAccount(id).single().localPath).readText())
                assertEquals(42, settings.pageSize.first())
            }
            assertFalse(File(context.filesDir, "native_backup/restore_pending").exists())
        }
    }
}
