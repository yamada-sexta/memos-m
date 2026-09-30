package org.example.memosm.data.audit

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.example.memosm.data.cache.QuarantiningOpenHelperFactory

/**
 * Room database holding the sync audit log ([SyncAuditEntry]) separately from
 * the cache/outbox database. The audit trail must survive cache-database
 * corruption: when the cache file is damaged it is quarantined and recreated
 * empty, so an audit table living inside it would lose all prior history.
 *
 * This database intentionally has no dependency on MemoCacheDatabase (in
 * either direction of initialization): recording a cache-DB corruption event
 * requires AuditDatabase.getInstance, which must never route back into the
 * cache database.
 */
@Database(
    entities = [SyncAuditEntry::class],
    version = 1,
    exportSchema = true
)
abstract class AuditDatabase : RoomDatabase() {

    abstract fun syncAuditDao(): SyncAuditDao

    companion object {
        @Volatile
        private var INSTANCE: AuditDatabase? = null

        fun getInstance(context: Context): AuditDatabase {
            return INSTANCE ?: synchronized(this) {
                // Double-checked locking: another thread may have finished
                // the build while we waited on the lock.
                INSTANCE ?: build(context).also {
                    // Room opens lazily; run a trivial read so open errors
                    // surface here instead of on first use. This probe runs on
                    // the caller's thread, which can be the main thread during
                    // Application.onCreate or a cache-DB corruption callback,
                    // hence allowMainThreadQueries() below; actual DAO suspend
                    // functions still execute off the main thread.
                    it.query("SELECT 1", null).use { cursor -> cursor.moveToFirst() }
                    INSTANCE = it
                }
            }
        }

        private fun build(context: Context): AuditDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AuditDatabase::class.java,
                DATABASE_NAME
            )
            // Reuse the quarantine behavior so a corrupted audit file is moved
            // aside (never deleted) before androidx.sqlite recreates it. No
            // corruption-audit callback is passed: writing a corruption event
            // requires this very database, which may be the broken one.
            .openHelperFactory(QuarantiningOpenHelperFactory(context.applicationContext, DATABASE_NAME))
            // See the eager-open probe above for why main-thread queries are
            // allowed for this database.
            .allowMainThreadQueries()
            .build()
        }

        const val DATABASE_NAME = "sync_audit_database"
    }
}
