package org.example.memosm.data.paging

import android.app.Application
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.example.memosm.account.stub
import org.example.memosm.api.MemosApi
import org.example.memosm.data.cache.MemoCacheDatabase
import org.example.memosm.data.media.toAttachment
import org.example.memosm.data.media.toCachedMetadata
import org.example.memosm.data.offline.AttachmentCacheStore
import org.example.memosm.model.Account
import org.example.memosm.model.Attachment
import org.example.memosm.model.ListAttachmentsResponse
import org.example.memosm.viewmodel.AccountContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AttachmentPagingTest {
    private fun attachment(id: String) =
        Attachment(
            name = "attachments/$id",
            filename = "$id.jpg",
            type = "image/jpeg",
            createTime = Instant.parse("2026-10-04T12:00:00Z"),
            size = "1024",
            externalLink = "https://files.example.com/$id.jpg",
        )

    @Test fun cachedMetadataRetainsLinksAndRepairsMissingRequiredFields() {
        val original = attachment("one")
        val row = original.toCachedMetadata("A")
        assertEquals(original, row.toAttachment())
        for (json in listOf("{}", """{"filename":null,"type":null}""", "null", "{")) {
            val recovered = row.copy(attachmentJson = json).toAttachment()
            assertEquals(original.name, recovered.name)
            assertEquals(original.filename, recovered.filename)
            assertEquals(original.type, recovered.displayType)
            assertEquals(original.createTime, recovered.createTime)
            assertEquals(original.size, recovered.size)
        }
        val incomplete = row.copy(attachmentJson = """{"externalLink":"https://files.example.com/one.jpg"}""").toAttachment()
        assertEquals(original.externalLink, incomplete.externalLink)
        val untimed = original.copy(createTime = null, size = null, memo = null)
        assertEquals(untimed, untimed.toCachedMetadata("A", "local-memo-id").toAttachment())
        val recoveredUntimed = untimed.toCachedMetadata("A", "local-memo-id").copy(attachmentJson = "null").toAttachment()
        assertNull(recoveredUntimed.createTime)
        assertNull(recoveredUntimed.size)
        assertNull(recoveredUntimed.memo)
        val epoch = original.copy(createTime = Instant.fromEpochMilliseconds(0), size = "0")
        assertEquals(epoch, epoch.toCachedMetadata("A").toAttachment())
    }

    @Test fun cachedGallerySurvivesShrinkingRefreshAndOfflineRestart() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<android.content.Context>()
            val name = "attachments-${java.util.UUID.randomUUID()}.db"

            fun open() = Room.databaseBuilder(app, MemoCacheDatabase::class.java, name).build()
            var database = open()
            var collector: Job? = null
            try {
                var cache = AttachmentCacheStore(database.cachedAttachmentMetaDao())
                val old = (0..59).map { attachment("$it") }
                cache.ingestPage("A", old, null, refresh = true)
                val replacement = old.last().copy(filename = "updated.jpg")
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var calls = 0
                val account =
                    AccountContext(
                        Account(id = "A"),
                        stub<MemosApi> { method, _ ->
                            check(method == "listAttachments") { "Unexpected API: $method" }
                            calls++
                            started.complete(Unit)
                            release.await()
                            ListAttachmentsResponse(listOf(replacement), null, 1)
                        },
                        OkHttpClient(),
                        generation = 1,
                    )
                val shown = MutableStateFlow(emptyList<Attachment>())
                val presenter =
                    object : PagingDataPresenter<Attachment>(Dispatchers.Unconfined) {
                        override suspend fun presentPagingDataEvent(event: PagingDataEvent<Attachment>) {
                            shown.value = snapshot().items
                        }
                    }
                collector =
                    launch {
                        attachmentPages(account, cache, { true }, { true }).collectLatest { presenter.collectFrom(it) }
                    }
                withTimeout(10_000) {
                    started.await()
                    shown.first { it.size == old.size }
                }
                assertEquals(old.toSet(), shown.value.toSet())
                release.complete(Unit)
                withTimeout(10_000) { shown.first { it == listOf(replacement) } }
                assertEquals(listOf(replacement), cache.getCachedMeta("A"))
                collector.cancelAndJoin()
                collector = null
                database.close()
                database = open()
                cache = AttachmentCacheStore(database.cachedAttachmentMetaDao())
                val offline =
                    withTimeout(10_000) {
                        attachmentPages(account, cache, { false }, { true }).asSnapshot()
                    }
                assertEquals(listOf(replacement), offline)
                assertEquals(1, calls)
            } finally {
                collector?.cancelAndJoin()
                database.close()
                app.deleteDatabase(name)
            }
        }

    @Test fun unreadableCachedJsonUsesRoomMetadataWithoutClearingOtherRows() =
        runBlocking {
            val database =
                Room
                    .inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(),
                        MemoCacheDatabase::class.java,
                    ).build()
            try {
                val cache = AttachmentCacheStore(database.cachedAttachmentMetaDao())
                val original = attachment("one")
                val valid = attachment("two")
                database.cachedAttachmentMetaDao().upsertAll(
                    listOf(
                        original.toCachedMetadata("A").copy(attachmentJson = "{"),
                        valid.toCachedMetadata("A"),
                        attachment("other-account").toCachedMetadata("B"),
                    ),
                )
                val account =
                    AccountContext(
                        Account(id = "A"),
                        stub<MemosApi> { method, _ -> error("Offline API request: $method") },
                        OkHttpClient(),
                        generation = 1,
                    )
                val offline =
                    withTimeout(10_000) {
                        attachmentPages(account, cache, { false }, { true }).asSnapshot()
                    }
                assertEquals(listOf(original.name, valid.name), offline.map { it.name })
                assertEquals(original.filename, offline.first().filename)
                assertEquals(valid, offline.last())
                assertEquals(offline, cache.getCachedMeta("A"))
                assertEquals(2, database.cachedAttachmentMetaDao().count("A"))
                assertEquals(1, database.cachedAttachmentMetaDao().count("B"))
            } finally {
                database.close()
            }
        }
}
