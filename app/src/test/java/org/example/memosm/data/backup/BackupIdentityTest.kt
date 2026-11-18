package org.example.memosm.data.backup

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.example.memosm.api.GsonProvider
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.cache.seedMemo
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.PendingOpType
import org.example.memosm.model.Account
import org.example.memosm.model.Memo
import org.example.memosm.model.UserSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BackupIdentityTest {
    private val account =
        Account(
            id = "A",
            hostUrl = "https://example.invalid",
            accessToken = "token",
            isActive = true,
            user = UserSnapshot(name = "users/1"),
        )

    private fun mutation(
        memo: Memo,
        type: PendingOpType,
        parent: String? = null,
    ): PendingOp =
        PendingOp.new(
            account.id,
            type,
            memo.name,
            parentName = parent,
            payloadJson = GsonProvider.gson.toJson(memo),
            updateMask = "content",
        )

    private suspend fun restoreCache(
        source: BackupTestFixture,
        target: BackupTestFixture,
    ) {
        val archive = source.service.export(BackupSelection(setOf(account.id), setOf(BackupCategory.CACHE))).getOrThrow()
        target.service.inspect(archive).getOrThrow().use { backup ->
            target.service.restore(backup, RestoreSelection(setOf(account.id), setOf(BackupCategory.CACHE))).getOrThrow()
        }
    }

    @Test fun acknowledgedLocalIdentitiesExportActualServerNames() =
        runBlocking {
            BackupTestFixture().use { source ->
                BackupTestFixture().use { target ->
                    source.settings.saveAccounts(listOf(account))
                    target.settings.saveAccounts(listOf(account))
                    val cache = MemoCacheRepository(source.database.memoDao())
                    val parent = Memo(name = "offline-parent", content = "parent")
                    val create = mutation(parent, PendingOpType.CREATE)
                    cache.commitMutation(create, parent)
                    cache.acknowledge(create, parent.copy(name = "memos/server-parent"))
                    val child = Memo(name = "offline-child", content = "comment")
                    val comment = mutation(child, PendingOpType.COMMENT_CREATE, parent.name)
                    cache.commitMutation(comment, child, CacheListType.COMMENT)
                    cache.acknowledge(comment, child.copy(name = "memos/server-child", parent = "memos/server-parent"))

                    restoreCache(source, target)

                    assertEquals(
                        "memos/server-parent",
                        target.database
                            .memoDao()
                            .getMemos("A", "USER")
                            .single()
                            .name,
                    )
                    val restored =
                        target.database
                            .memoDao()
                            .getMemos("A", "COMMENT")
                            .filter { it.parentName == "memos/server-parent" }
                            .single()
                    assertEquals("memos/server-child", restored.name)
                    assertTrue(
                        target.database
                            .pendingOpDao()
                            .getOps("A")
                            .isEmpty(),
                    )
                }
            }
        }

    @Test fun restoringCachePreservesPendingProjectionAndItsOriginalServerBase() =
        runBlocking {
            BackupTestFixture().use { source ->
                BackupTestFixture().use { target ->
                    source.settings.saveAccounts(listOf(account))
                    target.settings.saveAccounts(listOf(account))
                    val sourceCache = MemoCacheRepository(source.database.memoDao())
                    sourceCache.seedMemo("A", Memo(name = "memos/server", content = "backup cache"))
                    val targetCache = MemoCacheRepository(target.database.memoDao())
                    val local = Memo(name = "offline-local", content = "original server")
                    val create = mutation(local, PendingOpType.CREATE)
                    targetCache.commitMutation(create, local)
                    targetCache.acknowledge(create, local.copy(name = "memos/server"))
                    val edit = local.copy(content = "pending local edit")
                    val pending = mutation(edit, PendingOpType.UPDATE)
                    targetCache.commitMutation(pending, edit)

                    restoreCache(source, target)

                    assertEquals("pending local edit", targetCache.getCachedMemo("A", "offline-local")?.content)
                    assertEquals(
                        "original server",
                        target.database
                            .memoDao()
                            .getEntity("A", "offline-local")
                            ?.serverMemo()
                            ?.content,
                    )
                    assertEquals(
                        listOf(pending.id),
                        target.database
                            .pendingOpDao()
                            .getOps("A")
                            .map { it.id },
                    )
                    targetCache.discardMutation(pending)
                    assertEquals("original server", targetCache.getCachedMemo("A", "offline-local")?.content)
                }
            }
        }
}
