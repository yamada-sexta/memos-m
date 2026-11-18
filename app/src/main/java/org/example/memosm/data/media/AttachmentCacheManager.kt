package org.example.memosm.data.media

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.example.memosm.api.awaitResponse
import org.example.memosm.api.resourceAuthorization
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.model.Attachment
import org.example.memosm.viewmodel.manager.AttachmentManager
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AttachmentCacheManager"

/** Parallel downloads during a preload batch (keeps servers from being hammered). */
private const val PRELOAD_PARALLELISM = 4

data class PreloadResult(
    val available: Int = 0,
    val failed: Int = 0,
)

/**
 * Downloads attachment files (images/audio/video/other) to local storage so
 * they can be viewed/played offline. Files live in
 * filesDir/offline_media/{accountId}/{attachmentName}/{filename} (see
 * [OfflineMediaPaths]; filesDir so the OS cache purger cannot wipe the
 * offline store) and are tracked in the Room table [CachedAttachment] for
 * LRU eviction, bounded by the user-configurable cache size limit.
 */
class AttachmentCacheManager(
    private val context: Context,
    private val dao: CachedAttachmentDao,
    private val okHttpClient: OkHttpClient,
    private val dataStoreManager: DataStoreManager,
    private val isWifiProvider: () -> Boolean,
) {
    data class Usage(
        val bytes: Long = 0L,
        val count: Int = 0,
    )

    /**
     * Usage of the ACTIVE account only. Consumers (settings screen,
     * PreDownloadManager) read this as the current account's numbers, so
     * refreshes for other accounts (e.g. a background preload) must never
     * overwrite it with wrong-account values; [refreshUsage] gates on
     * [activeAccountId] and [clearAccount] only zeroes it when the cleared
     * account is the published one.
     */
    data class AccountUsage(
        val accountId: String? = null,
        val usage: Usage = Usage(),
    )

    private val _usage = MutableStateFlow(AccountUsage())
    val usage: StateFlow<AccountUsage> = _usage.asStateFlow()

    private fun rootDir(): File = OfflineMediaPaths.rootDir(context)

    /**
     * Whether the legacy cacheDir -> filesDir migration already completed in
     * this process. Left false when a run fails so the next access retries;
     * the move itself is idempotent (see [OfflineMediaMigrator]), so a crash
     * mid-migration simply resumes on the next run.
     */
    private val legacyMigrationDone = AtomicBoolean(false)

    /**
     * One-time migration of offline media from the pre-Phase-2 root
     * (cacheDir/offline_media) to the current one (filesDir/offline_media).
     * Runs lazily on first cache access rather than at startup: no migration
     * work is paid by installs that never had downloads, and every read/write
     * path funnels through here before touching the DB, so old rows are never
     * served with stale paths. Relative paths are preserved, each
     * `cached_attachments.localPath` row is updated right after its file
     * lands, and the old dir is removed afterwards (untracked leftovers there
     * are disposable temp files/orphans).
     */
    private suspend fun migrateLegacyRootIfNeeded() {
        if (legacyMigrationDone.get()) return
        withContext(Dispatchers.IO) {
            if (legacyMigrationDone.get()) return@withContext
            try {
                val legacy = OfflineMediaPaths.legacyRootDir(context)
                if (legacy.exists()) {
                    var moved = 0
                    dao.getAll().forEach { row ->
                        val target =
                            OfflineMediaMigrator.movePreservingPath(
                                legacy,
                                rootDir(),
                                File(row.localPath),
                            ) ?: return@forEach
                        if (target.absolutePath != row.localPath) {
                            dao.upsert(row.copy(localPath = target.absolutePath))
                            moved++
                        }
                    }
                    legacy.deleteRecursively()
                    if (moved > 0) {
                        Log.d(TAG, "migrated $moved offline media files to filesDir")
                    }
                }
                legacyMigrationDone.set(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "offline media migration failed; will retry on next access", e)
            }
        }
    }

    /**
     * In-memory index of downloaded attachment files (accountId, attachmentName -> file).
     *
     * The old implementation queried Room and wrote an LRU touch on every read,
     * which caused one SELECT + one UPDATE per image the moment it scrolled into
     * view - a major source of list jank. Reads are now served from this index
     * (a hash lookup, no I/O) and the DB is only consulted on a miss. The
     * per-access lastAccessedAt touch is dropped from the read path entirely;
     * LRU eviction order falls back to download time, which is good enough for
     * enforcing a size cap.
     */
    private val localIndex = ConcurrentHashMap<String, File>()

    /**
     * Whether [warmIndex] already ran in this process. Warming once is enough:
     * entries only disappear via [evictIfNeeded]/[clearAccount], both of which
     * remove from the index, and the file-exists check in [indexLookup] keeps
     * stale entries from being served.
     */
    private val indexWarmed = AtomicBoolean(false)

    fun invalidateLocalIndex() {
        localIndex.clear()
        indexWarmed.set(false)
    }

    private fun indexKey(
        accountId: String,
        attachmentName: String,
    ): String = "$accountId\u0000$attachmentName"

    private fun indexLookup(key: String): File? {
        val file = localIndex[key] ?: return null
        if (!file.exists()) {
            localIndex.remove(key)
            return null
        }
        return file
    }

    private fun indexPut(
        accountId: String,
        attachmentName: String,
        file: File,
    ) {
        localIndex[indexKey(accountId, attachmentName)] = file
    }

    private fun indexRemove(
        accountId: String,
        attachmentName: String,
    ) {
        localIndex.remove(indexKey(accountId, attachmentName))
    }

    /**
     * Populate the in-memory index from the DB in one query. A preload over a
     * large attachment set would otherwise do a per-attachment SELECT on the
     * first miss (the index starts empty after a process restart), turning a
     * batch of N attachments into N queries; warming makes those lookups all
     * in-memory hash reads.
     */
    suspend fun warmIndex() {
        migrateLegacyRootIfNeeded()
        if (!indexWarmed.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) {
            try {
                val rows = dao.getAll()
                rows.forEach { row ->
                    val file = File(row.localPath)
                    if (file.exists()) {
                        indexPut(row.accountId, row.attachmentName, file)
                    }
                }
                Log.d(TAG, "warmed index with ${rows.size} cached attachments")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "warmIndex failed", e)
            }
        }
    }

    /**
     * Return the locally cached file for an attachment, or null.
     * Served from the in-memory index; falls back to a single DB read on miss.
     */
    suspend fun getLocalFile(
        accountId: String,
        attachmentName: String?,
    ): File? {
        if (attachmentName.isNullOrBlank()) return null
        migrateLegacyRootIfNeeded()
        indexLookup(indexKey(accountId, attachmentName))?.let { return it }
        return try {
            val row = dao.getByAttachment(accountId, attachmentName) ?: return null
            val file = File(row.localPath)
            if (!file.exists()) {
                dao.deleteForAttachment(accountId, attachmentName)
                refreshUsage(accountId)
                null
            } else {
                indexPut(accountId, attachmentName, file)
                file
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "getLocalFile failed for $attachmentName", e)
            null
        }
    }

    /** Missing account identity must fall back to the network, never another account's file. */
    suspend fun getLocalFileForAccount(
        accountId: String?,
        attachmentName: String?,
    ): File? = accountId?.let { getLocalFile(it, attachmentName) }

    suspend fun displayModel(
        accountId: String?,
        hostUrl: String,
        attachment: Attachment?,
    ): Any? {
        if (attachment == null) return null
        getLocalFileForAccount(accountId, attachment.name)?.let { return it }
        return AttachmentManager.getAttachmentUrl(hostUrl, attachment)
    }

    /**
     * Download a single attachment to the offline cache.
     * Returns true when the file is available locally afterwards.
     *
     * [evict] controls whether the size cap is enforced after this download.
     * Preloads pass `false` and run [evictIfNeeded] once after the whole
     * batch: per-download eviction would run a full-table SUM query per file
     * (O(N) queries for N new attachments).
     */
    suspend fun download(
        hostUrl: String,
        token: String,
        accountId: String,
        memoName: String,
        attachment: Attachment,
        evict: Boolean = true,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Boolean {
        val generation = BackupCoordinator.generation
        val attName = attachment.name ?: return false
        // Inline base64 content needs no download - it travels with the memo JSON.
        if (!attachment.content.isNullOrBlank()) return true
        if (getLocalFile(accountId, attName) != null) return true
        val url = AttachmentManager.getAttachmentUrl(hostUrl, attachment) ?: return false

        return withContext(dispatcher) {
            var partFile: File? = null
            try {
                val fileName = sanitizeFileName(attachment.filename)
                val dir = File(File(rootDir(), accountId), sanitizeDirName(attName))
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, fileName)
                // Unique temp name per download attempt: concurrent downloads
                // of the same attachment must not share one .part file (they
                // would corrupt each other's bytes). The final rename target
                // stays stable, so the last finisher wins atomically.
                val temp = File(dir, ".$fileName.part-${UUID.randomUUID()}")
                partFile = temp

                val requestBuilder = Request.Builder().url(url)
                resourceAuthorization(hostUrl, url, token)?.let { requestBuilder.header("Authorization", it) }
                okHttpClient.newCall(requestBuilder.build()).awaitResponse { response ->
                    check(response.isSuccessful) { "Attachment download failed: HTTP ${response.code}" }
                    response.body.byteStream().use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                val size = temp.length()
                if (size == 0L) {
                    temp.delete()
                    return@withContext false
                }
                BackupCoordinator.withStorageLock {
                    check(generation == BackupCoordinator.generation && !BackupCoordinator.restoring.value) { "Storage was restored" }
                    check(dataStoreManager.getAccounts().any { it.id == accountId }) { "Account was removed" }
                    // Another attempt may have committed while this response was downloading.
                    val existing = dao.getByAttachment(accountId, attName)?.let { File(it.localPath) }?.takeIf { it.isFile }
                    if (existing != null) {
                        indexPut(accountId, attName, existing)
                    } else {
                        withContext(NonCancellable) {
                            check(temp.renameTo(target)) { "Could not commit attachment file" }
                            var committed = false
                            try {
                                dao.upsert(
                                    CachedAttachment(
                                        attachmentName = attName,
                                        accountId = accountId,
                                        memoName = memoName,
                                        url = url,
                                        localPath = target.absolutePath,
                                        size = size,
                                        downloadedAt = System.currentTimeMillis(),
                                        lastAccessedAt = System.currentTimeMillis(),
                                    ),
                                )
                                committed = true
                                indexPut(accountId, attName, target)
                            } finally {
                                if (!committed) target.delete()
                            }
                        }
                    }
                }
                if (evict) evictIfNeeded()
                Log.d(TAG, "downloaded $url ($size bytes)")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "download failed: $url", e)
                false
            } finally {
                partFile?.delete()
            }
        }
    }

    /**
     * Pre-download the given (attachment, memoName) pairs, skipping ones that
     * are already cached (checked in [download] via the warmed index/DB).
     * Used both for memo-embedded attachments and for attachments known only
     * from the metadata cache, which no cached memo references anymore.
     *
     * Respects the Wi-Fi-only setting (read via the suspend DataStore flow,
     * never with runBlocking on the calling thread). Downloads run with
     * limited parallelism (PRELOAD_PARALLELISM) instead of serially, so large
     * attachment sets complete faster without hammering the server; eviction
     * and the usage refresh happen once after the batch.
     */
    suspend fun preloadAttachments(
        hostUrl: String,
        token: String,
        accountId: String,
        attachments: List<Pair<Attachment, String>>,
    ): PreloadResult {
        if (attachments.isEmpty()) return PreloadResult()
        if (dataStoreManager.preDownloadWifiOnly.first() && !isWifiProvider()) {
            return PreloadResult(failed = attachments.size)
        }
        warmIndex()
        return withContext(Dispatchers.IO) {
            val permits = Semaphore(PRELOAD_PARALLELISM)
            val available =
                coroutineScope {
                    attachments
                        .distinctBy { it.first.name }
                        .map { (attachment, memoName) ->
                            async(Dispatchers.IO) {
                                permits.withPermit {
                                    download(
                                        hostUrl,
                                        token,
                                        accountId,
                                        memoName,
                                        attachment,
                                        evict = false,
                                        dispatcher = Dispatchers.IO,
                                    )
                                }
                            }
                        }.awaitAll()
                }
            evictIfNeeded()
            refreshUsage(accountId)
            PreloadResult(available.count { it }, available.count { !it })
        }
    }

    /**
     * Evict least-recently-used files until the total cache (across ALL
     * accounts) stays within the configured size limit. The cap is a global
     * budget: with per-account eviction, N accounts could each fill the full
     * cap and multiply disk usage by N.
     */
    private suspend fun evictIfNeeded() {
        try {
            val capMb = dataStoreManager.attachmentCacheMaxMb.first()
            // 0 (or negative) = unlimited: never evict.
            if (capMb <= 0) return
            val cap = capMb * 1024L * 1024L
            BackupCoordinator.withStorageLock {
                var total = dao.getTotalSizeAll()
                if (total <= cap) return@withStorageLock
                for (row in dao.getAll().sortedBy { it.lastAccessedAt }) {
                    if (total <= cap) break
                    if (removeCachedFile(row)) total -= row.size
                }
            }
            Log.d(TAG, "evicted attachments to fit ${capMb}MB cap")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "evictIfNeeded failed", e)
        }
    }

    /**
     * Refresh the published [usage] from the DB for [accountId]. Only
     * publishes when [accountId] is the active account (see [usage]).
     */
    suspend fun refreshUsage(accountId: String) {
        try {
            val usage =
                Usage(
                    bytes = dao.getTotalSize(accountId),
                    count = dao.getAllForAccount(accountId).size,
                )
            if (accountId == activeAccountId()) {
                _usage.value = AccountUsage(accountId, usage)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "refreshUsage failed", e)
        }
    }

    /** Id of the active account, resolved from the stored account list. */
    private suspend fun activeAccountId(): String? =
        dataStoreManager.accounts
            .first()
            .firstOrNull { it.isActive }
            ?.id

    /**
     * Remove all downloaded attachments for an account (files + metadata).
     */
    suspend fun clearAccount(accountId: String) {
        withContext(Dispatchers.IO) {
            try {
                BackupCoordinator.withStorageLock {
                    dao.getAllForAccount(accountId).forEach { removeCachedFile(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "clearAccount failed", e)
            }
            // Zero the published usage only when it belonged to the cleared
            // account; clearing a background account must not blank the
            // active account's numbers.
            _usage.update { if (it.accountId == accountId) AccountUsage() else it }
        }
    }

    /** Keep accounting and retry ownership when the filesystem refuses deletion. */
    private suspend fun removeCachedFile(row: CachedAttachment): Boolean =
        withContext(NonCancellable) {
            val file = File(row.localPath)
            val removed =
                try {
                    !file.exists() || file.delete()
                } catch (_: java.io.IOException) {
                    false
                }
            if (removed) {
                dao.deleteForAttachment(row.accountId, row.attachmentName)
                indexRemove(row.accountId, row.attachmentName)
            }
            removed
        }

    private fun sanitizeFileName(name: String): String {
        val sanitized = name.replace(Regex("[/\\\\:?*\"<>|]"), "_")
        return sanitized.ifBlank { "file" }
    }

    private fun sanitizeDirName(name: String): String {
        // Attachment names become a directory segment under the account dir:
        // besides path separators, "." / ".." would resolve to (or escape)
        // the offline-media root - the same threat OfflineMediaMigrator
        // rejects - so map them to a safe name.
        val sanitized = name.replace('/', '_').replace('\\', '_')
        return if (sanitized == "." || sanitized == "..") "_" else sanitized
    }
}
