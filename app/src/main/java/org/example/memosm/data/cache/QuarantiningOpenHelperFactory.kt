package org.example.memosm.data.cache

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * [SupportSQLiteOpenHelper.Factory] that quarantines a corrupted database
 * file before androidx.sqlite recovers from it.
 *
 * androidx.sqlite's FrameworkSQLiteOpenHelper never lets a corruption
 * exception reach the caller: the framework DatabaseErrorHandler invokes
 * [SupportSQLiteOpenHelper.Callback.onCorruption] (whose default
 * implementation deletes the file) and the helper then retries the open,
 * silently recreating an empty database. Intercepting onCorruption is the
 * only reliable hook: it runs before any delete/recreate, so the damaged
 * files can be renamed aside via [CorruptDatabaseRecovery] first and the
 * framework's own recovery then recreates a fresh database as usual.
 *
 * [onQuarantined], when provided, runs right after the files were actually
 * moved aside (first corruption of this database in this process only).
 * MemoCacheDatabase uses it to write the DATABASE_CORRUPTED audit row into
 * the separate AuditDatabase, so corruption detected at runtime (post-startup)
 * is recorded exactly like corruption detected during the eager open.
 * AuditDatabase passes no callback: writing the event requires the audit
 * database, which must not route back into the database being recovered.
 */
internal class QuarantiningOpenHelperFactory(
    private val context: Context,
    private val databaseName: String,
    private val onQuarantined: (() -> Unit)? = null,
    private val delegateFactory: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val wrapped = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(QuarantiningCallback(context, databaseName, configuration.callback, onQuarantined))
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
            .build()
        return delegateFactory.create(wrapped)
    }

    /**
     * Delegates every lifecycle callback to Room's callback except
     * [onCorruption], which first moves the damaged files aside.
     */
    private class QuarantiningCallback(
        private val context: Context,
        private val databaseName: String,
        private val delegate: SupportSQLiteOpenHelper.Callback,
        private val onQuarantined: (() -> Unit)?
    ) : SupportSQLiteOpenHelper.Callback(delegate.version) {

        override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)

        override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            delegate.onUpgrade(db, oldVersion, newVersion)

        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            delegate.onDowngrade(db, oldVersion, newVersion)

        override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)

        override fun onCorruption(db: SupportSQLiteDatabase) {
            // Runs (via the framework DatabaseErrorHandler) before the helper's
            // delete-and-recreate recovery. Move the damaged files aside first
            // so they survive; the delegate's default implementation then tries
            // to delete the original path, which is already gone.
            if (CorruptDatabaseRecovery.quarantineDatabaseFiles(context.applicationContext, databaseName)) {
                // First quarantine of this database in this process: fire the
                // audit hook. It must never throw into the corruption path.
                try {
                    onQuarantined?.invoke()
                } catch (t: Throwable) {
                    Log.w(TAG, "Corruption audit hook failed", t)
                }
            }
            delegate.onCorruption(db)
        }
    }

    private companion object {
        const val TAG = "QuarantiningHelper"
    }
}
