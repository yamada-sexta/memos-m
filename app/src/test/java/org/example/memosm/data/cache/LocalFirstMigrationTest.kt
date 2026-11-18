package org.example.memosm.data.cache

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.example.memosm.api.GsonProvider
import org.example.memosm.model.Attachment
import org.example.memosm.model.Memo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** Real SQLite upgrades followed by Room's latest-schema validation, without an emulator. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LocalFirstMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun oldDatabase(
        name: String,
        version: Int,
    ): SupportSQLiteOpenHelper {
        context.deleteDatabase(name)
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            val resource = "org.example.memosm.data.cache.MemoCacheDatabase/$version.json"
                            val schema =
                                requireNotNull(javaClass.classLoader?.getResourceAsStream(resource)) { "Missing schema $resource" }
                                    .bufferedReader()
                                    .use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("database") }
                            schema.getAsJsonArray("entities").forEach { element ->
                                val entity = element.asJsonObject
                                val table = entity.get("tableName").asString
                                db.execSQL(entity.get("createSql").asString.replace("\${TABLE_NAME}", table))
                                entity.getAsJsonArray("indices")?.forEach { index ->
                                    db.execSQL(
                                        index.asJsonObject
                                            .get("createSql")
                                            .asString
                                            .replace("\${TABLE_NAME}", table),
                                    )
                                }
                            }
                            schema.getAsJsonArray("setupQueries").forEach { db.execSQL(it.asString) }
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = error("Unexpected fixture upgrade")
                    },
                ).build(),
        )
    }

    private fun currentDatabase(name: String) =
        Room
            .databaseBuilder(context, MemoCacheDatabase::class.java, name)
            .addMigrations(*MemoCacheDatabase.ALL_MIGRATIONS)
            .build()

    @Test fun everySupportedHistoricalVersionUpgradesToTheLatestSchema() =
        runBlocking {
            for (version in 2..8) {
                val name = "migration-from-$version"
                oldDatabase(name, version).use { it.writableDatabase }
                val current = currentDatabase(name)
                try {
                    assertEquals(0, current.memoDao().getCountForAccount("account"))
                    assertEquals(9, current.openHelper.readableDatabase.version)
                } finally {
                    current.close()
                    context.deleteDatabase(name)
                }
            }
        }

    @Test fun earliestCacheBackfillAndUnreadableRowsSurviveNormalization() =
        runBlocking {
            val name = "migration-v2-data"
            val created = "2024-01-02T03:04:05Z"
            val updated = "2024-02-03T04:05:06Z"
            oldDatabase(name, 2).use { old ->
                val db = old.writableDatabase
                val json =
                    """
                    {"name":"memos/valid","content":"hello","createTime":"$created","updateTime":"$updated",
                     "state":"NORMAL","visibility":"PUBLIC","tags":["tag"],"pinned":true}
                    """.trimIndent()
                db.execSQL("INSERT INTO cached_memos VALUES (?,?,?,?,?,?)", arrayOf<Any?>("memos/valid", "A", "USER", json, 0, 42L))
                db.execSQL(
                    "INSERT INTO cached_memos VALUES (?,?,?,?,?,?)",
                    arrayOf<Any?>("memos/broken", "A", "USER", "{not valid json", 1, 43L),
                )
            }
            val current = currentDatabase(name)
            try {
                val valid = requireNotNull(current.memoDao().getEntity("A", "memos/valid"))
                assertEquals("hello", valid.content)
                assertEquals(Instant.parse(created).toEpochMilliseconds(), valid.createTime)
                assertEquals(Instant.parse(updated).toEpochMilliseconds(), valid.updateTime)
                assertTrue(valid.pinned)
                assertEquals("[\"tag\"]", valid.tags)
                assertEquals("{not valid json", current.memoDao().getEntity("A", "memos/broken")?.visibleJson)
            } finally {
                current.close()
                context.deleteDatabase(name)
            }
        }

    @Test fun normalizationPreservesMembershipsPendingWorkAndStagedUploads() =
        runBlocking {
            val name = "local-first-migration"
            oldDatabase(name, 8).use { old ->
                val db = old.writableDatabase

                fun row(
                    account: String,
                    type: String,
                    content: String,
                    update: Long,
                    parent: String?,
                ) {
                    val memo =
                        Memo(
                            name = "memos/comment",
                            content = content,
                            parent = parent,
                            attachments =
                                listOf(
                                    Attachment(name = "attachments/cached", filename = "cached.png", type = "image/png"),
                                    Attachment(filename = "unsent.png", type = "image/png", clientId = "local-only"),
                                ),
                        )
                    db.execSQL(
                        "INSERT INTO cached_memos VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>(
                            "memos/comment",
                            account,
                            type,
                            GsonProvider.gson.toJson(memo),
                            0,
                            update,
                            content,
                            1L,
                            update,
                            "PRIVATE",
                            "NORMAL",
                            "[]",
                            0,
                            parent,
                        ),
                    )
                }
                row("A", "COMMENT", "old", 10, "memos/parent")
                row("A", "USER", "new", 20, null)
                row("B", "COMMENT", "other account", 30, "memos/parent")
                val pending = GsonProvider.gson.toJson(Memo(name = "offline-created", content = "unsent"))
                db.execSQL(
                    "INSERT INTO pending_ops VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>("pending", "A", "CREATE", "offline-created", null, pending, null, null, 100L, 2, "timeout", 1L, 0),
                )
                db.execSQL(
                    "INSERT INTO attachment_uploads VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>("upload", "A", "client-id", "/private/staged", "photo.png", "image/png", 42L, 100L, 8, "timeout", 1L),
                )
            }
            val current = currentDatabase(name)
            try {
                val dao = current.memoDao()
                val canonical = requireNotNull(dao.getEntity("A", "memos/comment"))
                assertEquals("new", canonical.visibleMemo().content)
                assertEquals("memos/parent", canonical.parentId)
                assertEquals("other account", dao.getEntity("B", "memos/comment")?.visibleMemo()?.content)
                assertEquals(1, dao.getMemos("A", "COMMENT").size)
                assertEquals(setOf("memos/comment", "offline-created"), dao.getMemos("A", "USER").map { it.name }.toSet())
                assertEquals("unsent", dao.getEntity("A", "offline-created")?.visibleMemo()?.content)
                assertNull(dao.getEntity("A", "offline-created")?.remoteName)
                val op = current.pendingOpDao().getOps("A").single()
                assertEquals("pending", op.id)
                assertEquals(2, op.attemptCount)
                assertEquals(
                    "/private/staged",
                    current
                        .attachmentUploadDao()
                        .getForAccount("A")
                        .single()
                        .localPath,
                )
                assertEquals(
                    "attachments/cached",
                    current
                        .cachedAttachmentMetaDao()
                        .getAll("A")
                        .single()
                        .attachmentName,
                )
                assertFalse(requireNotNull(dao.getQuery("A", "COMMENT:memos/parent")).complete)
                current.openHelper.readableDatabase
                    .query(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='cached_memos'",
                    ).use {
                        assertTrue(it.moveToFirst())
                        assertEquals(0, it.getInt(0))
                    }
            } finally {
                current.close()
                context.deleteDatabase(name)
            }
        }
}
