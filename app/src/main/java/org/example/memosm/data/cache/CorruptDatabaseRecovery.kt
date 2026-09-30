package org.example.memosm.data.cache

import android.content.Context
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recovery path for a corrupted database file. The corrupted files are
 * renamed (never deleted) to a timestamped sibling name so they remain
 * available for forensics or manual salvage, then Room recreates an empty
 * database on the next open. Cached data rebuilds from the server; no
 * in-place repair of the outbox is attempted.
 *
 * The primary trigger is [QuarantiningOpenHelperFactory], which hooks
 * SupportSQLiteOpenHelper.Callback.onCorruption because androidx.sqlite
 * absorbs the corruption exception and recreates the file without throwing;
 * MemoCacheDatabase.openVerified keeps a secondary exception-based path.
 * The factory also writes the DATABASE_CORRUPTED audit row (into the
 * separate AuditDatabase, so the record survives the recreate) right after
 * the first quarantine; this covers both startup and runtime corruption.
 */
internal object CorruptDatabaseRecovery {

    private const val TAG = "CorruptDbRecovery"

    /** Per-database guard: set once the corrupted files have been moved aside; prevents double-rename. */
    private val relocated = ConcurrentHashMap<String, AtomicBoolean>()

    /** True when [t] (or one of its causes) reports a corrupted/unopenable database. */
    fun isCorruption(t: Throwable): Boolean {
        var current: Throwable? = t
        while (current != null) {
            if (current is SQLiteDatabaseCorruptException || current is SQLiteCantOpenDatabaseException) return true
            current = current.cause
        }
        return false
    }

    /**
     * Renames the database file and its -wal/-shm companions to
     * "<name>.corrupt-<timestamp>[...]" siblings. Runs at most once per
     * process and database; concurrent or repeated calls after the first are
     * no-ops. Returns true only for the call that actually quarantined, so
     * callers can ride the same first-time-only guard (e.g. for the audit
     * write) without recording duplicates.
     */
    fun quarantineDatabaseFiles(context: Context, databaseName: String): Boolean {
        val guard = relocated.getOrPut(databaseName) { AtomicBoolean(false) }
        if (!guard.compareAndSet(false, true)) return false
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val database = context.getDatabasePath(databaseName)
        for (suffix in arrayOf("", "-wal", "-shm")) {
            val source = File(database.path + suffix)
            if (!source.exists()) continue
            val target = File(source.path + ".corrupt-" + timestamp)
            if (source.renameTo(target)) {
                Log.w(TAG, "Renamed corrupted database file to ${target.name}")
            } else {
                Log.e(TAG, "Could not rename corrupted database file ${source.name}")
            }
        }
        return true
    }
}
