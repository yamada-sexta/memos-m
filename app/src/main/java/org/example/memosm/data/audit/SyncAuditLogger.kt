package org.example.memosm.data.audit

import android.util.Log
import java.security.MessageDigest

/** Writes bounded, redacted audit records. Payloads and credentials never enter this class. */
class SyncAuditLogger(private val dao: SyncAuditDao) {
    suspend fun record(
        accountId: String?,
        event: String,
        outcome: String,
        operation: String? = null,
        target: String? = null,
        detailCode: String? = null
    ) {
        try {
            dao.insert(
                SyncAuditEntry(
                    occurredAt = System.currentTimeMillis(),
                    accountHash = hash(accountId),
                    event = event.take(64),
                    operation = operation?.take(32),
                    outcome = outcome.take(32),
                    targetHash = target?.let(::hash),
                    detailCode = detailCode?.take(64)
                )
            )
            val count = dao.count()
            if (count > MAX_ENTRIES) dao.deleteOldest(count - MAX_ENTRIES)
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist audit entry", e)
        }
    }

    fun observeRecent(limit: Int = 50) = dao.observeRecent(limit.coerceIn(1, 200))

    companion object {
        private const val TAG = "SyncAuditLogger"
        private const val MAX_ENTRIES = 500

        fun hash(value: String?): String {
            if (value.isNullOrEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
