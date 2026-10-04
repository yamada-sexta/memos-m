package org.example.memosm.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.example.memosm.api.GsonProvider
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.DraftManager
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.CachedMemo
import org.example.memosm.data.cache.MemoCacheDatabase
import org.example.memosm.data.media.CachedAttachment
import org.example.memosm.data.media.CachedAttachmentMeta
import org.example.memosm.model.Account
import org.example.memosm.model.Attachment
import org.example.memosm.model.Draft
import org.example.memosm.model.Memo
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** No API, sync repository, upload queue, or scheduler is available to this service. */
class BackupService(
    private val context: Context,
    private val settings: DataStoreManager,
    private val database: MemoCacheDatabase,
    private val drafts: DraftManager
) {
    private val gson = GsonProvider.gson
    private val journal: File get() = File(context.noBackupFilesDir, "backup_restore/transaction.mmbackup")
    private val staging: File get() = File(context.noBackupFilesDir, "backup_staging").apply { mkdirs() }
    fun recoveryDirectory(): File = File(context.noBackupFilesDir, "recovery").apply { mkdirs() }

    class PreparedBackup(val manifest: BackupManifest, internal val decoded: BackupArchive.Decoded) : Closeable {
        override fun close() = decoded.close()
    }

    suspend fun export(selection: BackupSelection, password: CharArray? = null): Result<File> = result {
        withContext(Dispatchers.IO) {
            BackupCoordinator.withStorageLock {
                val scratch = File(staging, "export-${UUID.randomUUID()}").apply { mkdirs() }
                try {
                    val blobs = linkedMapOf<String, File>()
                    val manifest = snapshot(selection, blobs, scratch)
                    validate(manifest, blobs)
                    val target = File(recoveryDirectory(), "memos-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.mmbackup")
                    BackupArchive.write(target, encode(manifest), blobs, password)
                    target
                } finally { scratch.deleteRecursively() }
            }
        }
    }

    suspend fun inspect(source: File, password: CharArray? = null): Result<PreparedBackup> = result {
        withContext(Dispatchers.IO) {
            val legacy = source.inputStream().buffered().use { input ->
                var next = input.read(); var skipped = 0
                while (next >= 0 && next.toChar().isWhitespace() && skipped++ < 4096) next = input.read()
                next == '{'.code
            }
            val decoded = if (legacy) readLegacy(source) else BackupArchive.read(source, staging, password)
            try { PreparedBackup(decode(decoded.metadata).also { validate(it, decoded.blobs) }, decoded) }
            catch (error: Exception) { decoded.close(); throw error }
        }
    }

    /** Replacement is confined to the selected categories and accounts, with a roll-forward journal. */
    suspend fun restore(backup: PreparedBackup, selection: RestoreSelection): Result<RestoreSummary> = result {
        withContext(Dispatchers.IO) {
            require(selection.categories.isNotEmpty() && backup.manifest.categories.containsAll(selection.categories)) { "Select available backup categories" }
            require(backup.manifest.accounts.map { it.identity.id }.toSet().containsAll(selection.accountIds)) { "Unknown backup account" }
            val selected = select(backup.manifest, selection)
            validate(selected, backup.decoded.blobs)
            withContext(NonCancellable) { BackupCoordinator.restore {
                require(!journal.exists()) { "Finish the interrupted restore first" }
                validateAccountTargets(selected)
                val selectedBlobs = referencedBlobs(selected).associateWith { backup.decoded.blobs.getValue(it) }
                // The private journal contains the complete intended result. It is durable before any live changes.
                BackupArchive.write(journal, encode(selected), selectedBlobs)
                withContext(NonCancellable) {
                    try { applyJournal() }
                    catch (error: Exception) { BackupCoordinator.recoveryError.value = "Restore was interrupted. Retry to finish restoring your data."; throw error }
                }
            } }
        }
    }

    /** Called before sessions/workers start, and safe to retry after a partially completed commit. */
    suspend fun recoverInterruptedRestore(): Result<Unit> = result {
        withContext(Dispatchers.IO) {
            if (journal.exists()) {
                BackupCoordinator.restore { withContext(NonCancellable) {
                    applyJournal()
                    BackupCoordinator.recoveryError.value = null
                } }
            }
            BackupCoordinator.recoveryError.value = null
        }
    }

    suspend fun restoreNativeIfPresent(): Result<Unit> = result {
        withContext(Dispatchers.IO) {
            val directory = File(context.filesDir, NativeBackupAgent.DIRECTORY)
            val marker = File(directory, NativeBackupAgent.RESTORE_MARKER)
            if (!marker.exists()) return@withContext
            val device = File(directory, NativeBackupAgent.DEVICE_FILE)
            val cloud = File(directory, NativeBackupAgent.CLOUD_FILE)
            val source = if (device.exists()) device else cloud
            require(source.exists()) { "Android restored an incomplete backup" }
            val prepared = inspect(source).getOrThrow()
            prepared.use {
                if (source == cloud) require(it.manifest.categories == setOf(BackupCategory.ACCOUNTS)) { "Invalid Android cloud backup" }
                require(BackupCategory.QUEUED_EDITS !in it.manifest.categories) { "Android backup cannot contain queued edits" }
                restore(it, RestoreSelection(it.manifest.accounts.map { account -> account.identity.id }.toSet(), it.manifest.categories)).getOrThrow()
            }
            directory.deleteRecursively()
        }
    }

    private suspend fun snapshot(selection: BackupSelection, blobs: MutableMap<String, File>, scratch: File): BackupManifest {
        require(selection.categories.isNotEmpty()) { "Select at least one backup category" }
        val allAccounts = settings.getAccounts()
        require(allAccounts.map { it.id }.toSet().containsAll(selection.accountIds)) { "Unknown account" }
        val accounts = allAccounts.filter { it.id in selection.accountIds }.map { account ->
            val id = account.id
            val blocked = database.pendingOpDao().getOps(id).flatMap { listOfNotNull(it.memoName, it.parentName) }.toSet()
            val memoRows = if (BackupCategory.CACHE in selection.categories) CacheListType.entries.flatMap { type ->
                database.memoDao().getMemos(id, type.name)
            }.filter { CacheBackupPolicy.canExport(it.name, blocked) }.mapNotNull { row ->
                val memo = row.toMemo() ?: return@mapNotNull null
                if (memo.attachments.orEmpty().any { it.name.isNullOrBlank() && it.clientId != null }) return@mapNotNull null
                BackupMemo(row.listType, row.displayOrder, row.parentName, gson.toJsonTree(memo.copy(attachments = memo.attachments?.map { it.copy(clientId = null, localPath = null) })).asJsonObject)
            } else emptyList()
            val metadata = if (BackupCategory.CACHE in selection.categories) database.cachedAttachmentMetaDao().getAll(id)
                .filter { it.memoName !in blocked }
                .map { JsonParser.parseString(it.attachmentJson).asJsonObject.apply { remove("localPath"); remove("clientId") } } else emptyList()
            val media = if (BackupCategory.MEDIA in selection.categories) database.cachedAttachmentDao().getAllForAccount(id).filter { it.memoName !in blocked }.mapNotNull { row ->
                val file = privateFile(row.localPath) ?: return@mapNotNull null
                val blobId = addBlob(file, blobs)
                BackupMedia(row.attachmentName, row.memoName, row.url, blobId, file.length(), row.downloadedAt)
            } else emptyList()
            val draftRows = if (BackupCategory.DRAFTS in selection.categories) drafts.getDrafts(id).map { draft ->
                val attachments = draft.attachments.map { attachment ->
                    val file = attachment.localPath?.let { privateFile(it) }
                    val encoded = attachment.content
                    val local = when {
                        file != null -> file
                        !encoded.isNullOrBlank() -> File(scratch, UUID.randomUUID().toString()).also { target ->
                            java.util.Base64.getDecoder().wrap(encoded.byteInputStream(Charsets.US_ASCII)).use { input -> target.outputStream().use { input.copyTo(it) } }
                        }
                        else -> null
                    }
                    require(local != null || attachment.clientId == null) { "A draft attachment is missing. Recover or remove it before exporting." }
                    attachment.copy(clientId = null, content = null, localPath = local?.let { "blob:${addBlob(it, blobs)}" })
                }
                gson.toJsonTree(draft.copy(attachments = attachments)).asJsonObject
            } else emptyList()
            BackupAccountData(
                BackupIdentity(id, account.hostUrl, account.user?.name, account.displayName ?: account.name ?: account.hostUrl),
                if (BackupCategory.ACCOUNTS in selection.categories) account else null,
                memoRows, metadata,
                if (BackupCategory.CACHE in selection.categories) settings.backupSnapshot("session", id) else null,
                if (BackupCategory.CACHE in selection.categories) settings.backupSnapshot("notifications", id) else null,
                media, draftRows,
                if (BackupCategory.QUEUED_EDITS in selection.categories) {
                    val live = database.pendingOpDao().getOps(id).map { op ->
                        com.google.gson.JsonObject().apply {
                            addProperty("id", op.id); addProperty("type", op.type)
                            op.memoName?.let { addProperty("memoName", it) }
                            op.parentName?.let { addProperty("parentName", it) }
                            op.updateMask?.let { addProperty("updateMask", it) }
                            op.baseUpdateTime?.let { addProperty("baseUpdateTime", it) }
                            addProperty("createdAt", op.createdAt)
                            op.payloadJson?.let { json ->
                                val payload = JsonParser.parseString(json).asJsonObject
                                payload.getAsJsonArray("attachments")?.forEach { item ->
                                    val obj = item.asJsonObject
                                    val path = obj.get("localPath")?.takeUnless { it.isJsonNull }?.asString
                                    val local = path?.let { privateFile(it) }
                                    require(local != null || obj.get("clientId") == null || obj.get("clientId").isJsonNull) { "A queued edit attachment is missing" }
                                    obj.remove("clientId"); obj.remove("localPath")
                                    if (local != null) obj.addProperty("localPath", "blob:${addBlob(local, blobs)}")
                                }
                                add("payload", payload)
                            }
                        }
                    }
                    val reviews = settings.restoredEdits(id).first().map { it.asJsonObject.deepCopy().apply {
                        getAsJsonObject("payload")?.getAsJsonArray("attachments")?.forEach { item ->
                            val obj = item.asJsonObject
                            obj.get("localPath")?.takeUnless { it.isJsonNull }?.asString?.let { path ->
                                val file = privateFile(path) ?: error("A saved edit attachment is missing")
                                obj.addProperty("localPath", "blob:${addBlob(file, blobs)}")
                            }
                        }
                    } }
                    (live + reviews).distinctBy { it.get("id").asString }
                } else emptyList()
            )
        }
        return BackupManifest(System.currentTimeMillis(), selection.categories, accounts,
            if (BackupCategory.SETTINGS in selection.categories) settings.backupSettings() else null)
    }

    private suspend fun validateAccountTargets(manifest: BackupManifest) {
        val current = settings.getAccounts().associateBy { it.id }
        manifest.accounts.forEach { data ->
            val existing = current[data.identity.id]
            if (existing != null) {
                require(CacheBackupPolicy.sameIdentity(data.identity, existing)) { "Account identity does not match; restore into the original account" }
                // Replacing credentials cannot change where existing unsent work will be sent.
                if (data.account != null) require(CacheBackupPolicy.sameIdentity(data.identity, data.account)) { "Backup account identity does not match" }
            } else {
                require(data.account != null) { "Restore account information as well, or first add the original account" }
            }
        }
    }

    private suspend fun applyJournal(): RestoreSummary {
        val decoded = BackupArchive.read(journal, staging)
        decoded.use {
            val manifest = decode(it.metadata)
            validate(manifest, it.blobs)
            validateAccountTargets(manifest)
            val filesRoot = File(context.filesDir, "restored_backup_files")
            val draftFiles = File(filesRoot, "drafts")
            fun mediaFile(accountId: String, blobId: String): File {
                val accountKey = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray()).joinToString("") { "%02x".format(it) }
                return File(File(File(filesRoot, "media"), accountKey), blobId)
            }
            fun installBlob(id: String, target: File) {
                target.parentFile!!.mkdirs()
                if (!target.exists() || BackupArchive.fileId(target) != id) {
                    val temp = File(target.parentFile, ".$id.tmp")
                    decoded.blobs.getValue(id).inputStream().use { input -> temp.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
                    check(temp.renameTo(target)) { "Could not restore attachment" }
                }
            }
            // Media eviction and account removal must never remove draft attachments or another account's files.
            manifest.accounts.forEach { data ->
                data.media.forEach { media -> installBlob(media.blobId, mediaFile(data.identity.id, media.blobId)) }
            }
            draftBlobIds(manifest).forEach { id -> installBlob(id, File(draftFiles, id)) }
            database.withTransaction {
                manifest.accounts.forEach { data ->
                    val id = data.identity.id
                    if (BackupCategory.CACHE in manifest.categories) {
                        val protectedNames = database.memoDao().protectedMemoNames(id).toSet()
                        val protectedRows = CacheListType.entries.flatMap { type -> database.memoDao().getMemos(id, type.name) }.filter { row -> row.name in protectedNames }
                        database.memoDao().deleteAllForAccount(id)
                        val rows = data.memos.filter { row -> row.memo.get("name").asString !in protectedNames }.map { row ->
                            CachedMemo.fromMemo(gson.fromJson(row.memo, Memo::class.java), id, CacheListType.valueOf(row.listType), row.order, row.parentName)
                        }
                        database.memoDao().insertMemos(rows + protectedRows)
                        database.cachedAttachmentMetaDao().clear(id)
                        database.cachedAttachmentMetaDao().upsertAll(data.attachmentMetadata.map { obj ->
                            val attachment = gson.fromJson(obj, Attachment::class.java)
                            CachedAttachmentMeta(id, attachment.name!!, attachment.filename, attachment.type,
                                attachment.size?.toLongOrNull() ?: 0L, attachment.createTime?.toEpochMilliseconds() ?: 0L, attachment.memo, gson.toJson(attachment))
                        })
                    }
                    if (BackupCategory.MEDIA in manifest.categories) {
                        database.cachedAttachmentDao().deleteAllForAccount(id)
                        data.media.forEach { media -> database.cachedAttachmentDao().upsert(CachedAttachment(id, media.attachmentName, media.memoName, media.url,
                            mediaFile(id, media.blobId).absolutePath, media.size, media.downloadedAt, media.downloadedAt)) }
                    }
                }
            }
            if (BackupCategory.DRAFTS in manifest.categories) manifest.accounts.forEach { data ->
                drafts.replaceDrafts(data.identity.id, data.drafts.map { obj ->
                    val draft = gson.fromJson(obj, Draft::class.java)
                    draft.copy(attachments = draft.attachments.map { attachment -> attachment.copy(clientId = null,
                        localPath = attachment.localPath?.removePrefix("blob:")?.let { blobId -> File(draftFiles, blobId).absolutePath }) })
                })
            }
            // Review records stay outside the live outbox, with durable local attachment paths.
            val applied = manifest.copy(accounts = manifest.accounts.map { data -> data.copy(queuedEdits = data.queuedEdits.map { edit -> edit.deepCopy().apply {
                getAsJsonObject("payload")?.getAsJsonArray("attachments")?.forEach { item ->
                    val obj = item.asJsonObject
                    obj.get("localPath")?.takeUnless { it.isJsonNull }?.asString?.let { path ->
                        obj.addProperty("localPath", File(draftFiles, path.removePrefix("blob:")).absolutePath)
                    }
                }
            } }) })
            // Publish credentials last, after every account's data has landed.
            settings.applyBackup(applied)
            check(journal.delete()) { "Could not finish restore" }
            return RestoreSummary(manifest.accounts.count { account -> account.account != null }, manifest.accounts.sumOf { account -> account.memos.size },
                manifest.accounts.sumOf { account -> account.drafts.size }, manifest.accounts.sumOf { account -> account.media.size })
        }
    }

    private fun select(manifest: BackupManifest, selection: RestoreSelection) = manifest.copy(
        categories = selection.categories,
        settings = manifest.settings.takeIf { BackupCategory.SETTINGS in selection.categories },
        accounts = manifest.accounts.filter { it.identity.id in selection.accountIds }.map { data -> data.copy(
            account = data.account.takeIf { BackupCategory.ACCOUNTS in selection.categories },
            memos = if (BackupCategory.CACHE in selection.categories) data.memos else emptyList(),
            attachmentMetadata = if (BackupCategory.CACHE in selection.categories) data.attachmentMetadata else emptyList(),
            session = data.session.takeIf { BackupCategory.CACHE in selection.categories },
            notifications = data.notifications.takeIf { BackupCategory.CACHE in selection.categories },
            media = if (BackupCategory.MEDIA in selection.categories) data.media else emptyList(),
            drafts = if (BackupCategory.DRAFTS in selection.categories) data.drafts else emptyList(),
            queuedEdits = if (BackupCategory.QUEUED_EDITS in selection.categories) data.queuedEdits else emptyList()
        ) }
    )

    private fun encode(manifest: BackupManifest): JsonObject = gson.toJsonTree(manifest).asJsonObject

    private fun decode(root: JsonObject): BackupManifest {
        require(root.get("schemaVersion")?.asInt == BackupArchive.SCHEMA_VERSION) { "Unsupported backup payload schema version" }
        // Read the schema explicitly: omitted required fields are errors, never Gson-created null collections.
        val categories = root.getAsJsonArray("categories").map { BackupCategory.valueOf(it.asString) }.toSet()
        val accounts = root.getAsJsonArray("accounts").map { value ->
            val obj = value.asJsonObject
            val identity = gson.fromJson(obj.getAsJsonObject("identity"), BackupIdentity::class.java)
            BackupAccountData(identity,
                obj.get("account")?.takeUnless { it.isJsonNull }?.let { gson.fromJson(it, Account::class.java) },
                obj.getAsJsonArray("memos").map { item -> item.asJsonObject.let { memo -> BackupMemo(memo.get("listType").asString, memo.get("order").asInt,
                    memo.get("parentName")?.takeUnless { it.isJsonNull }?.asString, memo.getAsJsonObject("memo")) } },
                obj.getAsJsonArray("attachmentMetadata").map { it.asJsonObject },
                obj.get("session")?.takeUnless { it.isJsonNull }?.asJsonObject,
                obj.get("notifications")?.takeUnless { it.isJsonNull }?.asJsonObject,
                obj.getAsJsonArray("media").map { gson.fromJson(it, BackupMedia::class.java) },
                obj.getAsJsonArray("drafts").map { it.asJsonObject },
                obj.getAsJsonArray("queuedEdits")?.map { it.asJsonObject } ?: emptyList())
        }
        return BackupManifest(root.get("createdAt").asLong, categories, accounts,
            root.get("settings")?.takeUnless { it.isJsonNull }?.asJsonObject, root.get("legacy")?.asBoolean ?: false)
    }

    private fun validate(manifest: BackupManifest, blobs: Map<String, File>) {
        require(manifest.categories.isNotEmpty() && manifest.accounts.size <= 1000) { "Invalid backup contents" }
        require(manifest.accounts.map { it.identity.id }.distinct().size == manifest.accounts.size) { "Duplicate backup account" }
        require((manifest.settings != null) == (BackupCategory.SETTINGS in manifest.categories)) { "Invalid backup settings" }
        manifest.settings?.let { settings.validateBackupSettings(it) }
        manifest.accounts.forEach { data ->
            val identity = data.identity
            require(identity.id.isNotBlank() && identity.id.length <= 200 && identity.id.none { it == '/' || it == '\\' } && identity.id !in setOf(".", "..") && identity.hostUrl.isNotBlank()) { "Invalid backup account" }
            require(identity.hostUrl.toHttpUrlOrNull() != null) { "Invalid account server" }
            require((data.account != null) == (BackupCategory.ACCOUNTS in manifest.categories)) { "Missing backup account information" }
            data.account?.let { account -> require(account.id != null && account.hostUrl != null && account.accessToken != null && account.accessToken.isNotBlank() && CacheBackupPolicy.sameIdentity(identity, account)) { "Invalid account credentials" } }
            require(data.memos.size <= 50_000 && data.drafts.size <= 50_000 && data.media.size <= 100_000 && data.attachmentMetadata.size <= 100_000) { "Too many backup records" }
            if (BackupCategory.CACHE !in manifest.categories) require(data.memos.isEmpty() && data.attachmentMetadata.isEmpty() && data.session == null && data.notifications == null)
            if (BackupCategory.DRAFTS !in manifest.categories) require(data.drafts.isEmpty())
            if (BackupCategory.MEDIA !in manifest.categories) require(data.media.isEmpty())
            if (BackupCategory.QUEUED_EDITS !in manifest.categories) require(data.queuedEdits.isEmpty())
            require(data.queuedEdits.size <= 50_000) { "Too many queued edits" }
            data.queuedEdits.forEach { edit ->
                require(edit.get("id").asString.isNotBlank()) { "Invalid queued edit" }
                org.example.memosm.data.sync.PendingOpType.valueOf(edit.get("type").asString)
                edit.getAsJsonObject("payload")?.getAsJsonArray("attachments")?.forEach { item ->
                    val obj = item.asJsonObject
                    require(obj.get("clientId") == null || obj.get("clientId").isJsonNull) { "Invalid automatic upload state" }
                    obj.get("localPath")?.takeUnless { it.isJsonNull }?.asString?.let { path ->
                        require(path.startsWith("blob:") && blobs.containsKey(path.removePrefix("blob:"))) { "Missing queued edit attachment" }
                    }
                }
            }
            data.memos.forEach { row ->
                CacheListType.valueOf(row.listType)
                val memo = gson.fromJson(row.memo, Memo::class.java)
                require(CacheBackupPolicy.canExport(memo.name.orEmpty(), emptySet())) { "Invalid cached memo" }
                require(memo.attachments.orEmpty().none { it.clientId != null || it.localPath != null }) { "Cache contains local upload state" }
            }
            data.attachmentMetadata.forEach { obj ->
                val attachment = gson.fromJson(obj, Attachment::class.java)
                require(!attachment.name.isNullOrBlank() && attachment.filename != null && attachment.type != null && attachment.clientId == null && attachment.localPath == null) { "Invalid cached attachment" }
            }
            data.media.forEach { media -> require(media.attachmentName.isNotBlank() && media.size >= 0 && blobs[media.blobId]?.length() == media.size) { "Missing or invalid media file" } }
            require(data.drafts.map { it.get("id").asString }.distinct().size == data.drafts.size) { "Duplicate draft" }
            data.drafts.forEach { obj ->
                val draft = gson.fromJson(obj, Draft::class.java)
                require(draft.id.isNotBlank() && draft.content != null && draft.visibility != null && draft.attachments != null) { "Invalid draft" }
                draft.attachments.forEach { attachment ->
                    require(attachment.clientId == null && attachment.content == null) { "Draft contains automatic upload state" }
                    attachment.localPath?.let { path -> require(path.startsWith("blob:") && blobs.containsKey(path.removePrefix("blob:"))) { "Invalid draft attachment" } }
                }
            }
        }
    }

    private fun draftBlobIds(manifest: BackupManifest): Set<String> = manifest.accounts.flatMap { data ->
        data.drafts.flatMap { obj -> gson.fromJson(obj, Draft::class.java).attachments.mapNotNull { it.localPath?.removePrefix("blob:") } } +
            data.queuedEdits.flatMap { edit -> edit.getAsJsonObject("payload")?.getAsJsonArray("attachments")?.mapNotNull { item -> item.asJsonObject.get("localPath")?.takeUnless { it.isJsonNull }?.asString?.removePrefix("blob:") } ?: emptyList() }
    }.toSet()

    private fun referencedBlobs(manifest: BackupManifest): Set<String> = manifest.accounts.flatMap { data ->
        data.media.map { it.blobId } + data.drafts.flatMap { obj -> gson.fromJson(obj, Draft::class.java).attachments.mapNotNull { it.localPath?.removePrefix("blob:") } } +
            data.queuedEdits.flatMap { edit -> edit.getAsJsonObject("payload")?.getAsJsonArray("attachments")?.mapNotNull { item -> item.asJsonObject.get("localPath")?.takeUnless { it.isJsonNull }?.asString?.removePrefix("blob:") } ?: emptyList() }
    }.toSet()

    private fun privateFile(path: String): File? {
        val file = File(path)
        if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) return null
        val canonical = file.canonicalFile
        return canonical.takeIf { candidate -> listOf(context.filesDir, context.cacheDir, context.noBackupFilesDir).any { root -> candidate.path.startsWith(root.canonicalPath + File.separator) } }
    }

    private fun addBlob(file: File, blobs: MutableMap<String, File>): String = BackupArchive.fileId(file).also { blobs.putIfAbsent(it, file) }

    private suspend fun readLegacy(source: File): BackupArchive.Decoded {
        require(source.isFile && source.length() in 1..(25L * 1024 * 1024)) { "Invalid legacy backup size" }
        val root = JsonParser.parseString(source.readText()).asJsonObject
        require(root.get("format")?.asString == "memosm-local-recovery" && root.get("version")?.asInt == 1) { "Unsupported legacy backup" }
        val body = root.get("body").asString
        val checksum = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
        require(MessageDigest.isEqual(checksum.toByteArray(), root.get("checksum").asString.toByteArray())) { "Legacy backup checksum mismatch" }
        val payload = JsonParser.parseString(body).asJsonObject
        val accountId = payload.get("accountId").asString
        val account = settings.getAccounts().firstOrNull { it.id == accountId } ?: error("Add the original account before restoring this legacy cache backup")
        val blocked = payload.getAsJsonArray("pendingOps").flatMap { item -> listOf("memoName", "parentName").mapNotNull { item.asJsonObject.get(it)?.takeUnless { it.isJsonNull }?.asString } }.toSet()
        val rows = payload.getAsJsonArray("memos").map { gson.fromJson(it, CachedMemo::class.java) }
        require(rows.size <= 50_000 && rows.all { it.accountId == accountId }) { "Invalid legacy memo rows" }
        val memos = rows.filter { CacheBackupPolicy.canExport(it.name, blocked) }.mapNotNull { row ->
            val memo = row.toMemo() ?: return@mapNotNull null
            if (memo.attachments.orEmpty().any { it.clientId != null || it.localPath != null }) return@mapNotNull null
            BackupMemo(row.listType, row.displayOrder, row.parentName, gson.toJsonTree(memo).asJsonObject)
        }
        val manifest = BackupManifest(payload.get("exportedAt").asLong, setOf(BackupCategory.CACHE), listOf(BackupAccountData(
            BackupIdentity(accountId, account.hostUrl, account.user?.name, account.displayName ?: account.name ?: account.hostUrl), null,
            memos, emptyList(), null, null, emptyList(), emptyList())), null, legacy = true)
        val directory = File(staging, "legacy-${UUID.randomUUID()}").apply { mkdirs() }
        return BackupArchive.Decoded(encode(manifest), emptyMap(), directory)
    }

    private suspend fun <T> result(action: suspend () -> T): Result<T> = try { Result.success(action()) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }
}

internal object CacheBackupPolicy {
    fun canExport(name: String, pendingNames: Set<String>) = name.isNotBlank() && !name.startsWith("offline-") && name !in pendingNames
    fun sameIdentity(identity: BackupIdentity, account: Account): Boolean = identity.id == account.id &&
        normalize(identity.hostUrl) == normalize(account.hostUrl) && (identity.userName == null || account.user?.name == null || identity.userName == account.user.name)
    private fun normalize(host: String) = host.trim().trimEnd('/').removeSuffix("/api/v1").trimEnd('/')
}
