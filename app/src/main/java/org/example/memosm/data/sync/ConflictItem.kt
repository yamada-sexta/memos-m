package org.example.memosm.data.sync

import org.example.memosm.model.Memo

/**
 * A conflict detected while syncing an offline UPDATE: the server version of
 * the memo was modified (by another device) after the local edit was based on.
 */
data class ConflictItem(
    val opId: String,
    val memoName: String,
    val localMemo: Memo,   // The locally edited version (from the pending op)
    val serverMemo: Memo   // The current server version
)

enum class ConflictResolution {
    KEEP_LOCAL,    // Force-push the local version to the server
    KEEP_SERVER,   // Discard the local edit, adopt the server version
    MERGE,         // Push a user-edited third version combining both sides
    LATER          // Leave the op queued, ask again on next sync
}

/**
 * Payload for REACTION_UPSERT / REACTION_DELETE pending ops.
 */
data class ReactionOpPayload(
    val reactionType: String,
    val creator: String? = null
)
