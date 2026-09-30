package org.example.memosm.data.sync

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Type of a pending offline write operation.
 */
enum class PendingOpType {
    CREATE,             // Create a new memo
    UPDATE,             // Update memo/comment content, visibility, attachments, state (archive), pinned
    DELETE,             // Delete a memo/comment
    COMMENT_CREATE,     // Create a comment on a memo
    REACTION_UPSERT,    // Add/refresh a reaction on a memo/comment
    REACTION_DELETE     // Remove a reaction from a memo/comment
}

/**
 * Room entity for the offline write queue ("outbox").
 * When a write fails due to no network, it is stored here and replayed
 * by [SyncManager] once connectivity is restored.
 */
@Entity(
    tableName = "pending_ops",
    indices = [Index("accountId")]
)
data class PendingOp(
    @PrimaryKey
    val id: String,
    val accountId: String,
    val type: String,               // PendingOpType.name()
    val memoName: String?,          // Target memo/comment name (temp id for offline creates)
    val parentName: String?,        // For comments: the parent memo name
    val payloadJson: String?,       // Memo JSON payload (CREATE/UPDATE/COMMENT_CREATE) or reaction info (REACTION_*)
    val updateMask: String?,        // Field mask for UPDATE ops ("content,visibility", "state", "pinned"...)
    val baseUpdateTime: String?,    // Server updateTime at the moment the local edit was based on (conflict detection)
    val createdAt: Long,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val lastAttemptAt: Long = 0L,   // Last sync attempt timestamp (exponential backoff)
    val permanentlyFailed: Boolean = false // True when the server rejected the op with a 4xx (retrying won't help)
) {
    companion object {
        fun new(
            accountId: String,
            type: PendingOpType,
            memoName: String?,
            parentName: String? = null,
            payloadJson: String? = null,
            updateMask: String? = null,
            baseUpdateTime: String? = null,
            id: String? = null
        ): PendingOp {
            return PendingOp(
                // Use the caller-supplied id when present (e.g. the clientId of a
                // timed-out online create) so a replay reuses the same memoId.
                id = id ?: java.util.UUID.randomUUID().toString(),
                accountId = accountId,
                type = type.name,
                memoName = memoName,
                parentName = parentName,
                payloadJson = payloadJson,
                updateMask = updateMask,
                baseUpdateTime = baseUpdateTime,
                createdAt = System.currentTimeMillis()
            )
        }
    }
}
