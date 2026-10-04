package org.example.memosm.account

import android.content.ContextWrapper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.example.memosm.api.MemosApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.cache.*
import org.example.memosm.data.media.*
import org.example.memosm.data.offline.AttachmentCacheStore
import org.example.memosm.data.store.RoomAttachmentMetaStore
import org.example.memosm.data.sync.PreDownloadManager
import org.example.memosm.data.sync.PreDownloadState
import org.example.memosm.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class MediaAccountIsolationTest {
    @get:Rule val temp = TemporaryFolder()

    private fun context() = object : ContextWrapper(null) {
        override fun getFilesDir() = File(temp.root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(temp.root, "cache").apply { mkdirs() }
    }

    @Test fun `same host accounts use their own binaries and missing identity never falls back`() = runTest {
        val manager = DataStoreManager(MemoryPreferences())
        manager.saveAccounts(listOf(
            Account(id = "A", hostUrl = "https://same-server", isActive = true),
            Account(id = "B", hostUrl = "https://same-server")
        ))
        val a = temp.newFile("a.jpg").apply { writeText("A") }
        val b = temp.newFile("b.jpg").apply { writeText("B") }
        val rows = listOf(
            CachedAttachment("A", "attachments/1", "memos/1", "url", a.path),
            CachedAttachment("B", "attachments/1", "memos/1", "url", b.path)
        )
        val dao = stub<CachedAttachmentDao> { name, _ ->
            check(name == "getAll")
            rows
        }
        val media = AttachmentCacheManager(context(), dao, OkHttpClient(), manager) { true }
        media.warmIndex()
        assertEquals("A", media.getLocalFileForAccount("A", "attachments/1")?.readText())
        assertEquals("B", media.getLocalFileForAccount("B", "attachments/1")?.readText())
        assertNull(media.getLocalFileForAccount(null, "attachments/1"))
    }

    @Test fun `equal nonzero usage publishes both account identities`() = runTest {
        val manager = DataStoreManager(MemoryPreferences())
        manager.saveAccounts(listOf(Account(id = "A", isActive = true), Account(id = "B")))
        val dao = stub<CachedAttachmentDao> { name, args ->
            when (name) {
                "getTotalSize" -> 20L
                "getAllForAccount" -> listOf(CachedAttachment(args[0] as String,
                    "attachments/1", "memos/1", "url", "path"))
                else -> error(name)
            }
        }
        val media = AttachmentCacheManager(context(), dao, OkHttpClient(), manager) { true }
        val seen = mutableListOf<AttachmentCacheManager.AccountUsage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { media.usage.collect { seen += it } }
        media.refreshUsage("A")
        manager.setActiveAccount("B")
        media.refreshUsage("B")
        assertEquals(listOf("A", "B"), seen.mapNotNull { it.accountId })
        assertEquals(listOf(20L, 20L), seen.filter { it.accountId != null }.map { it.usage.bytes })
        assertEquals("B", media.usage.value.accountId)
        assertEquals(1, media.usage.value.usage.count)
    }

    @Test fun `attachment phase retains the host token and account captured before history fetch`() = runTest {
        val manager = DataStoreManager(MemoryPreferences())
        manager.saveAccounts(listOf(Account(id = "A", isActive = true)))
        val release = CompletableDeferred<Unit>()
        var active = "A"
        val attachment = Attachment(name = "attachments/1", filename = "photo.jpg", type = "image/jpeg")
        val memo = Memo(name = "memos/1", content = "A", attachments = listOf(attachment))
        val api = stub<MemosApi> { name, args ->
            when (name) {
                "buildMemoCreatorFilter" -> "creator == 'users/A'"
                "listMemos" -> {
                    if (args[2] == "ARCHIVED") ListMemosResponse(emptyList(), null)
                    else { release.await(); ListMemosResponse(listOf(memo), null) }
                }
                else -> error("Unexpected API: $name")
            }
        }
        val rows = mutableListOf<CachedMemo>()
        val memos = MemoCacheRepository(stub<MemoDao> { name, args ->
            when (name) {
                "getCountForAccount" -> rows.size
                "cacheRemoteMemos" -> { @Suppress("UNCHECKED_CAST")
                    rows.addAll(args[2] as List<CachedMemo>); Unit }
                "trimListType" -> Unit
                "getMemos" -> rows.filter { it.accountId == args[0] && it.listType == args[1] }
                else -> error("Unexpected memo DAO: $name")
            }
        })
        val downloads = CopyOnWriteArrayList<Request>()
        val binaries = CopyOnWriteArrayList<CachedAttachment>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            downloads += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("A bytes".toResponseBody()).build()
        }.build()
        val media = AttachmentCacheManager(context(), stub<CachedAttachmentDao> { name, args ->
            when (name) {
                "getAll" -> binaries.toList()
                "getByAttachment" -> binaries.firstOrNull { it.accountId == args[0] && it.attachmentName == args[1] }
                "upsert" -> { binaries += args[0] as CachedAttachment; Unit }
                "getTotalSize", "getTotalSizeAll" -> 0L
                "getAllForAccount" -> binaries.filter { it.accountId == args[0] }
                else -> error("Unexpected binary DAO: $name")
            }
        }, http, manager) { true }
        val attachments = AttachmentCacheStore(memos, RoomAttachmentMetaStore(stub<CachedAttachmentMetaDao> { name, _ ->
            when (name) { "count" -> 1; "getAll" -> emptyList<CachedAttachmentMeta>(); else -> error(name) }
        }), media)
        val predownload = PreDownloadManager(
            backgroundScope, memos, manager, media, attachments,
            { api }, { active }, { UserSnapshot(name = "users/$active") },
            { "https://${active.lowercase()}.example/" }, { "token-$active" }, { true }, { true }
        )
        predownload.downloadAllText()
        runCurrent()
        active = "B"
        release.complete(Unit)
        // IO-backed binary writes are deliberately real; wait for their terminal state.
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                predownload.state.first { it is PreDownloadState.Done || it is PreDownloadState.Failed }
            }
        }
        assertTrue(predownload.state.value.toString(), predownload.state.value is PreDownloadState.Done)
        assertEquals("a.example", downloads.single().url.host)
        assertEquals("Bearer token-A", downloads.single().header("Authorization"))
        assertEquals("A", binaries.single().accountId)
        assertTrue(manager.textSyncCursor("A").first() > 0L)
        assertEquals(0L, manager.textSyncCursor("B").first())
    }
}
