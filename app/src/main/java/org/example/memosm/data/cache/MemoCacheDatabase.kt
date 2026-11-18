package org.example.memosm.data.cache

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.example.memosm.api.GsonProvider
import org.example.memosm.data.audit.AuditDatabase
import org.example.memosm.data.audit.SyncAuditLogger
import org.example.memosm.data.media.AttachmentUpload
import org.example.memosm.data.media.AttachmentUploadDao
import org.example.memosm.data.media.CachedAttachment
import org.example.memosm.data.media.CachedAttachmentDao
import org.example.memosm.data.media.CachedAttachmentMeta
import org.example.memosm.data.media.CachedAttachmentMetaDao
import org.example.memosm.data.media.toCachedMetadata
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.PendingOpDao
import org.example.memosm.model.Memo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Room database for caching memos locally, the offline write queue
 * and downloaded attachment metadata. The sync audit log lives in the
 * separate AuditDatabase so it survives cache-database corruption.
 */
@Database(
    entities = [
        MemoEntity::class,
        MemoMembership::class,
        CachedQuery::class,
        PendingOp::class,
        CachedAttachment::class,
        AttachmentUpload::class,
        CachedAttachmentMeta::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class MemoCacheDatabase : RoomDatabase() {
    abstract fun memoDao(): MemoDao

    abstract fun pendingOpDao(): PendingOpDao

    abstract fun cachedAttachmentDao(): CachedAttachmentDao

    abstract fun attachmentUploadDao(): AttachmentUploadDao

    abstract fun cachedAttachmentMetaDao(): CachedAttachmentMetaDao

    companion object {
        @Volatile
        private var INSTANCE: MemoCacheDatabase? = null

        fun getInstance(context: Context): MemoCacheDatabase =
            INSTANCE ?: synchronized(this) {
                // Double-checked locking: another thread may have finished
                // the build (and any recovery) while we waited on the lock.
                INSTANCE ?: openVerified(context)
            }

        /**
         * Builds the database and forces it open so a corrupted file fails
         * here instead of on first use. Corruption is normally absorbed by
         * androidx.sqlite's open helper, which deletes and recreates the file
         * without throwing; the [QuarantiningOpenHelperFactory] installed by
         * [build] intercepts that path (Callback.onCorruption) so the damaged
         * files are renamed aside (never deleted) before the fresh database
         * is created, and the factory's audit hook writes the
         * DATABASE_CORRUPTED row into the separate AuditDatabase — for
         * corruption detected at runtime just as for corruption detected
         * during this eager open. The catch below remains as the fail-closed
         * path for corruption that still surfaces as an exception and for any
         * other open error. A failure never leaves a half-built [INSTANCE]
         * behind.
         */
        private fun openVerified(context: Context): MemoCacheDatabase =
            try {
                build(context).also { openOrThrow(it) }
            } catch (t: Throwable) {
                if (!CorruptDatabaseRecovery.isCorruption(t)) throw t
                Log.e(TAG, "Cache database corrupted; moving files aside and recreating", t)
                val appContext = context.applicationContext
                if (CorruptDatabaseRecovery.quarantineDatabaseFiles(appContext, DATABASE_NAME)) {
                    // The factory's onCorruption hook did not run for this
                    // failure, so record the audit row here. The write is
                    // guarded against duplicates by recordCorruptionAudit.
                    recordCorruptionAudit(appContext)
                }
                build(context).also { openOrThrow(it) }
            }.also { INSTANCE = it }

        /** Set once the cache-DB corruption audit row has been written; prevents double-recording. */
        private val corruptionAuditRecorded = AtomicBoolean(false)

        /**
         * Best-effort write of the DATABASE_CORRUPTED/RECREATED audit row into
         * the separate AuditDatabase, where it survives the cache database's
         * quarantine-and-recreate. Runs at most once per process (riding the
         * same first-time-only guard as the quarantine via the callers) and
         * never throws into the corruption path. May be invoked from the main
         * thread (eager open in Application.onCreate), hence the short
         * blocking IO write.
         */
        private fun recordCorruptionAudit(context: Context) {
            if (!corruptionAuditRecorded.compareAndSet(false, true)) return
            try {
                runBlocking(Dispatchers.IO) {
                    SyncAuditLogger(AuditDatabase.getInstance(context).syncAuditDao()).record(
                        accountId = null,
                        event = "DATABASE_CORRUPTED",
                        outcome = "RECREATED",
                        detailCode = "cache_db",
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not record database corruption event", t)
            }
        }

        private fun build(context: Context): MemoCacheDatabase =
            Room
                .databaseBuilder(
                    context.applicationContext,
                    MemoCacheDatabase::class.java,
                    DATABASE_NAME,
                )
                // androidx.sqlite recreates a corrupted database silently (the
                // exception never reaches openVerified). This factory intercepts
                // Callback.onCorruption so the damaged files are quarantined
                // before the framework's delete-and-recreate recovery runs, then
                // records the audit row into the separate AuditDatabase.
                .openHelperFactory(
                    QuarantiningOpenHelperFactory(
                        context.applicationContext,
                        DATABASE_NAME,
                        onQuarantined = {
                            Log.e(TAG, "Cache database was corrupted; files moved aside and database recreated")
                            recordCorruptionAudit(context.applicationContext)
                        },
                    ),
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                // The eager open below runs on the main thread (Application.onCreate
                // -> getInstance). allowMainThreadQueries() makes that one-time probe
                // legal; actual DAO suspend functions still execute off the main thread.
                .allowMainThreadQueries()
                // The outbox contains unsent user data. Never erase it merely
                // because a migration is missing; fail closed so recovery or
                // export remains possible instead of silently losing writes.
                .build()

        /** Room opens lazily; run a trivial read so open/migration errors surface now. */
        private fun openOrThrow(database: MemoCacheDatabase) {
            database.query("SELECT 1", null).use { it.moveToFirst() }
        }

        val ALL_MIGRATIONS: Array<Migration>
            get() = arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)

        const val DATABASE_NAME = "memo_cache_database"
        private const val TAG = "MemoCacheDatabase"

        /** Normalize cached bodies without discarding the outbox or any staged uploads. */
        val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS memo_entities (
                    accountId TEXT NOT NULL, localId TEXT NOT NULL, remoteName TEXT,
                    serverJson TEXT, visibleJson TEXT NOT NULL, cachedAt INTEGER NOT NULL,
                    content TEXT NOT NULL, createTime INTEGER NOT NULL, displayTime INTEGER NOT NULL, updateTime INTEGER NOT NULL,
                    visibility TEXT NOT NULL, state TEXT NOT NULL, tags TEXT NOT NULL,
                    pinned INTEGER NOT NULL, parentId TEXT, deleted INTEGER NOT NULL,
                    serverVerified INTEGER NOT NULL, PRIMARY KEY(accountId,localId))""",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_memo_entities_accountId_remoteName ON memo_entities(accountId,remoteName)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_memo_entities_accountId_createTime ON memo_entities(accountId,createTime)")
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS memo_memberships (
                    accountId TEXT NOT NULL, queryKey TEXT NOT NULL, localId TEXT NOT NULL,
                    listType TEXT NOT NULL, parentName TEXT, position INTEGER NOT NULL,
                    generation INTEGER NOT NULL, cachedAt INTEGER NOT NULL,
                    PRIMARY KEY(accountId,queryKey,localId))""",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_memo_memberships_accountId_localId ON memo_memberships(accountId,localId)")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_memo_memberships_accountId_listType ON memo_memberships(accountId,listType)",
                    )
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS memo_queries (
                    accountId TEXT NOT NULL, `key` TEXT NOT NULL, listType TEXT NOT NULL,
                    parentName TEXT, loaded INTEGER NOT NULL, complete INTEGER NOT NULL,
                    nextPageToken TEXT, generation INTEGER NOT NULL,
                    lastFullSnapshotAt INTEGER NOT NULL, incrementalCursor INTEGER NOT NULL,
                    PRIMARY KEY(accountId,`key`))""",
                    )
                    // A memo may have stale copies in several old lists. Pick one deterministically.
                    db.execSQL(
                        """INSERT INTO memo_entities
                    SELECT c.accountId,c.name,CASE WHEN c.name LIKE 'memos/%' THEN c.name ELSE NULL END,
                    c.memoJson,c.memoJson,c.cachedAt,c.content,c.createTime,c.createTime,c.updateTime,c.visibility,
                    c.state,c.tags,c.pinned,COALESCE(c.parentName,(SELECT parent.parentName FROM cached_memos parent WHERE parent.accountId=c.accountId AND parent.name=c.name AND parent.parentName IS NOT NULL ORDER BY parent.cachedAt DESC LIMIT 1)),0,
                    CASE WHEN EXISTS(SELECT 1 FROM pending_ops p WHERE p.accountId=c.accountId AND p.memoName=c.name) THEN 0 ELSE 1 END
                    FROM cached_memos c WHERE NOT EXISTS (
                        SELECT 1 FROM cached_memos newer WHERE newer.accountId=c.accountId AND newer.name=c.name AND
                        (newer.updateTime>c.updateTime OR (newer.updateTime=c.updateTime AND newer.cachedAt>c.cachedAt) OR
                        (newer.updateTime=c.updateTime AND newer.cachedAt=c.cachedAt AND newer.listType<c.listType)))""",
                    )
                    db.execSQL(
                        """INSERT OR REPLACE INTO memo_memberships
                    SELECT accountId,listType || CASE WHEN parentName IS NULL THEN '' ELSE ':' || parentName END,
                    name,listType,parentName,displayOrder,0,cachedAt FROM cached_memos""",
                    )
                    db.execSQL(
                        """INSERT OR IGNORE INTO memo_queries
                    SELECT accountId,queryKey,listType,parentName,0,0,NULL,0,0,0 FROM memo_memberships""",
                    )
                    db.execSQL("ALTER TABLE pending_ops ADD COLUMN status TEXT NOT NULL DEFAULT 'PENDING'")
                    db.execSQL("ALTER TABLE pending_ops ADD COLUMN conflictServerJson TEXT")
                    db.execSQL("ALTER TABLE pending_ops ADD COLUMN sequence INTEGER NOT NULL DEFAULT 0")
                    db.execSQL(
                        """UPDATE pending_ops SET sequence=(SELECT COUNT(*)+1 FROM pending_ops previous
                    WHERE previous.accountId=pending_ops.accountId AND (previous.createdAt<pending_ops.createdAt OR
                    (previous.createdAt=pending_ops.createdAt AND previous.id<pending_ops.id)))""",
                    )
                    db.execSQL("UPDATE pending_ops SET status='REJECTED' WHERE permanentlyFailed=1")
                    db.execSQL("ALTER TABLE attachment_uploads ADD COLUMN serverJson TEXT")
                    db.execSQL("ALTER TABLE attachment_uploads ADD COLUMN permanentlyFailed INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE attachment_uploads ADD COLUMN authRequired INTEGER NOT NULL DEFAULT 0")
                    db.query("SELECT accountId,localId,visibleJson,createTime FROM memo_entities").use { cursor ->
                        while (cursor.moveToNext()) {
                            val memo = runCatching { GsonProvider.gson.fromJson(cursor.getString(2), Memo::class.java) }.getOrNull()
                            db.execSQL(
                                "UPDATE memo_entities SET displayTime=? WHERE accountId=? AND localId=?",
                                arrayOf<Any?>(
                                    (memo?.displayTime ?: memo?.createTime)?.toEpochMilliseconds() ?: cursor.getLong(3),
                                    cursor.getString(0),
                                    cursor.getString(1),
                                ),
                            )
                        }
                    }
                    recoverPendingEntities(db)
                    reprojectPendingEntities(db)
                    backfillAttachmentMetadata(db)
                    db.execSQL("DROP TABLE cached_memos")
                }
            }

        private fun backfillAttachmentMetadata(db: SupportSQLiteDatabase) {
            db.query("SELECT accountId,localId,visibleJson FROM memo_entities").use { cursor ->
                while (cursor.moveToNext()) {
                    val memo =
                        runCatching {
                            GsonProvider.gson.fromJson(cursor.getString(2), Memo::class.java)
                        }.getOrNull() ?: continue
                    memo.attachments.orEmpty().filter { it.name != null }.forEach { attachment ->
                        val row = attachment.toCachedMetadata(cursor.getString(0), cursor.getString(1))
                        db.execSQL(
                            "INSERT OR IGNORE INTO cached_attachment_meta VALUES (?,?,?,?,?,?,?,?)",
                            arrayOf<Any?>(
                                row.accountId,
                                row.attachmentName,
                                row.filename,
                                row.type,
                                row.size,
                                row.createTime,
                                row.memoName,
                                row.attachmentJson,
                            ),
                        )
                    }
                }
            }
        }

        private fun recoverPendingEntities(db: SupportSQLiteDatabase) {
            db
                .query(
                    "SELECT accountId,memoName,parentName,payloadJson,type FROM pending_ops WHERE payloadJson IS NOT NULL AND type IN ('CREATE','UPDATE','COMMENT_CREATE') ORDER BY sequence,createdAt,id",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val accountId = cursor.getString(0)
                        val name = cursor.getString(1) ?: continue
                        val parent = cursor.getString(2)
                        val memo =
                            runCatching { GsonProvider.gson.fromJson(cursor.getString(3), Memo::class.java).copy(name = name) }.getOrNull()
                                ?: continue
                        val value = MemoEntity.fromMemo(accountId, memo, name, parent, remote = false)
                        db.execSQL(
                            """INSERT OR IGNORE INTO memo_entities
                        (accountId,localId,remoteName,serverJson,visibleJson,cachedAt,content,createTime,displayTime,updateTime,visibility,state,tags,pinned,parentId,deleted,serverVerified)
                        VALUES (?,?,NULL,NULL,?,?,?,?,?,?,?,?,?,?,?,0,0)""",
                            arrayOf<Any?>(
                                accountId,
                                name,
                                value.visibleJson,
                                value.cachedAt,
                                value.content,
                                value.createTime,
                                value.displayTime,
                                value.updateTime,
                                value.visibility,
                                value.state,
                                value.tags,
                                if (value.pinned) 1 else 0,
                                parent,
                            ),
                        )
                        val type =
                            if (parent != null) {
                                "COMMENT"
                            } else if (value.state == "ARCHIVED") {
                                "ARCHIVED"
                            } else {
                                "USER"
                            }
                        val key = type + (parent?.let { ":$it" } ?: "")
                        db.execSQL(
                            "INSERT OR IGNORE INTO memo_memberships VALUES (?,?,?,?,?,0,0,?)",
                            arrayOf<Any?>(accountId, key, name, type, parent, value.cachedAt),
                        )
                        db.execSQL(
                            "INSERT OR IGNORE INTO memo_queries VALUES (?,?,?,?,0,0,NULL,0,0,0)",
                            arrayOf<Any?>(accountId, key, type, parent),
                        )
                    }
                }
        }

        private fun reprojectPendingEntities(db: SupportSQLiteDatabase) {
            val groups = linkedMapOf<Pair<String, String>, MutableList<PendingOp>>()
            db
                .query(
                    "SELECT id,accountId,type,memoName,parentName,payloadJson,updateMask,baseUpdateTime,createdAt,sequence FROM pending_ops ORDER BY sequence,createdAt,id",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(3) ?: continue
                        val op =
                            PendingOp(
                                id = cursor.getString(0),
                                accountId = cursor.getString(1),
                                type = cursor.getString(2),
                                memoName = name,
                                parentName = cursor.getString(4),
                                payloadJson = cursor.getString(5),
                                updateMask = cursor.getString(6),
                                baseUpdateTime = cursor.getString(7),
                                createdAt = cursor.getLong(8),
                                sequence = cursor.getLong(9),
                            )
                        groups.getOrPut(op.accountId to name) { mutableListOf() } += op
                    }
                }
            groups.forEach { (identity, ops) ->
                val (accountId, name) = identity
                val json =
                    db
                        .query(
                            "SELECT visibleJson FROM memo_entities WHERE accountId=? AND localId=?",
                            arrayOf<Any?>(accountId, name),
                        ).use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: return@forEach
                // Unreadable legacy payloads remain in the outbox for review, never acknowledged.
                val projected =
                    runCatching {
                        org.example.memosm.data.sync
                            .projectMemo(GsonProvider.gson.fromJson(json, Memo::class.java), ops)
                    }
                if (projected.isFailure) return@forEach
                val memo = projected.getOrNull()
                if (memo ==
                    null
                ) {
                    db.execSQL("UPDATE memo_entities SET deleted=1 WHERE accountId=? AND localId=?", arrayOf<Any?>(accountId, name))
                } else {
                    val value = MemoEntity.fromMemo(accountId, memo, name, remote = false)
                    db.execSQL(
                        "UPDATE memo_entities SET visibleJson=?,content=?,createTime=?,displayTime=?,updateTime=?,visibility=?,state=?,tags=?,pinned=?,deleted=0 WHERE accountId=? AND localId=?",
                        arrayOf<Any?>(
                            value.visibleJson,
                            value.content,
                            value.createTime,
                            value.displayTime,
                            value.updateTime,
                            value.visibility,
                            value.state,
                            value.tags,
                            if (value.pinned) 1 else 0,
                            accountId,
                            name,
                        ),
                    )
                }
            }
        }

        /**
         * v6 -> v7: move the sync audit log out of this database into the
         * separate AuditDatabase so the audit trail survives cache-database
         * corruption. Pre-v7 audit history is intentionally not carried over:
         * it is a bounded diagnostic log, and the corruption event that
         * motivates the move is recorded fresh into AuditDatabase.
         */
        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS sync_audit_log")
                }
            }

        /**
         * v7 -> v8: add the attachment-metadata cache used by the offline
         * Resources page. Pure metadata (no binaries), so the table starts
         * empty and is backfilled from cached memos on first offline read.
         */
        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS cached_attachment_meta (
                            accountId TEXT NOT NULL,
                            attachmentName TEXT NOT NULL,
                            filename TEXT NOT NULL,
                            type TEXT NOT NULL,
                            size INTEGER NOT NULL,
                            createTime INTEGER NOT NULL,
                            memoName TEXT,
                            attachmentJson TEXT NOT NULL,
                            PRIMARY KEY(accountId, attachmentName)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_cached_attachment_meta_accountId_createTime ON cached_attachment_meta(accountId, createTime)",
                    )
                }
            }
        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS attachment_uploads (
                            id TEXT NOT NULL PRIMARY KEY,
                            accountId TEXT NOT NULL,
                            clientId TEXT NOT NULL,
                            localPath TEXT NOT NULL,
                            filename TEXT NOT NULL,
                            mimeType TEXT NOT NULL,
                            size INTEGER NOT NULL,
                            createdAt INTEGER NOT NULL,
                            attemptCount INTEGER NOT NULL,
                            lastError TEXT,
                            lastAttemptAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_attachment_uploads_accountId ON attachment_uploads(accountId)")
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_attachment_uploads_accountId_clientId ON attachment_uploads(accountId, clientId)",
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS sync_audit_log (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            occurredAt INTEGER NOT NULL,
                            accountHash TEXT NOT NULL,
                            event TEXT NOT NULL,
                            operation TEXT,
                            outcome TEXT NOT NULL,
                            targetHash TEXT,
                            detailCode TEXT
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_sync_audit_log_accountHash_occurredAt ON sync_audit_log(accountHash, occurredAt)",
                    )
                }
            }

        /**
         * v4 -> v5: add retry bookkeeping and isolate cached attachment names by account.
         */
        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS pending_ops_new (
                            id TEXT NOT NULL PRIMARY KEY,
                            accountId TEXT NOT NULL,
                            type TEXT NOT NULL,
                            memoName TEXT,
                            parentName TEXT,
                            payloadJson TEXT,
                            updateMask TEXT,
                            baseUpdateTime TEXT,
                            createdAt INTEGER NOT NULL,
                            attemptCount INTEGER NOT NULL,
                            lastError TEXT,
                            lastAttemptAt INTEGER NOT NULL,
                            permanentlyFailed INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO pending_ops_new (id, accountId, type, memoName, parentName, payloadJson, updateMask, baseUpdateTime, createdAt, attemptCount, lastError, lastAttemptAt, permanentlyFailed)
                        SELECT id, accountId, type, memoName, parentName, payloadJson, updateMask, baseUpdateTime, createdAt, attemptCount, lastError, 0, 0 FROM pending_ops
                        """.trimIndent(),
                    )
                    db.execSQL("DROP TABLE pending_ops")
                    db.execSQL("ALTER TABLE pending_ops_new RENAME TO pending_ops")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_ops_accountId ON pending_ops(accountId)")

                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS cached_attachments_new (
                            accountId TEXT NOT NULL,
                            attachmentName TEXT NOT NULL,
                            memoName TEXT NOT NULL,
                            url TEXT NOT NULL,
                            localPath TEXT NOT NULL,
                            size INTEGER NOT NULL,
                            downloadedAt INTEGER NOT NULL,
                            lastAccessedAt INTEGER NOT NULL,
                            PRIMARY KEY(accountId, attachmentName)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO cached_attachments_new (accountId, attachmentName, memoName, url, localPath, size, downloadedAt, lastAccessedAt)
                        SELECT accountId, attachmentName, memoName, url, localPath, size, downloadedAt, lastAccessedAt FROM cached_attachments
                        """.trimIndent(),
                    )
                    db.execSQL("DROP TABLE cached_attachments")
                    db.execSQL("ALTER TABLE cached_attachments_new RENAME TO cached_attachments")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_cached_attachments_accountId_lastAccessedAt ON cached_attachments(accountId, lastAccessedAt)",
                    )
                }
            }

        /**
         * v3 -> v4: add the (accountId, createTime) index used by offline
         * search sorting and pagination, avoiding a full-table scan/order.
         */
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_cached_memos_time ON cached_memos(accountId, createTime)",
                    )
                }
            }

        /**
         * v2 -> v3: rebuild cached_memos with a composite primary key
         * (accountId, listType, name) so the same memo can live in multiple
         * lists, add indexed search/sync columns, backfill them from the
         * stored memoJson, and add the pending_ops + cached_attachments tables.
         */
        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS cached_memos_new (
                            name TEXT NOT NULL,
                            accountId TEXT NOT NULL,
                            listType TEXT NOT NULL,
                            memoJson TEXT NOT NULL,
                            displayOrder INTEGER NOT NULL,
                            cachedAt INTEGER NOT NULL,
                            content TEXT NOT NULL DEFAULT '',
                            createTime INTEGER NOT NULL DEFAULT 0,
                            updateTime INTEGER NOT NULL DEFAULT 0,
                            visibility TEXT NOT NULL DEFAULT '',
                            state TEXT NOT NULL DEFAULT '',
                            tags TEXT NOT NULL DEFAULT '',
                            pinned INTEGER NOT NULL DEFAULT 0,
                            parentName TEXT,
                            PRIMARY KEY(accountId, listType, name)
                        )
                        """.trimIndent(),
                    )

                    db.execSQL(
                        """
                        INSERT INTO cached_memos_new (name, accountId, listType, memoJson, displayOrder, cachedAt)
                        SELECT name, accountId, listType, memoJson, displayOrder, cachedAt FROM cached_memos
                        """.trimIndent(),
                    )

                    // Backfill indexed columns from the stored memoJson so cached
                    // data remains searchable even before the next online fetch.
                    val gson = GsonProvider.gson
                    db.query("SELECT name, accountId, listType, memoJson FROM cached_memos_new").use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(0)
                            val accountId = cursor.getString(1)
                            val listType = cursor.getString(2)
                            val json = cursor.getString(3)
                            val memo =
                                try {
                                    gson.fromJson(json, Memo::class.java)
                                } catch (e: Exception) {
                                    null
                                } ?: continue
                            db.execSQL(
                                "UPDATE cached_memos_new SET content = ?, createTime = ?, updateTime = ?, " +
                                    "visibility = ?, state = ?, tags = ?, pinned = ? " +
                                    "WHERE name = ? AND accountId = ? AND listType = ?",
                                arrayOf<Any>(
                                    memo.content.orEmpty(),
                                    memo.createTime?.toEpochMilliseconds() ?: 0L,
                                    memo.updateTime?.toEpochMilliseconds() ?: 0L,
                                    memo.visibility.name,
                                    memo.state?.name ?: "",
                                    gson.toJson(memo.tags ?: emptyList<String>()),
                                    if (memo.pinned == true) 1 else 0,
                                    name,
                                    accountId,
                                    listType,
                                ),
                            )
                        }
                    }

                    db.execSQL("DROP TABLE cached_memos")
                    db.execSQL("ALTER TABLE cached_memos_new RENAME TO cached_memos")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_memos_accountId ON cached_memos(accountId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_memos_search ON cached_memos(accountId, content)")

                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS pending_ops (
                            id TEXT NOT NULL PRIMARY KEY,
                            accountId TEXT NOT NULL,
                            type TEXT NOT NULL,
                            memoName TEXT,
                            parentName TEXT,
                            payloadJson TEXT,
                            updateMask TEXT,
                            baseUpdateTime TEXT,
                            createdAt INTEGER NOT NULL,
                            attemptCount INTEGER NOT NULL,
                            lastError TEXT
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_ops_accountId ON pending_ops(accountId)")

                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS cached_attachments (
                            attachmentName TEXT NOT NULL PRIMARY KEY,
                            accountId TEXT NOT NULL,
                            memoName TEXT NOT NULL,
                            url TEXT NOT NULL,
                            localPath TEXT NOT NULL,
                            size INTEGER NOT NULL DEFAULT 0,
                            downloadedAt INTEGER NOT NULL DEFAULT 0,
                            lastAccessedAt INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_attachments_accountId ON cached_attachments(accountId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_attachments_lru ON cached_attachments(accountId, lastAccessedAt)")
                }
            }
    }
}
