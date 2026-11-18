package org.example.memosm.account

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.audit.AuditDatabase
import org.example.memosm.data.audit.SyncAuditLogger
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.cache.MemoCacheDatabase
import org.example.memosm.data.media.AttachmentCacheManager
import org.example.memosm.data.media.AttachmentUploadDao
import org.example.memosm.data.media.AttachmentUploadQueue
import org.example.memosm.data.media.CachedAttachment
import org.example.memosm.model.Account
import org.example.memosm.model.Attachment
import org.example.memosm.ui.component.composer.collectComposerAttachments
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AttachmentOwnershipTest {
    @get:Rule val temp = TemporaryFolder()

    private fun context() =
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir() = File(temp.root, "no-backup").apply { mkdirs() }

            override fun getFilesDir() = File(temp.root, "files").apply { mkdirs() }
        }

    @Test fun `staging failure leaves no unowned bytes and startup collects prior-process orphans`() =
        runBlocking {
            val context = context()
            val database = Room.inMemoryDatabaseBuilder(context, MemoCacheDatabase::class.java).allowMainThreadQueries().build()
            val audit = Room.inMemoryDatabaseBuilder(context, AuditDatabase::class.java).allowMainThreadQueries().build()
            try {
                val logger = SyncAuditLogger(audit.syncAuditDao())
                val uploads = AttachmentUploadQueue(context, database.attachmentUploadDao(), logger, accountExists = { false })
                val uri = Uri.parse("content://tests/broken")
                shadowOf(context.contentResolver).registerInputStream(
                    uri,
                    object : InputStream() {
                        override fun read(): Int = throw IOException("source failed")
                    },
                )
                try {
                    uploads.enqueue("A", uri, "broken", "text/plain", 0)
                    fail("Copy should fail")
                } catch (_: IOException) {
                }
                val source = temp.newFile().apply { writeText("bytes") }
                try {
                    uploads.enqueue("A", Uri.fromFile(source), "file", "text/plain", 0)
                    fail("Removed account should fail")
                } catch (
                    _: IllegalStateException,
                ) {
                }
                assertTrue(File(context.noBackupFilesDir, "attachment_uploads").listFiles().orEmpty().isEmpty())
                assertTrue(database.attachmentUploadDao().getAll().isEmpty())

                val failingDao =
                    stub<AttachmentUploadDao> { method, _ ->
                        check(method == "upsert")
                        throw IOException("disk full")
                    }
                val failing = AttachmentUploadQueue(context, failingDao, logger)
                try {
                    failing.enqueue("A", Uri.fromFile(source), "file", "text/plain", 0)
                    fail("Persistence should fail")
                } catch (
                    _: IOException,
                ) {
                }
                assertTrue(File(context.noBackupFilesDir, "attachment_uploads").listFiles().orEmpty().isEmpty())

                val old =
                    uploads.stagedFile(UUID.randomUUID().toString()).apply {
                        writeText("orphan")
                        setLastModified(1)
                    }
                val active = uploads.stagedFile(UUID.randomUUID().toString()).apply { writeText("active") }
                uploads.cleanupOrphanedFiles(createdBefore = 2)
                assertFalse(old.exists())
                assertTrue(active.exists())
            } finally {
                database.close()
                audit.close()
            }
        }

    @Test fun `restored and inline composer attachments survive selection and share durable preparation`() =
        runBlocking {
            val context = context()
            val database = Room.inMemoryDatabaseBuilder(context, MemoCacheDatabase::class.java).allowMainThreadQueries().build()
            val audit = Room.inMemoryDatabaseBuilder(context, AuditDatabase::class.java).allowMainThreadQueries().build()
            try {
                val file =
                    File(context.filesDir, "restored_backup_files/photo").apply {
                        parentFile!!.mkdirs()
                        writeText("restored bytes")
                    }
                val restored = Attachment(filename = "photo", type = "image/jpeg", localPath = file.path)
                val inline = Attachment(filename = "old.txt", type = "text/plain", content = "aW5saW5lIGJ5dGVz")
                val selected =
                    collectComposerAttachments(listOf(Uri.EMPTY to restored, Uri.EMPTY to inline), stage = { error("Already has source") })
                assertEquals(listOf(restored, inline), selected)
                val queue = AttachmentUploadQueue(context, database.attachmentUploadDao(), SyncAuditLogger(audit.syncAuditDao()))
                repeat(2) {
                    try {
                        queue.withPreparedAttachments("A", listOf(inline, restored.copy(localPath = file.path + ".missing"))) { _, _ ->
                            fail("A missing source must prevent the entire batch commit")
                        }
                        fail("Preparation should fail")
                    } catch (_: IllegalArgumentException) {
                    }
                    assertTrue(database.attachmentUploadDao().getAll().isEmpty())
                    assertTrue(File(context.noBackupFilesDir, "attachment_uploads").listFiles().orEmpty().isEmpty())
                }
                try {
                    queue.withPreparedAttachments("A", listOf(inline)) { _, uploads ->
                        database.memoDao().commitMutation(
                            org.example.memosm.data.sync.PendingOp.new(
                                "A",
                                org.example.memosm.data.sync.PendingOpType.COMMENT_CREATE,
                                "offline-child",
                            ),
                            null,
                            org.example.memosm.data.cache.CacheListType.COMMENT,
                            null,
                            uploads,
                        )
                    }
                    fail("Invalid memo commit must roll back upload ownership")
                } catch (_: IllegalArgumentException) {
                }
                assertTrue(database.attachmentUploadDao().getAll().isEmpty())
                assertTrue(File(context.noBackupFilesDir, "attachment_uploads").listFiles().orEmpty().isEmpty())
                val prepared =
                    queue.withPreparedAttachments("A", selected) { prepared, uploads ->
                        database.memoDao().insertUploads(uploads)
                        prepared
                    }
                assertEquals(listOf("restored bytes", "inline bytes"), prepared.map { File(it.localPath!!).readText() })
                assertTrue(prepared.all { it.clientId != null && it.content == null })
                assertEquals(2, database.attachmentUploadDao().getForAccount("A").size)
                try {
                    collectComposerAttachments(
                        listOf(Uri.parse("content://tests/fail") to null),
                        stage = { throw IOException("unreadable") },
                    )
                    fail("Selected attachment must not be silently omitted")
                } catch (_: IOException) {
                }
            } finally {
                database.close()
                audit.close()
            }
        }

    @Test fun `bulk publishing prepares inline attachments before deleting the source draft`() =
        runBlocking {
            val context = context()
            val database = Room.inMemoryDatabaseBuilder(context, MemoCacheDatabase::class.java).allowMainThreadQueries().build()
            val audit = Room.inMemoryDatabaseBuilder(context, AuditDatabase::class.java).allowMainThreadQueries().build()
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            val sessions =
                org.example.memosm.viewmodel
                    .AccountSession(scope)
            try {
                sessions.activate(
                    Account(id = "A"),
                    stub<org.example.memosm.api.MemosApi> {
                        name,
                        _,
                        ->
                        error("Unexpected network: $name")
                    },
                    OkHttpClient(),
                )
                val draft =
                    org.example.memosm.model.Draft(
                        id = "inline-draft",
                        content = "keep attachment",
                        visibility = org.example.memosm.model.Visibility.PRIVATE,
                        attachments = listOf(Attachment(filename = "old.txt", type = "text/plain", content = "aW5saW5lIGJ5dGVz")),
                    )
                val drafts =
                    org.example.memosm.data
                        .DraftManager(context)
                drafts.saveDraft("A", draft, strict = true)
                val state =
                    kotlinx.coroutines.flow.MutableStateFlow(
                        org.example.memosm.viewmodel
                            .MemosUiState(),
                    )
                state.value = state.value.copy(draft = state.value.draft.copy(drafts = listOf(draft)))
                val uploads = AttachmentUploadQueue(context, database.attachmentUploadDao(), SyncAuditLogger(audit.syncAuditDao()))
                val attachments =
                    org.example.memosm.viewmodel.manager
                        .AttachmentManager(sessions, uploadQueueProvider = { uploads })
                lateinit var actions: org.example.memosm.viewmodel.delegates.MemoActionDelegate
                val delegate =
                    org.example.memosm.viewmodel.delegates
                        .DraftDelegateImpl(scope, state, drafts, sessions, { actions }, {})
                actions =
                    org.example.memosm.viewmodel.delegates.MemoActionDelegateImpl(
                        scope,
                        state,
                        delegate,
                        { attachments },
                        org.example.memosm.data.cache
                            .MemoCacheRepository(database.memoDao()),
                        sessions,
                        drafts,
                        object : org.example.memosm.data.sync.SyncWorkScheduler {
                            override fun schedule(accountId: String) {}

                            override fun cancel(accountId: String) {}
                        },
                    )
                val result = CompletableDeferred<Int>()
                delegate.publishAllDrafts { result.complete(it) }
                assertEquals(1, kotlinx.coroutines.withTimeout(10_000) { result.await() })
                assertTrue(drafts.getDrafts("A", strict = true).isEmpty())
                val op = database.pendingOpDao().getOps("A").single()
                val payload =
                    org.example.memosm.api.GsonProvider.gson
                        .fromJson(op.payloadJson, org.example.memosm.model.Memo::class.java)
                val prepared = payload.attachments!!.single()
                assertNotNull(prepared.clientId)
                assertNull(prepared.content)
                assertEquals("inline bytes", File(prepared.localPath!!).readText())
            } finally {
                sessions.clear()
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                database.close()
                audit.close()
            }
        }

    @Test fun `downloads authenticate only server origin and fence restore removal and failed persistence`() =
        runBlocking {
            for (scenario in listOf("same", "external", "restore", "remove", "database")) {
                val context = context()
                val database = Room.inMemoryDatabaseBuilder(context, MemoCacheDatabase::class.java).allowMainThreadQueries().build()
                try {
                    val settings = DataStoreManager(MemoryPreferences())
                    settings.saveAccounts(listOf(Account(id = "A", isActive = true)))
                    val started = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    var authorization: String? = null
                    val http =
                        OkHttpClient
                            .Builder()
                            .addInterceptor { chain ->
                                authorization = chain.request().header("Authorization")
                                started.complete(Unit)
                                runBlocking { release.await() }
                                Response
                                    .Builder()
                                    .request(chain.request())
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(200)
                                    .message("OK")
                                    .body("downloaded bytes".toResponseBody())
                                    .build()
                            }.build()
                    val dao = database.cachedAttachmentDao()
                    val guarded =
                        if (scenario == "database") {
                            object : org.example.memosm.data.media.CachedAttachmentDao by dao {
                                override suspend fun upsert(attachment: CachedAttachment): Unit = throw IOException("write failed")
                            }
                        } else {
                            dao
                        }
                    val media = AttachmentCacheManager(context, guarded, http, settings) { true }
                    val attachment =
                        Attachment(
                            name = "attachments/$scenario",
                            filename = "photo",
                            type = "image/jpeg",
                            externalLink = if (scenario == "external") "https://storage.example/photo" else null,
                        )
                    val download =
                        async { media.download("https://server.example/subpath", "secret", "A", "memos/1", attachment, evict = false) }
                    started.await()
                    when (scenario) {
                        "restore" -> BackupCoordinator.restore { }
                        "remove" -> settings.saveAccounts(emptyList())
                    }
                    release.complete(Unit)
                    val success = download.await()
                    assertEquals(if (scenario == "external") null else "Bearer secret", authorization)
                    assertEquals(scenario in listOf("same", "external"), success)
                    assertEquals(if (success) 1 else 0, dao.getAll().size)
                    if (!success) assertTrue(File(context.filesDir, "offline_media/A").walkTopDown().none { it.isFile })
                    media.clearAccount("A")
                    http.dispatcher.executorService.shutdown()
                    http.connectionPool.evictAll()
                } finally {
                    database.close()
                }
            }
        }

    @Test fun `eviction retains ownership and accounting when file deletion fails`() =
        runBlocking {
            val context = context()
            val database = Room.inMemoryDatabaseBuilder(context, MemoCacheDatabase::class.java).allowMainThreadQueries().build()
            try {
                val settings = DataStoreManager(MemoryPreferences())
                settings.saveAccounts(listOf(Account(id = "A", isActive = true)))
                settings.saveAttachmentCacheMaxMb(1)
                val undeletable = temp.newFolder("not-empty").apply { resolve("child").writeText("bytes") }
                val dao = database.cachedAttachmentDao()
                dao.upsert(CachedAttachment("A", "attachments/old", "memos/1", "url", undeletable.path, 2 * 1024 * 1024, 1, 1))
                val http =
                    OkHttpClient
                        .Builder()
                        .addInterceptor { chain ->
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body("new bytes".toResponseBody())
                                .build()
                        }.build()
                val media = AttachmentCacheManager(context, dao, http, settings) { true }
                media.download(
                    "https://server.example",
                    "",
                    "A",
                    "memos/1",
                    Attachment(name = "attachments/new", filename = "new", type = "text/plain"),
                )
                assertNotNull(dao.getByAttachment("A", "attachments/old"))
                assertEquals(2 * 1024 * 1024L, dao.getTotalSizeAll())
                assertTrue(undeletable.resolve("child").exists())
                http.dispatcher.executorService.shutdown()
            } finally {
                database.close()
            }
        }
}
