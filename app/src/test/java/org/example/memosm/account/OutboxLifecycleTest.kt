package org.example.memosm.account

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.example.memosm.api.GsonProvider
import org.example.memosm.api.MemosApi
import org.example.memosm.data.audit.AuditDatabase
import org.example.memosm.data.audit.SyncAuditLogger
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.MemoCacheDatabase
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.cache.seedMemo
import org.example.memosm.data.media.AttachmentUpload
import org.example.memosm.data.media.AttachmentUploadQueue
import org.example.memosm.data.sync.*
import org.example.memosm.model.Attachment
import org.example.memosm.model.Memo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class OutboxLifecycleTest {
    @get:Rule val files = TemporaryFolder()

    private class Fixture : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, MemoCacheDatabase::class.java).allowMainThreadQueries().build()
        private val auditDatabase = Room.inMemoryDatabaseBuilder(context, AuditDatabase::class.java).allowMainThreadQueries().build()
        val cache = MemoCacheRepository(database.memoDao())
        val repository = SyncRepository(database.pendingOpDao())
        val audit = SyncAuditLogger(auditDatabase.syncAuditDao())
        val uploads = AttachmentUploadQueue(context, database.attachmentUploadDao(), audit)

        suspend fun commit(
            memo: Memo,
            mask: String,
            id: String,
        ): PendingOp {
            val op =
                PendingOp.new(
                    "A",
                    PendingOpType.UPDATE,
                    memo.name,
                    payloadJson = GsonProvider.gson.toJson(memo),
                    updateMask = mask,
                    baseUpdateTime = memo.updateTime?.toString(),
                    id = id,
                )
            cache.commitMutation(op, memo)
            return repository.getOp(id)!!
        }

        suspend fun drain(
            api: MemosApi,
            current: suspend () -> Boolean = { true },
        ) = drainOutbox("A", api, repository, cache, uploads, null, audit, current)

        override fun close() {
            database.close()
            auditDatabase.close()
        }
    }

    @Test fun `acknowledging an in-flight edit preserves a newer queued edit`() =
        runBlocking {
            Fixture().use { fixture ->
                val t0 = Instant.parse("2026-11-18T10:00:00Z")
                val t1 = Instant.parse("2026-11-18T10:01:00Z")
                var server = Memo(name = "memos/1", content = "original", pinned = false, updateTime = t0)
                fixture.cache.seedMemo("A", server, CacheListType.USER)
                fixture.commit(server.copy(content = "edited"), "content", "first")
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var updates = 0
                val api =
                    stub<MemosApi> { method, args ->
                        when (method) {
                            "getMemo" -> {
                                server
                            }

                            "updateMemo" -> {
                                updates++
                                if (updates == 1) {
                                    started.complete(Unit)
                                    release.await()
                                }
                                server = (args[1] as Memo).copy(updateTime = t1)
                                server
                            }

                            else -> {
                                error(method)
                            }
                        }
                    }
                val firstDrain = async { fixture.drain(api) }
                started.await()
                val visible = fixture.cache.getCachedMemo("A", "memos/1")!!
                fixture.commit(visible.copy(pinned = true), "pinned", "second")
                release.complete(Unit)
                assertEquals(1, firstDrain.await().acknowledged)
                assertEquals(listOf("second"), fixture.repository.getOps("A").map { it.id })
                val afterFirst = fixture.cache.getCachedMemo("A", "memos/1")!!
                assertEquals("edited", afterFirst.content)
                assertTrue(afterFirst.pinned!!)
                assertEquals(t1.toString(), fixture.repository.getOp("second")!!.baseUpdateTime)
                fixture.drain(api)
                assertTrue(fixture.repository.getOps("A").isEmpty())
                assertEquals("edited", server.content)
                assertTrue(server.pinned!!)
            }
        }

    @Test fun `archiving stale memo queues state only and preserves newer pending content`() =
        runBlocking {
            Fixture().use { fixture ->
                val original =
                    Memo(
                        name = "memos/1",
                        content = "original A",
                        state = org.example.memosm.model.MemoState.NORMAL,
                        location =
                            org.example.memosm.model
                                .Location(placeholder = "preserved"),
                    )
                fixture.cache.seedMemo("A", original)
                val sessions =
                    org.example.memosm.viewmodel
                        .AccountSession(this)
                sessions.activate(
                    org.example.memosm.model
                        .Account(id = "A"),
                    stub<MemosApi> { method, _ -> error("Delegate must not call $method") },
                    okhttp3.OkHttpClient(),
                )
                try {
                    val actions =
                        org.example.memosm.viewmodel.delegates.MemoActionDelegateImpl(
                            this,
                            kotlinx.coroutines.flow.MutableStateFlow(
                                org.example.memosm.viewmodel
                                    .MemosUiState(),
                            ),
                            stub<org.example.memosm.viewmodel.delegates.DraftDelegate> { _, _ -> Unit },
                            { null },
                            fixture.cache,
                            sessions,
                            org.example.memosm.data
                                .DraftManager(ApplicationProvider.getApplicationContext()),
                            object : SyncWorkScheduler {
                                override fun schedule(accountId: String) {}

                                override fun cancel(accountId: String) {}
                            },
                        )
                    val edited = CompletableDeferred<Unit>()
                    actions.updateMemo(
                        original,
                        "newer B",
                        original.visibility,
                        original.attachments.orEmpty(),
                        onSuccess = { edited.complete(Unit) },
                        onError = { edited.completeExceptionally(AssertionError("Local edit failed")) },
                    )
                    kotlinx.coroutines.withTimeout(10_000) { edited.await() }
                    val archived = CompletableDeferred<Unit>()
                    actions.updateMemo(
                        original,
                        original.content,
                        original.visibility,
                        original.attachments.orEmpty(),
                        state = org.example.memosm.model.MemoState.ARCHIVED,
                        onSuccess = { archived.complete(Unit) },
                        onError = { archived.completeExceptionally(AssertionError("Local archive failed")) },
                    )
                    kotlinx.coroutines.withTimeout(10_000) { archived.await() }
                    assertEquals(listOf("content", "state"), fixture.repository.getOps("A").map { it.updateMask })
                    val visible = requireNotNull(fixture.cache.getCachedMemo("A", "memos/1"))
                    assertEquals("newer B", visible.content)
                    assertEquals(org.example.memosm.model.MemoState.ARCHIVED, visible.state)
                    assertEquals(original.location, visible.location)
                } finally {
                    sessions.clear()
                }
            }
        }

    @Test fun `conflict blocks its follow-up while independent deletion completes`() =
        runBlocking {
            Fixture().use { fixture ->
                val old = Memo(name = "memos/1", content = "old", updateTime = Instant.parse("2026-11-18T10:00:00Z"))
                fixture.cache.seedMemo("A", old)
                fixture.cache.seedMemo("A", Memo(name = "memos/2", content = "independent"))
                fixture.commit(old.copy(content = "local"), "content", "conflict")
                fixture.commit(old.copy(pinned = true), "pinned", "follow-up")
                val delete = PendingOp.new("A", PendingOpType.DELETE, "memos/2")
                fixture.cache.commitMutation(delete, fixture.cache.getCachedMemo("A", "memos/2"))
                var deleted = 0
                val api =
                    stub<MemosApi> { method, _ ->
                        when (method) {
                            "getMemo" -> {
                                old.copy(content = "server change", updateTime = Instant.parse("2026-11-18T11:00:00Z"))
                            }

                            "deleteMemo" -> {
                                deleted++
                                Unit
                            }

                            else -> {
                                error("Conflicted entity must not be sent: $method")
                            }
                        }
                    }
                fixture.drain(api)
                assertEquals(1, deleted)
                assertNull(fixture.cache.getCachedMemo("A", "memos/2"))
                assertEquals(PendingOpStatus.CONFLICT.name, fixture.repository.getOp("conflict")!!.status)
                assertEquals(
                    "server change",
                    fixture.repository
                        .getOp("conflict")!!
                        .toConflict()!!
                        .serverMemo.content,
                )
                assertNotNull(fixture.repository.getOp("follow-up"))
            }
        }

    @Test fun `merging a pin-only conflict includes merged content in projection and queued mask`() =
        runBlocking {
            Fixture().use { fixture ->
                val original =
                    Memo(
                        name = "memos/1",
                        content = "old",
                        pinned = false,
                        updateTime = Instant.parse("2026-11-18T10:00:00Z"),
                    )
                val server = original.copy(content = "server change", updateTime = Instant.parse("2026-11-18T11:00:00Z"))
                fixture.cache.seedMemo("A", original)
                val op = fixture.commit(original.copy(pinned = true), "pinned", "pin-conflict")
                fixture.repository.setStatus(op.id, PendingOpStatus.CONFLICT, GsonProvider.gson.toJson(server))
                val conflict = requireNotNull(requireNotNull(fixture.repository.getOp(op.id)).toConflict())

                fixture.repository.resolveConflict(conflict, ConflictResolution.MERGE, "merged content", fixture.cache)

                val replacement = fixture.repository.getOps("A").single()
                assertNotEquals(op.id, replacement.id)
                assertEquals(setOf("pinned", "content"), replacement.updateMask!!.split(',').toSet())
                assertEquals(PendingOpStatus.PENDING.name, replacement.status)
                val visible = requireNotNull(fixture.cache.getCachedMemo("A", "memos/1"))
                assertEquals("merged content", visible.content)
                assertEquals(true, visible.pinned)
                val entity = requireNotNull(fixture.database.memoDao().getEntity("A", "memos/1"))
                assertEquals("server change", requireNotNull(entity.serverMemo()).content)
            }
        }

    @Test fun `reaction cannot approve external edits for a following content mutation`() =
        runBlocking {
            for (externalEdit in listOf(false, true)) {
                Fixture().use { fixture ->
                    val original = Memo(name = "memos/1", content = "old", updateTime = Instant.parse("2026-11-18T10:00:00Z"))
                    fixture.cache.seedMemo("A", original)
                    val reaction =
                        PendingOp.new(
                            "A",
                            PendingOpType.REACTION_UPSERT,
                            original.name,
                            payloadJson = GsonProvider.gson.toJson(ReactionOpPayload("like", "users/me")),
                        )
                    fixture.cache.commitMutation(reaction, original)
                    fixture.commit(original.copy(content = "my edit"), "content", "after-reaction")
                    var server =
                        original.copy(
                            content = if (externalEdit) "external edit" else "old",
                            updateTime = Instant.parse("2026-11-18T11:00:00Z"),
                        )
                    var updates = 0
                    val api =
                        stub<MemosApi> { method, args ->
                            when (method) {
                                "upsertMemoReaction" -> {
                                    org.example.memosm.model
                                        .Reaction(contentId = "memos/1", reactionType = "like")
                                }

                                "getMemo" -> {
                                    server
                                }

                                "updateMemo" -> {
                                    updates++
                                    server = args[1] as Memo
                                    server
                                }

                                else -> {
                                    error(method)
                                }
                            }
                        }
                    fixture.drain(api)
                    if (externalEdit) {
                        assertEquals(0, updates)
                        assertEquals("external edit", server.content)
                        assertEquals(PendingOpStatus.CONFLICT.name, fixture.repository.getOp("after-reaction")!!.status)
                    } else {
                        assertEquals(1, updates)
                        assertEquals("my edit", server.content)
                        assertTrue(fixture.repository.getOps("A").isEmpty())
                    }
                }
            }
        }

    @Test fun `lost upload acknowledgement recovers existing client resource`() =
        runBlocking {
            Fixture().use { fixture ->
                val file = files.newFile("lost-response.txt").apply { writeText("saved bytes") }
                fixture.database.attachmentUploadDao().upsert(
                    AttachmentUpload("client-id", "A", "client-id", file.path, "original.txt", "text/plain", file.length(), 1),
                )
                var created = false
                val uploaded = Attachment(name = "attachments/client-id", filename = "original.txt", type = "text/plain")
                val api =
                    stub<MemosApi> { method, args ->
                        when (method) {
                            "createAttachment" -> {
                                if (!created) {
                                    created = true
                                    throw IOException("Response lost after server commit")
                                }
                                throw HttpException(Response.error<Unit>(409, "exists".toResponseBody()))
                            }

                            "getAttachment" -> {
                                assertEquals(uploaded.name, args[0])
                                uploaded
                            }

                            else -> {
                                error(method)
                            }
                        }
                    }
                assertTrue(fixture.uploads.replay("A", api, null))
                assertFalse(fixture.uploads.replay("A", api, null))
                assertEquals(uploaded.name, fixture.uploads.resolved("client-id")!!.name)
                assertFalse(
                    fixture.database
                        .attachmentUploadDao()
                        .getById("client-id")!!
                        .permanentlyFailed,
                )
            }
        }

    @Test fun `stale account response cannot acknowledge queued work`() =
        runBlocking {
            Fixture().use { fixture ->
                val memo = Memo(name = "memos/1", content = "keep locally")
                fixture.cache.seedMemo("A", memo)
                val op = PendingOp.new("A", PendingOpType.DELETE, memo.name)
                fixture.cache.commitMutation(op, memo)
                var current = true
                val api =
                    stub<MemosApi> { method, _ ->
                        check(method == "deleteMemo")
                        current = false
                        Unit
                    }
                assertEquals(0, fixture.drain(api) { current }.acknowledged)
                assertNotNull(fixture.repository.getOp(op.id))
                assertNotNull(fixture.database.memoDao().getEntity("A", "memos/1"))
            }
        }

    @Test fun `startup cleanup removes old completed uploads and preserves current composer uploads`() =
        runBlocking {
            Fixture().use { fixture ->
                val cutoff = 100L
                for ((id, createdAt) in listOf("cleanup-old" to 99L, "cleanup-current" to cutoff, "cleanup-new" to 101L)) {
                    val file =
                        fixture.uploads.stagedFile(id).apply {
                            requireNotNull(parentFile).mkdirs()
                            writeText("staged $id")
                        }
                    val uploaded = Attachment(name = "attachments/$id", filename = "$id.txt", type = "text/plain")
                    fixture.database.attachmentUploadDao().upsert(
                        AttachmentUpload(
                            id,
                            "A",
                            id,
                            file.path,
                            uploaded.filename,
                            uploaded.type,
                            file.length(),
                            createdAt,
                            serverJson = GsonProvider.gson.toJson(uploaded),
                        ),
                    )
                }
                val context = ApplicationProvider.getApplicationContext<Context>()
                fixture.uploads.cleanupCompletedUploads(
                    "A",
                    org.example.memosm.data
                        .DraftManager(context),
                    org.example.memosm.data
                        .DataStoreManager(MemoryPreferences()),
                    fixture.cache,
                    fixture.repository,
                    createdBefore = cutoff,
                )
                assertFalse(fixture.uploads.stagedFile("cleanup-old").exists())
                assertNull(fixture.database.attachmentUploadDao().getById("cleanup-old"))
                for (id in listOf("cleanup-current", "cleanup-new")) {
                    assertTrue(fixture.uploads.stagedFile(id).exists())
                    assertNotNull(fixture.database.attachmentUploadDao().getById(id))
                }
            }
        }

    @Test fun `repeated upload transport failures retain staged bytes and queue row`() =
        runBlocking {
            Fixture().use { fixture ->
                val file = files.newFile("unsent.txt").apply { writeText("irreplaceable bytes") }
                val row = AttachmentUpload("upload", "A", "client-id", file.path, "original.txt", "text/plain", file.length(), 1)
                fixture.database.attachmentUploadDao().upsert(row)
                val api =
                    stub<MemosApi> { method, _ ->
                        check(method == "createAttachment")
                        throw IOException("offline")
                    }
                repeat(12) { assertTrue(fixture.uploads.replay("A", api, null)) }
                assertEquals("irreplaceable bytes", file.readText())
                val retained = fixture.database.attachmentUploadDao().getById("upload")!!
                assertEquals(12, retained.attemptCount)
                assertFalse(retained.permanentlyFailed)
                assertNull(retained.serverJson)
            }
        }

    @Test fun `rejected or unauthenticated upload surfaces attention on dependent memo`() =
        runBlocking {
            for (code in listOf(401, 413)) {
                Fixture().use { fixture ->
                    val file = files.newFile("blocked-$code.txt").apply { writeText("keep my bytes") }
                    fixture.database.attachmentUploadDao().upsert(
                        AttachmentUpload(
                            "client-id",
                            "A",
                            "client-id",
                            file.path,
                            "original.txt",
                            "text/plain",
                            file.length(),
                            1,
                        ),
                    )
                    val placeholder =
                        Attachment(filename = "original.txt", type = "text/plain", clientId = "client-id", localPath = file.path)
                    val local = Memo(name = "offline-memo", content = "attached", attachments = listOf(placeholder))
                    val op = PendingOp.new("A", PendingOpType.CREATE, local.name, payloadJson = GsonProvider.gson.toJson(local))
                    fixture.cache.commitMutation(op, local)
                    val api =
                        stub<MemosApi> { method, _ ->
                            check(method == "createAttachment") { "Dependent memo must wait: $method" }
                            throw HttpException(Response.error<Unit>(code, "rejected".toResponseBody()))
                        }
                    fixture.drain(api)
                    val pending = fixture.repository.getOp(op.id)!!
                    assertEquals(if (code == 401) PendingOpStatus.AUTH_REQUIRED.name else PendingOpStatus.REJECTED.name, pending.status)
                    assertTrue(pending.lastError!!.startsWith("Attachment upload:"))
                    assertEquals("keep my bytes", file.readText())
                    assertEquals("attached", fixture.cache.getCachedMemo("A", "offline-memo")!!.content)
                }
            }
        }

    @Test fun `upload persists server-assigned identity and unblocks dependent memo`() =
        runBlocking {
            Fixture().use { fixture ->
                val file = files.newFile("ready.txt").apply { writeText("bytes") }
                fixture.database.attachmentUploadDao().upsert(
                    AttachmentUpload(
                        "client-id",
                        "A",
                        "client-id",
                        file.path,
                        "original.txt",
                        "text/plain",
                        file.length(),
                        1,
                    ),
                )
                val placeholder = Attachment(filename = "original.txt", type = "text/plain", clientId = "client-id", localPath = file.path)
                val local = Memo(name = "offline-memo", content = "attached", attachments = listOf(placeholder))
                val op = PendingOp.new("A", PendingOpType.CREATE, local.name, payloadJson = GsonProvider.gson.toJson(local))
                fixture.cache.commitMutation(op, local)
                val actual = Attachment(name = "attachments/server-assigned", filename = "original.txt", type = "text/plain")
                var posted: Memo? = null
                val api =
                    stub<MemosApi> { method, args ->
                        when (method) {
                            "createAttachment" -> {
                                actual
                            }

                            "createMemo" -> {
                                val memo = args[0] as Memo
                                posted = memo
                                memo.copy(name = "memos/server-assigned")
                            }

                            else -> {
                                error(method)
                            }
                        }
                    }
                fixture.drain(api)
                val bound = requireNotNull(requireNotNull(posted).attachments).single()
                assertEquals(actual.name, bound.name)
                assertNull(bound.clientId)
                assertEquals(actual, fixture.uploads.resolved("client-id"))
                assertEquals("memos/server-assigned", fixture.cache.resolveServerName("A", "offline-memo"))
                assertTrue(fixture.repository.getOps("A").isEmpty())
            }
        }
}
