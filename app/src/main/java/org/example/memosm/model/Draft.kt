package org.example.memosm.model

import java.util.UUID
import kotlin.time.Instant

/**
 * Represents a local draft memo that hasn't been published yet.
 * Stored in the app's cache directory as JSON files, keyed by account ID.
 */
data class Draft(
    val id: String = UUID.randomUUID().toString(),
    val remoteName: String? = null,
    val syncState: MemoSyncState = MemoSyncState.PENDING_CREATE,
    val state: MemoState? = MemoState.NORMAL,
    val content: String = "",
    val visibility: Visibility = Visibility.PRIVATE,
    val attachments: List<Attachment> = emptyList(),
    val location: Location? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Returns true if the draft has meaningful content worth saving.
     */
    fun hasContent(): Boolean = content.isNotBlank() || attachments.isNotEmpty() || location != null

    /**
     * Converts this Draft to a [Memo] for display in MemoItem.
     * Server-specific fields (name, state, reactions, etc.) are left null/default.
     */
    fun toMemo(): Memo {
        return Memo(
            name = remoteName,
            localId = id,
            syncState = syncState,
            state = state,
            content = content,
            visibility = visibility,
            attachments = attachments.ifEmpty { null },
            location = location,
            displayTime = Instant.fromEpochMilliseconds(updatedAt),
        )
    }
}

/**
 * Converts a server [Memo] into a local [Draft] for editing.
 * Maps Instant timestamps back to millis and carries over shared fields.
 */
fun Memo.toDraft(): Draft {
    val now = System.currentTimeMillis()
    return Draft(
        remoteName = name,
        syncState = if (name == null) MemoSyncState.PENDING_CREATE else MemoSyncState.PENDING_UPDATE,
        state = state,
        content = content,
        visibility = visibility,
        attachments = attachments ?: emptyList(),
        location = location,
        createdAt = createTime?.toEpochMilliseconds() ?: now,
        updatedAt = updateTime?.toEpochMilliseconds() ?: now,
    )
}
