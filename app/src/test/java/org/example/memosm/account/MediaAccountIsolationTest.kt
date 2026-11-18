package org.example.memosm.account

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.example.memosm.api.MemosApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.cache.*
import org.example.memosm.data.media.*
import org.example.memosm.data.sync.downloadMemoHistory
import org.example.memosm.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class MediaAccountIsolationTest {
    @get:Rule val temp = TemporaryFolder()

    private fun context() =
        object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getFilesDir() = File(temp.root, "files").apply { mkdirs() }

            override fun getCacheDir() = File(temp.root, "cache").apply { mkdirs() }
        }

    @Test fun `same host accounts use their own binaries and missing identity never falls back`() =
        runTest {
            val manager = DataStoreManager(MemoryPreferences())
            manager.saveAccounts(
                listOf(
                    Account(id = "A", hostUrl = "https://same-server", isActive = true),
                    Account(id = "B", hostUrl = "https://same-server"),
                ),
            )
            val a = temp.newFile("a.jpg").apply { writeText("A") }
            val b = temp.newFile("b.jpg").apply { writeText("B") }
            val rows =
                listOf(
                    CachedAttachment("A", "attachments/1", "memos/1", "url", a.path),
                    CachedAttachment("B", "attachments/1", "memos/1", "url", b.path),
                )
            val dao =
                stub<CachedAttachmentDao> { name, _ ->
                    check(name == "getAll")
                    rows
                }
            val media = AttachmentCacheManager(context(), dao, OkHttpClient(), manager) { true }
            media.warmIndex()
            assertEquals("A", media.getLocalFileForAccount("A", "attachments/1")?.readText())
            assertEquals("B", media.getLocalFileForAccount("B", "attachments/1")?.readText())
            assertNull(media.getLocalFileForAccount(null, "attachments/1"))
        }

    @Test fun `equal nonzero usage publishes both account identities`() =
        runTest {
            val manager = DataStoreManager(MemoryPreferences())
            manager.saveAccounts(listOf(Account(id = "A", isActive = true), Account(id = "B")))
            val dao =
                stub<CachedAttachmentDao> { name, args ->
                    when (name) {
                        "getTotalSize" -> {
                            20L
                        }

                        "getAllForAccount" -> {
                            listOf(
                                CachedAttachment(
                                    args[0] as String,
                                    "attachments/1",
                                    "memos/1",
                                    "url",
                                    "path",
                                ),
                            )
                        }

                        else -> {
                            error(name)
                        }
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

    @Test fun `attachment phase retains the host token and account captured before history fetch`() =
        runTest {
            val manager = DataStoreManager(MemoryPreferences())
            manager.saveAccounts(listOf(Account(id = "A", isActive = true)))
            val release = CompletableDeferred<Unit>()
            var active = "A"
            val attachment = Attachment(name = "attachments/1", filename = "photo.jpg", type = "image/jpeg")
            val memo = Memo(name = "memos/1", content = "A", attachments = listOf(attachment))
            val api =
                stub<MemosApi> { name, args ->
                    when (name) {
                        "buildMemoCreatorFilter" -> {
                            "creator == 'users/A'"
                        }

                        "listMemos" -> {
                            if (args[2] == "ARCHIVED") {
                                ListMemosResponse(emptyList(), null)
                            } else {
                                release.await()
                                ListMemosResponse(listOf(memo), null)
                            }
                        }

                        else -> {
                            error("Unexpected API: $name")
                        }
                    }
                }
            val database =
                Room
                    .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MemoCacheDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            val memos = MemoCacheRepository(database.memoDao())
            val downloads = CopyOnWriteArrayList<Request>()
            val binaries = CopyOnWriteArrayList<CachedAttachment>()
            val http =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        downloads += chain.request()
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("A bytes".toResponseBody())
                            .build()
                    }.build()
            val media =
                AttachmentCacheManager(
                    context(),
                    stub<CachedAttachmentDao> { name, args ->
                        when (name) {
                            "getAll" -> {
                                binaries.toList()
                            }

                            "getByAttachment" -> {
                                binaries.firstOrNull { it.accountId == args[0] && it.attachmentName == args[1] }
                            }

                            "upsert" -> {
                                binaries += args[0] as CachedAttachment
                                Unit
                            }

                            "getTotalSize", "getTotalSizeAll" -> {
                                0L
                            }

                            "getAllForAccount" -> {
                                binaries.filter { it.accountId == args[0] }
                            }

                            else -> {
                                error("Unexpected binary DAO: $name")
                            }
                        }
                    },
                    http,
                    manager,
                ) { true }
            // Capture one account before either phase starts; later UI selection is irrelevant.
            val accountId = active
            val host = "https://${active.lowercase()}.example/"
            val token = "token-$active"
            val user = UserSnapshot(name = "users/$active")
            val job =
                async {
                    downloadMemoHistory(api, accountId, user, memos, includeExplore = false)
                    media.preloadAttachments(host, token, accountId, listOf(attachment to "memos/1"))
                }
            runCurrent()
            active = "B"
            release.complete(Unit)
            val result = job.await()
            assertEquals(0, result.failed)
            assertEquals("a.example", downloads.single().url.host)
            assertEquals("Bearer token-A", downloads.single().header("Authorization"))
            assertEquals("A", binaries.single().accountId)
            assertTrue(memos.getQuery("A", "USER:history")!!.incrementalCursor > 0L)
            assertNull(memos.getQuery("B", "USER:history"))
            database.close()
        }
}
