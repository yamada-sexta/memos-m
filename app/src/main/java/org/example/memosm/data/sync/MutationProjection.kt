package org.example.memosm.data.sync

import org.example.memosm.api.GsonProvider
import org.example.memosm.model.Memo
import org.example.memosm.model.Reaction

internal const val EDITABLE_MEMO_FIELDS = "content,visibility,attachments,location,state,pinned,tags,display_time"

/** Apply only fields the user changed; later pin/archive actions must not restore old content. */
fun applyMemoPatch(
    base: Memo,
    patch: Memo,
    mask: String,
): Memo {
    val fields = mask.split(',').map(String::trim).toSet()
    return base.copy(
        content = if ("content" in fields) patch.content else base.content,
        visibility = if ("visibility" in fields) patch.visibility else base.visibility,
        attachments = if ("attachments" in fields) patch.attachments else base.attachments,
        location = if ("location" in fields) patch.location else base.location,
        state = if ("state" in fields) patch.state else base.state,
        pinned = if ("pinned" in fields) patch.pinned else base.pinned,
        tags = if ("tags" in fields) patch.tags else base.tags,
        displayTime = if ("display_time" in fields || "displayTime" in fields) patch.displayTime else base.displayTime,
    )
}

/** Fold durable mutations over the latest remote snapshot. Null represents a pending delete. */
fun projectMemo(
    base: Memo?,
    ops: List<PendingOp>,
): Memo? {
    var visible = base
    for (op in ops.sortedWith(compareBy<PendingOp> { it.sequence }.thenBy { it.createdAt }.thenBy { it.id })) {
        when (PendingOpType.valueOf(op.type)) {
            PendingOpType.CREATE, PendingOpType.COMMENT_CREATE -> {
                visible = decodeMemo(op)
            }

            PendingOpType.UPDATE -> {
                val patch = decodeMemo(op)
                visible = visible?.let { applyMemoPatch(it, patch, op.updateMask ?: "content") } ?: patch
            }

            PendingOpType.DELETE -> {
                visible = null
            }

            PendingOpType.REACTION_UPSERT, PendingOpType.REACTION_DELETE -> {
                visible =
                    visible?.let { memo ->
                        val payload = requireNotNull(GsonProvider.gson.fromJson(op.payloadJson, ReactionOpPayload::class.java))
                        val rest =
                            memo.reactions.orEmpty().filterNot {
                                it.reactionType == payload.reactionType && it.creator == payload.creator
                            }
                        memo.copy(
                            reactions =
                                if (op.type == PendingOpType.REACTION_DELETE.name) {
                                    rest
                                } else {
                                    rest +
                                        Reaction(
                                            contentId = memo.name.orEmpty(),
                                            reactionType = payload.reactionType,
                                            creator = payload.creator,
                                        )
                                },
                        )
                    }
            }
        }
    }
    return visible
}

internal fun decodeMemo(op: PendingOp): Memo =
    requireNotNull(GsonProvider.gson.fromJson(op.payloadJson, Memo::class.java)) { "Missing mutation payload" }

fun PendingOp.toConflict(): ConflictItem? {
    if (status != PendingOpStatus.CONFLICT.name) return null
    val local = runCatching { decodeMemo(this) }.getOrNull() ?: return null
    val server = runCatching { GsonProvider.gson.fromJson(conflictServerJson, Memo::class.java) }.getOrNull() ?: return null
    return ConflictItem(id, accountId, memoName ?: return null, local, server)
}
