package org.example.memosm.data.backup

import com.google.gson.JsonObject
import org.example.memosm.model.Account

enum class BackupCategory { ACCOUNTS, SETTINGS, CACHE, MEDIA, DRAFTS, QUEUED_EDITS }

data class BackupSelection(
    val accountIds: Set<String>,
    val categories: Set<BackupCategory> = BackupCategory.entries.toSet() - BackupCategory.QUEUED_EDITS
)

data class RestoreSelection(val accountIds: Set<String>, val categories: Set<BackupCategory>)

/** Identity is present even when credentials were unchecked. Never match by host alone. */
data class BackupIdentity(val id: String, val hostUrl: String, val userName: String?, val label: String)
data class BackupMemo(val listType: String, val order: Int, val parentName: String?, val memo: JsonObject)
data class BackupMedia(
    val attachmentName: String, val memoName: String, val url: String,
    val blobId: String, val size: Long, val downloadedAt: Long
)
data class BackupAccountData(
    val identity: BackupIdentity,
    val account: Account?,
    val memos: List<BackupMemo>,
    val attachmentMetadata: List<JsonObject>,
    val session: JsonObject?,
    val notifications: JsonObject?,
    val media: List<BackupMedia>,
    val drafts: List<JsonObject>,
    val queuedEdits: List<JsonObject> = emptyList()
)

/** Queued edits are inert review records; this schema cannot populate the live outbox. */
data class BackupManifest(
    val createdAt: Long,
    val categories: Set<BackupCategory>,
    val accounts: List<BackupAccountData>,
    val settings: JsonObject?,
    val legacy: Boolean = false,
    val schemaVersion: Int = BackupArchive.SCHEMA_VERSION
)

data class RestoreSummary(val accountCount: Int, val memoCount: Int, val draftCount: Int, val mediaCount: Int)

class PasswordRequiredException : IllegalArgumentException("This backup requires a password")
class BackupPasswordException : IllegalArgumentException("Incorrect password or damaged encrypted backup")
