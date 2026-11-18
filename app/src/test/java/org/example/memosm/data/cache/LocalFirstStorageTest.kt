package org.example.memosm.data.cache

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.example.memosm.api.GsonProvider
import org.example.memosm.data.cache.cachedMemos
import org.example.memosm.data.cache.seedMemos
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.PendingOpType
import org.example.memosm.data.sync.ReactionOpPayload
import org.example.memosm.model.Attachment
import org.example.memosm.model.Location
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoState
import org.example.memosm.model.Visibility
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LocalFirstStorageTest {
    private lateinit var db: MemoCacheDatabase
    private lateinit var cache: MemoCacheRepository
    private val account = "account"
    private val key = defaultQueryKey(CacheListType.COMMENT, "memos/parent")

    @Test fun localMapMembershipTracksLocationArchiveAndDeleteRollbacks() =
        runBlocking {
            val memo = Memo(name = "memos/map-local", content = "server", location = Location(latitude = 1.0, longitude = 2.0))
            cache.seedMemos(account, CacheListType.USER, listOf(memo))
            val edited = memo.copy(content = "local")
            cache.commitMutation(mutation(edited), edited)
            val mapTypes = listOf(CacheListType.MAP_USER, CacheListType.MAP_ALL)
            for (type in mapTypes) assertEquals(1, cache.cachedMemos(account, type).size)

            val changes =
                listOf(
                    mutation(edited.copy(location = null), mask = "location") to edited.copy(location = null),
                    mutation(edited.copy(state = MemoState.ARCHIVED), mask = "state") to edited.copy(state = MemoState.ARCHIVED),
                    mutation(edited, PendingOpType.DELETE) to edited,
                )
            for ((op, changed) in changes) {
                cache.commitMutation(op, changed)
                for (type in mapTypes) assertTrue(cache.cachedMemos(account, type).isEmpty())
                cache.discardMutation(op)
                for (type in mapTypes) assertEquals(1, cache.cachedMemos(account, type).size)
            }
        }

    @Test fun staleUserPagesDoNotRestoreAMemoWithAPendingArchiveToTheUserFeed() =
        runBlocking {
            val original = Memo(name = "memos/archived", content = "memo", state = MemoState.NORMAL, visibility = Visibility.PUBLIC)
            cache.ingestPage(account, "USER", CacheListType.USER, listOf(original), nextPageToken = null, refresh = true)
            val archived = original.copy(state = MemoState.ARCHIVED)
            cache.commitMutation(mutation(archived, mask = "state"), archived, CacheListType.ARCHIVED)

            cache.ingestPage(account, "USER", CacheListType.USER, listOf(original), nextPageToken = null, refresh = true)

            assertTrue(cache.cachedMemos(account, CacheListType.USER).isEmpty())
            assertEquals(listOf("memos/archived"), cache.cachedMemos(account, CacheListType.ARCHIVED).map { it.name })
            assertEquals(MemoState.ARCHIVED, cache.getCachedMemo(account, "memos/archived")?.state)
            assertEquals(1, db.pendingOpDao().getOps(account).size)
        }

    @Test fun privacyChangesRemoveExploreMembershipEvenWhenUserMembershipAlreadyExists() =
        runBlocking {
            val original = Memo(name = "memos/private", content = "memo", visibility = Visibility.PUBLIC)
            for (type in listOf(CacheListType.USER, CacheListType.EXPLORE, CacheListType.MAP_EXPLORE)) {
                cache.seedMemos(account, type, listOf(original))
            }
            val privateMemo = original.copy(visibility = Visibility.PRIVATE)
            cache.commitMutation(mutation(privateMemo, mask = "visibility"), privateMemo)
            assertEquals(1, cache.cachedMemos(account, CacheListType.USER).size)
            assertTrue(cache.cachedMemos(account, CacheListType.EXPLORE).isEmpty())
            assertTrue(cache.cachedMemos(account, CacheListType.MAP_EXPLORE).isEmpty())

            cache.ingestPage(account, "EXPLORE", CacheListType.EXPLORE, listOf(original), nextPageToken = null, refresh = true)

            assertTrue(cache.cachedMemos(account, CacheListType.EXPLORE).isEmpty())
            assertEquals(Visibility.PRIVATE, cache.getCachedMemo(account, "memos/private")?.visibility)
        }

    @Test fun ownedEditsFromMapOrSearchCreateTheAppropriateOwnFeedMembership() =
        runBlocking {
            for (type in listOf(CacheListType.MAP_USER, CacheListType.SEARCH)) {
                val memo = Memo(name = "memos/${type.name}", content = "server", visibility = Visibility.PUBLIC)
                cache.seedMemos(account, type, listOf(memo))
                val edited = memo.copy(content = "edited ${type.name}")
                cache.commitMutation(mutation(edited), edited)
            }
            assertEquals(setOf("memos/MAP_USER", "memos/SEARCH"), cache.cachedMemos(account, CacheListType.USER).map { it.name }.toSet())
        }

    @Test fun reactingToForeignExploreContentDoesNotAddItToTheOwnFeed() =
        runBlocking {
            val memo =
                Memo(
                    name = "memos/foreign",
                    content = "foreign",
                    creator = "users/other",
                    visibility = Visibility.PUBLIC,
                    location = Location(latitude = 1.0, longitude = 2.0),
                )
            cache.seedMemos(account, CacheListType.EXPLORE, listOf(memo))
            val op =
                PendingOp.new(
                    account,
                    PendingOpType.REACTION_UPSERT,
                    memo.name,
                    payloadJson = GsonProvider.gson.toJson(ReactionOpPayload("thumbsup", "users/me")),
                )

            cache.commitMutation(op, memo)

            assertTrue(cache.cachedMemos(account, CacheListType.USER).isEmpty())
            assertTrue(cache.cachedMemos(account, CacheListType.ARCHIVED).isEmpty())
            assertTrue(cache.cachedMemos(account, CacheListType.MAP_USER).isEmpty())
            assertTrue(cache.cachedMemos(account, CacheListType.MAP_ALL).isEmpty())
            assertEquals(1, cache.cachedMemos(account, CacheListType.EXPLORE).size)
            assertEquals(
                "thumbsup",
                cache
                    .getCachedMemo(account, memo.name!!)
                    ?.reactions
                    ?.single()
                    ?.reactionType,
            )
        }

    @Test fun acknowledgingAnUploadedAttachmentPopulatesOfflineResourcesInTheSameCommit() =
        runBlocking {
            val placeholder = Attachment(filename = "photo.png", type = "image/png", clientId = "local-upload")
            val local = Memo(name = "offline-created", content = "photo", attachments = listOf(placeholder))
            val op = mutation(local, PendingOpType.CREATE)
            cache.commitMutation(op, local)
            assertTrue(db.cachedAttachmentMetaDao().getAll(account).isEmpty())
            val uploaded = placeholder.copy(name = "attachments/server-assigned", clientId = null)

            cache.acknowledge(op, local.copy(name = "memos/server-created", attachments = listOf(uploaded)))

            val metadata = db.cachedAttachmentMetaDao().getAll(account).single()
            assertEquals("attachments/server-assigned", metadata.attachmentName)
            assertEquals("offline-created", metadata.memoName)
            assertEquals("photo.png", metadata.filename)
            assertEquals(
                "attachments/server-assigned",
                cache
                    .getCachedMemo(account, "offline-created")
                    ?.attachments
                    ?.single()
                    ?.name,
            )
            assertTrue(db.pendingOpDao().getOps(account).isEmpty())
        }

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MemoCacheDatabase::class.java).build()
        cache = MemoCacheRepository(db.memoDao())
    }

    @After fun close() = db.close()

    private fun mutation(
        memo: Memo,
        type: PendingOpType = PendingOpType.UPDATE,
        mask: String = "content",
        parent: String? = null,
    ) = PendingOp.new(account, type, memo.name, parentName = parent, payloadJson = GsonProvider.gson.toJson(memo), updateMask = mask)

    @Test fun successfulEmptyThreadPrunesOldCommentsAndRetainsUnsentComments() =
        runBlocking {
            cache.ingestPage(
                account,
                key,
                CacheListType.COMMENT,
                listOf(Memo(name = "memos/old", content = "old")),
                "memos/parent",
                null,
                true,
            )
            val local = Memo(name = "offline-comment", content = "unsent")
            cache.commitMutation(mutation(local, PendingOpType.COMMENT_CREATE, parent = "memos/parent"), local, CacheListType.COMMENT)
            cache.ingestPage(account, key, CacheListType.COMMENT, emptyList(), "memos/parent", null, true)
            assertEquals(listOf("offline-comment"), db.memoDao().getQueryMemos(account, key).map { it.name })
            val query = requireNotNull(cache.getQuery(account, key))
            assertTrue(query.loaded)
            assertTrue(query.complete)
            assertNull(query.nextPageToken)
            cache.ingestPage("other", key, CacheListType.COMMENT, emptyList(), "memos/parent", null, true)
            assertTrue(requireNotNull(cache.getQuery("other", key)).loaded)
            assertTrue(db.memoDao().getQueryMemos("other", key).isEmpty())
        }

    @Test fun interruptedRefreshKeepsOldRowsAndRejectsAnOlderGeneration() =
        runBlocking {
            cache.ingestPage(
                account,
                key,
                CacheListType.COMMENT,
                listOf(Memo(name = "memos/old", content = "old")),
                "memos/parent",
                null,
                true,
            )
            val old = cache.beginRefresh(account, key, CacheListType.COMMENT, "memos/parent")
            cache.ingestPage(
                account,
                key,
                CacheListType.COMMENT,
                listOf(Memo(name = "memos/new", content = "new")),
                "memos/parent",
                "next",
                true,
                expectedGeneration = old,
            )
            assertEquals(
                setOf("memos/old", "memos/new"),
                db
                    .memoDao()
                    .getQueryMemos(account, key)
                    .map { it.name }
                    .toSet(),
            )
            assertFalse(requireNotNull(cache.getQuery(account, key)).complete)
            val current = cache.beginRefresh(account, key, CacheListType.COMMENT, "memos/parent")
            try {
                cache.ingestPage(
                    account,
                    key,
                    CacheListType.COMMENT,
                    emptyList(),
                    "memos/parent",
                    null,
                    false,
                    expectedGeneration = old,
                    expectedPageToken = "next",
                )
                fail("An obsolete page must not commit")
            } catch (_: CancellationException) {
            }
            assertEquals(current, cache.getQuery(account, key)?.generation)
            assertEquals(2, db.memoDao().getQueryMemos(account, key).size)
        }

    @Test fun acknowledgingOneEditReappliesAQueuedLaterFieldMask() =
        runBlocking {
            val original = Memo(name = "memos/a", content = "server", pinned = false)
            cache.seedMemos(account, CacheListType.USER, listOf(original))
            val first = mutation(original.copy(content = "first"))
            cache.commitMutation(first, original.copy(content = "first"))
            val later = mutation(original.copy(content = "stale", pinned = true), mask = "pinned")
            cache.commitMutation(later, original.copy(content = "first", pinned = true))
            cache.acknowledge(first, original.copy(content = "accepted first"))
            val visible = requireNotNull(cache.getCachedMemo(account, "memos/a"))
            assertEquals("accepted first", visible.content)
            assertTrue(visible.pinned == true)
            assertEquals(listOf(later.id), db.pendingOpDao().getOps(account).map { it.id })
        }

    @Test fun failedProjectionRollsBackBothMutationAndVisibleState() =
        runBlocking {
            val memo = Memo(name = "memos/a", content = "server")
            cache.seedMemos(account, CacheListType.USER, listOf(memo))
            val invalid = mutation(memo).copy(payloadJson = "{invalid json")
            try {
                cache.commitMutation(invalid, memo.copy(content = "lost"))
                fail("Malformed mutation must fail")
            } catch (
                _: com.google.gson.JsonParseException,
            ) {
            }
            assertTrue(db.pendingOpDao().getOps(account).isEmpty())
            assertEquals("server", cache.getCachedMemo(account, "memos/a")?.content)
        }

    @Test fun createAcknowledgementKeepsLocalIdentityAndCommentParent() =
        runBlocking {
            val parent = Memo(name = "offline-parent", content = "parent")
            val create = mutation(parent, PendingOpType.CREATE)
            cache.commitMutation(create, parent)
            val child = Memo(name = "offline-child", content = "child")
            val comment = mutation(child, PendingOpType.COMMENT_CREATE, parent = parent.name)
            cache.commitMutation(comment, child, CacheListType.COMMENT)
            cache.acknowledge(create, parent.copy(name = "memos/server-parent"))
            assertEquals("memos/server-parent", cache.resolveServerName(account, "offline-parent"))
            assertEquals("offline-parent", cache.getCachedMemo(account, "memos/server-parent")?.name)
            assertEquals(
                "offline-parent",
                db
                    .pendingOpDao()
                    .getOps(account)
                    .single()
                    .parentName,
            )
            cache.acknowledge(comment, child.copy(name = "memos/server-child", parent = "memos/server-parent"))
            assertEquals("offline-child", cache.cachedMemos(account, CacheListType.COMMENT, "offline-parent").single().name)
        }

    @Test fun changingACommentKeepsItOutOfTheUserFeed() =
        runBlocking {
            val comment = Memo(name = "memos/comment", content = "comment")
            cache.seedMemos(account, CacheListType.COMMENT, listOf(comment), parentName = "memos/parent")
            cache.commitMutation(
                mutation(comment.copy(content = "edited")),
                comment.copy(content = "edited"),
                CacheListType.COMMENT,
                "memos/parent",
            )
            assertEquals("edited", cache.cachedMemos(account, CacheListType.COMMENT, "memos/parent").single().content)
            assertTrue(cache.cachedMemos(account, CacheListType.USER).isEmpty())
        }

    @Test fun cacheClearPreservesPendingContentAndDeleteTombstones() =
        runBlocking {
            val clean = Memo(name = "memos/clean", content = "clean")
            val edited = Memo(name = "memos/edited", content = "edited")
            val removed = Memo(name = "memos/deleted", content = "deleted")
            cache.seedMemos(account, CacheListType.USER, listOf(clean, edited, removed))
            cache.commitMutation(mutation(edited), edited)
            cache.commitMutation(mutation(removed, PendingOpType.DELETE), removed)
            cache.clearCache(account)
            assertEquals("edited", cache.getCachedMemo(account, "memos/edited")?.content)
            assertNull(cache.getCachedMemo(account, "memos/clean"))
            assertNull(cache.getCachedMemo(account, "memos/deleted"))
            assertTrue(requireNotNull(db.memoDao().getEntity(account, "memos/deleted")).deleted)
            assertEquals(2, db.pendingOpDao().getOps(account).size)
        }

    @Test fun canonicalUpdatesReachAllMembershipsAndCachedOrderUsesDisplayTime() =
        runBlocking {
            val first =
                Memo(
                    name = "memos/first",
                    content = "old",
                    createTime = Instant.fromEpochMilliseconds(100),
                    displayTime = Instant.fromEpochMilliseconds(300),
                )
            val second =
                Memo(
                    name = "memos/second",
                    content = "second",
                    createTime = Instant.fromEpochMilliseconds(200),
                    displayTime = Instant.fromEpochMilliseconds(200),
                )
            cache.seedMemos(account, CacheListType.USER, listOf(second, first))
            cache.seedMemos(account, CacheListType.EXPLORE, listOf(first.copy(content = "fresh")))
            assertEquals(listOf("memos/first", "memos/second"), cache.cachedMemos(account, CacheListType.USER).map { it.name })
            assertEquals("fresh", cache.cachedMemos(account, CacheListType.USER).first().content)
            assertEquals("fresh", cache.getCachedMemo(account, "memos/first")?.content)
        }

    @Test fun incrementalSyncDoesNotMoveTheFullSnapshotCheckpointOrPruneRows() =
        runBlocking {
            val history = "USER:history"
            cache.ingestPage(
                account,
                history,
                CacheListType.USER,
                listOf(Memo(name = "memos/old", content = "old")),
                nextPageToken = null,
                refresh = true,
                checkpointAt = 100,
            )
            cache.ingestPage(
                account,
                history,
                CacheListType.USER,
                emptyList(),
                nextPageToken = null,
                refresh = true,
                incremental = true,
                checkpointAt = 200,
            )
            val query = requireNotNull(cache.getQuery(account, history))
            assertEquals(100L, query.lastFullSnapshotAt)
            assertEquals(200L, query.incrementalCursor)
            assertEquals(1, db.memoDao().getQueryMemos(account, history).size)
            assertEquals(1, db.memoDao().getQueryMemos(account, "USER").size)
        }

    @Test fun repeatedSubmissionOfTheSameImmutableMutationIsIdempotent() =
        runBlocking {
            val memo = Memo(name = "offline-created", content = "saved")
            val op = mutation(memo, PendingOpType.CREATE)
            cache.commitMutation(op, memo)
            cache.commitMutation(op, memo)
            assertEquals(1, db.pendingOpDao().getOps(account).size)
        }

    @Test fun aPendingDeleteRejectsLaterEditsAndNewCommentsWithoutCreatingOrphans() =
        runBlocking {
            val parent = Memo(name = "memos/parent", content = "parent")
            cache.seedMemos(account, CacheListType.USER, listOf(parent))
            cache.commitMutation(mutation(parent, PendingOpType.DELETE), parent)
            try {
                cache.commitMutation(mutation(parent.copy(content = "edit")), parent.copy(content = "edit"))
                fail("An edit must not resurrect a pending deletion")
            } catch (_: IllegalArgumentException) {
            }
            val child = Memo(name = "offline-child", content = "child")
            try {
                cache.commitMutation(mutation(child, PendingOpType.COMMENT_CREATE, parent = parent.name), child, CacheListType.COMMENT)
                fail("A new comment must not target a deleted parent")
            } catch (_: IllegalArgumentException) {
            }
            assertNull(cache.getCachedMemo(account, parent.name!!))
            assertNull(db.memoDao().getEntity(account, "offline-child"))
            assertEquals(listOf(PendingOpType.DELETE.name), db.pendingOpDao().getOps(account).map { it.type })
        }

    @Test fun remoteFetchBeforeCreateAcknowledgementMergesIntoTheStableLocalIdentity() =
        runBlocking {
            val local = Memo(name = "offline-parent", content = "parent")
            val op = mutation(local, PendingOpType.CREATE)
            cache.commitMutation(op, local)
            val remote = local.copy(name = "memos/server-parent")
            cache.ingestPage(account, "USER", CacheListType.USER, listOf(remote), nextPageToken = null, refresh = true)
            val comment = Memo(name = "memos/comment", content = "comment")
            cache.ingestPage(
                account,
                "COMMENT:memos/server-parent",
                CacheListType.COMMENT,
                listOf(comment),
                "memos/server-parent",
                null,
                true,
            )
            cache.acknowledge(op, remote)
            assertEquals("memos/server-parent", cache.resolveServerName(account, "offline-parent"))
            assertEquals(listOf("offline-parent"), cache.cachedMemos(account, CacheListType.USER).map { it.name })
            assertEquals("offline-parent", db.memoDao().getEntity(account, "memos/comment")?.parentId)
            assertEquals(listOf("memos/comment"), db.memoDao().getQueryMemos(account, "COMMENT:offline-parent").map { it.name })
        }

    @Test fun restartingAnInterruptedIncrementalCrawlResetsItsTokenWithoutLosingTheFullBaseline() =
        runBlocking {
            val history = "USER:history"
            cache.ingestPage(
                account,
                history,
                CacheListType.USER,
                listOf(Memo(name = "memos/a", content = "a")),
                nextPageToken = null,
                refresh = true,
                checkpointAt = 100,
            )
            val old = cache.beginRefresh(account, history, CacheListType.USER, incremental = true)
            cache.ingestPage(
                account,
                history,
                CacheListType.USER,
                emptyList(),
                nextPageToken = "page2",
                refresh = true,
                incremental = true,
                checkpointAt = 200,
                expectedGeneration = old,
            )
            val restarted = cache.beginRefresh(account, history, CacheListType.USER, incremental = true)
            cache.ingestPage(
                account,
                history,
                CacheListType.USER,
                emptyList(),
                nextPageToken = null,
                refresh = true,
                incremental = true,
                checkpointAt = 300,
                expectedGeneration = restarted,
            )
            val query = requireNotNull(cache.getQuery(account, history))
            assertTrue(query.complete)
            assertNull(query.nextPageToken)
            assertEquals(100L, query.lastFullSnapshotAt)
            assertEquals(300L, query.incrementalCursor)
        }

    @Test fun explicitRetryPreservesConflictsPayloadsAndOtherAccounts() =
        runBlocking {
            val rejected =
                mutation(Memo(name = "memos/rejected", content = "retained")).copy(
                    status = "REJECTED",
                    permanentlyFailed = true,
                    lastError = "rejected",
                    lastAttemptAt = 100,
                )
            val server = Memo(name = "memos/conflict", content = "server")
            val conflict =
                mutation(server.copy(content = "local")).copy(
                    status = "CONFLICT",
                    conflictServerJson = GsonProvider.gson.toJson(server),
                )
            val other = rejected.copy(id = "other-account", accountId = "other")
            val operations = db.pendingOpDao()
            listOf(rejected, conflict, other).forEach { operations.insert(it) }
            val upload =
                org.example.memosm.data.media.AttachmentUpload(
                    id = "upload",
                    accountId = account,
                    clientId = "upload",
                    localPath = "/retained/staged-bytes",
                    filename = "photo.jpg",
                    mimeType = "image/jpeg",
                    size = 1024,
                    createdAt = 1,
                    permanentlyFailed = true,
                    authRequired = true,
                    lastError = "rejected",
                )
            db.attachmentUploadDao().upsert(upload)

            org.example.memosm.data.sync
                .SyncRepository(operations)
                .retryAll(account)
            db.attachmentUploadDao().retryAll(account)

            val retried = requireNotNull(operations.getOp(rejected.id))
            assertEquals("PENDING", retried.status)
            assertFalse(retried.permanentlyFailed)
            assertNull(retried.lastError)
            assertEquals(rejected.payloadJson, retried.payloadJson)
            assertEquals(conflict, operations.getOp(conflict.id))
            assertEquals(other, operations.getOp(other.id))
            val staged = requireNotNull(db.attachmentUploadDao().getById(upload.id))
            assertFalse(staged.permanentlyFailed)
            assertFalse(staged.authRequired)
            assertEquals(upload.localPath, staged.localPath)
            assertEquals(upload.size, staged.size)
        }
}
