package org.example.memosm.data.sync

import kotlinx.coroutines.CancellationException
import org.example.memosm.api.GsonProvider
import org.example.memosm.api.MemosApi
import org.example.memosm.api.StreamingAttachmentApi
import org.example.memosm.data.audit.SyncAuditLogger
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.media.AttachmentUploadQueue
import org.example.memosm.model.Memo
import org.example.memosm.model.Reaction
import org.example.memosm.model.UpsertMemoReactionRequest
import retrofit2.HttpException

data class DrainResult(
    val retry: Boolean,
    val acknowledged: Int,
)

internal data class MutationResult(
    val serverMemo: Memo?,
    val conflict: Boolean = false,
)

/** One account drain; network I/O never owns the storage/restore lock. */
suspend fun drainOutbox(
    accountId: String,
    api: MemosApi,
    repository: SyncRepository,
    cache: MemoCacheRepository,
    uploads: AttachmentUploadQueue,
    streamingApi: StreamingAttachmentApi?,
    audit: SyncAuditLogger,
    isCurrent: suspend () -> Boolean = { true },
): DrainResult {
    var retry = uploads.replay(accountId, api, streamingApi, isCurrent)
    var acknowledged = 0
    val blocked = mutableSetOf<String>()
    for (snapshot in repository.getOps(accountId)) {
        if (!isCurrent()) break
        val op = repository.getOp(snapshot.id) ?: continue
        val localName = op.memoName
        if (localName in blocked || op.parentName in blocked) continue
        if (op.status != PendingOpStatus.PENDING.name || op.permanentlyFailed) {
            localName?.let(blocked::add)
            continue
        }
        try {
            require(op.accountId == accountId)
            requireNotNull(localName) { "Missing mutation target" }
            val type = PendingOpType.valueOf(op.type)
            val remoteName = cache.resolveServerName(accountId, localName)
            val parentRemote = op.parentName?.let { cache.resolveServerName(accountId, it) }
            if ((type !in setOf(PendingOpType.CREATE, PendingOpType.COMMENT_CREATE) && remoteName == null) ||
                (type == PendingOpType.COMMENT_CREATE && parentRemote == null)
            ) {
                blocked += localName
                continue
            }
            var payload =
                if (type in
                    setOf(PendingOpType.CREATE, PendingOpType.COMMENT_CREATE, PendingOpType.UPDATE)
                ) {
                    decodeMemo(op)
                } else {
                    null
                }
            val memoPayload = payload
            if (memoPayload != null && (type != PendingOpType.UPDATE || "attachments" in op.updateMask.orEmpty().split(','))) {
                val attachments = mutableListOf<org.example.memosm.model.Attachment>()
                var waiting = false
                var blockedUpload: org.example.memosm.data.media.AttachmentUpload? = null
                for (attachment in memoPayload.attachments.orEmpty()) {
                    val result =
                        if (!attachment.name.isNullOrBlank()) {
                            attachment
                        } else {
                            val clientId = requireNotNull(attachment.clientId) { "Attachment has no identity" }
                            val upload = requireNotNull(uploads.get(clientId)) { "Staged attachment record is missing" }
                            require(upload.accountId == accountId) { "Attachment belongs to another account" }
                            if (upload.authRequired || upload.permanentlyFailed) blockedUpload = upload
                            uploads.resolved(clientId)
                        }
                    if (result == null) {
                        waiting = true
                        break
                    }
                    attachments += result.copy(clientId = null, localPath = null)
                }
                if (waiting) {
                    val failedUpload = blockedUpload
                    if (failedUpload != null) {
                        BackupCoordinator.withStorageLock {
                            if (isCurrent() && repository.getOp(op.id) != null) {
                                repository.markFailed(
                                    op.id,
                                    op.attemptCount,
                                    "Attachment upload: ${failedUpload.lastError ?: "requires attention"}",
                                    op.lastAttemptAt,
                                    failedUpload.permanentlyFailed,
                                )
                                repository.setStatus(
                                    op.id,
                                    if (failedUpload.authRequired) PendingOpStatus.AUTH_REQUIRED else PendingOpStatus.REJECTED,
                                )
                            }
                        }
                    }
                    blocked += localName
                    continue
                }
                payload = memoPayload.copy(attachments = attachments.takeIf { memoPayload.attachments != null })
            }
            val result = sendMutation(api, op, remoteName, parentRemote, payload)
            BackupCoordinator.withStorageLock {
                if (!isCurrent() || repository.getOp(op.id) == null) return@withStorageLock
                if (result.conflict) {
                    repository.markFailed(op.id, op.attemptCount + 1, "Conflicting server changes", System.currentTimeMillis())
                    repository.setStatus(op.id, PendingOpStatus.CONFLICT, GsonProvider.gson.toJson(result.serverMemo))
                    blocked += localName
                } else {
                    cache.acknowledge(op, result.serverMemo)
                    acknowledged++
                }
            }
            audit.record(accountId, "SYNC", if (result.conflict) "CONFLICT" else "SUCCESS", op.type, localName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!isCurrent()) break
            val auth = error is HttpException && error.code() in setOf(401, 403)
            val rejected = isRejectedMutation(error)
            BackupCoordinator.withStorageLock {
                if (isCurrent() && repository.getOp(op.id) != null) {
                    repository.markFailed(op.id, op.attemptCount + 1, error.message, System.currentTimeMillis(), rejected)
                    repository.setStatus(
                        op.id,
                        when {
                            auth -> PendingOpStatus.AUTH_REQUIRED
                            rejected -> PendingOpStatus.REJECTED
                            else -> PendingOpStatus.PENDING
                        },
                    )
                }
            }
            localName?.let(blocked::add)
            retry = retry || (!auth && !rejected)
            audit.record(
                accountId,
                "SYNC",
                if (auth) {
                    "AUTH_REQUIRED"
                } else if (rejected) {
                    "REJECTED"
                } else {
                    "RETRY"
                },
                op.type,
                localName,
                if (error is HttpException) "http_${error.code()}" else error.javaClass.simpleName,
            )
        }
    }
    return DrainResult(retry, acknowledged)
}

internal fun isRejectedMutation(error: Exception): Boolean =
    error is IllegalArgumentException || error is com.google.gson.JsonParseException ||
        (
            error is HttpException && error.code() in 400..499 &&
                error.code() !in setOf(401, 403, 408, 429)
        )

/** Transport has no persistence or UI callbacks; every returned resource is committed by the drain. */
internal suspend fun sendMutation(
    api: MemosApi,
    op: PendingOp,
    remoteName: String?,
    parentRemote: String?,
    payload: Memo?,
): MutationResult =
    when (PendingOpType.valueOf(op.type)) {
        PendingOpType.CREATE, PendingOpType.COMMENT_CREATE -> {
            val memo = requireNotNull(payload) { "Missing create payload" }.copy(name = null, parent = parentRemote)
            val created =
                try {
                    if (op.type == PendingOpType.COMMENT_CREATE.name) {
                        api.createMemoComment(requireNotNull(parentRemote), memo, op.id)
                    } else {
                        api.createMemo(memo, op.id)
                    }
                } catch (error: HttpException) {
                    // Modern servers may report an already-existing client-generated resource on retry.
                    if (error.code() != 409) throw error
                    api.getMemo("memos/${op.id}")
                }
            if (created.name.isNullOrBlank()) throw java.io.IOException("Server returned no memo identity")
            MutationResult(created)
        }

        PendingOpType.UPDATE -> {
            val name = requireNotNull(remoteName)
            val server = api.getMemo(name)
            if (op.baseUpdateTime != null && server.updateTime?.toString() != op.baseUpdateTime) {
                MutationResult(server, conflict = true)
            } else {
                val patch = requireNotNull(payload) { "Missing update payload" }
                val mask = op.updateMask?.takeIf { it.isNotBlank() } ?: "content"
                MutationResult(api.updateMemo(name, applyMemoPatch(server, patch, mask).copy(name = name), mask))
            }
        }

        PendingOpType.DELETE -> {
            try {
                api.deleteMemo(requireNotNull(remoteName))
            } catch (error: HttpException) {
                if (error.code() != 404) throw error
            }
            MutationResult(null)
        }

        PendingOpType.REACTION_UPSERT, PendingOpType.REACTION_DELETE -> {
            val name = requireNotNull(remoteName)
            val reaction =
                requireNotNull(GsonProvider.gson.fromJson(op.payloadJson, ReactionOpPayload::class.java)) { "Missing reaction payload" }
            requireNotNull(reaction.creator) { "Missing reaction owner" }
            if (op.type == PendingOpType.REACTION_UPSERT.name) {
                api.upsertMemoReaction(
                    name,
                    UpsertMemoReactionRequest(
                        name,
                        Reaction(contentId = name, reactionType = reaction.reactionType),
                    ),
                )
            } else {
                val found =
                    api.getMemo(name).reactions.orEmpty().firstOrNull {
                        it.creator == reaction.creator && it.reactionType == reaction.reactionType
                    }
                found?.name?.let { reactionName ->
                    try {
                        api.deleteMemoReaction(reactionName)
                    } catch (error: HttpException) {
                        if (error.code() != 404) throw error
                    }
                }
            }
            MutationResult(api.getMemo(name))
        }
    }
