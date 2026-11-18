package org.example.memosm.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.example.memosm.api.AuthInterceptor
import org.example.memosm.api.MemosApi
import org.example.memosm.api.MemosApiFactory
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.cache.*
import org.example.memosm.data.media.AttachmentCacheManager
import org.example.memosm.data.offline.AttachmentCacheStore
import org.example.memosm.model.User
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

private const val COOLDOWN_MS = 30 * 60 * 1000L
internal const val FULL_SNAPSHOT_INTERVAL_MS = 24 * 60 * 60 * 1000L

sealed interface PreDownloadState {
    data object Idle : PreDownloadState

    data class Running(
        val phase: Phase,
        val page: Int = 0,
        val accountId: String? = null,
    ) : PreDownloadState {
        enum class Phase { TEXT, ATTACHMENTS }
    }

    data class Done(
        val timestamp: Long,
        val textCount: Int = 0,
        val attachmentCount: Int = 0,
        val accountId: String? = null,
    ) : PreDownloadState

    data class Failed(
        val message: String,
        val accountId: String? = null,
    ) : PreDownloadState
}

fun preDownloadWorkName(accountId: String) = "memosm-history-$accountId"

fun schedulePreDownload(
    context: Context,
    accountId: String,
    text: Boolean? = null,
    attachments: Boolean? = null,
    manual: Boolean = false,
) {
    val data = Data.Builder().putString("accountId", accountId).putBoolean("manual", manual)
    text?.let { data.putBoolean("text", it) }
    attachments?.let { data.putBoolean("attachments", it) }
    val request =
        OneTimeWorkRequestBuilder<PreDownloadWorker>()
            .setInputData(data.build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
    val work = WorkManager.getInstance(context)
    work.enqueueUniqueWork(
        preDownloadWorkName(accountId),
        if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
        request,
    )
    val periodic =
        PeriodicWorkRequestBuilder<PreDownloadWorker>(6, TimeUnit.HOURS)
            .setInputData(workDataOf("accountId" to accountId, "periodic" to true))
            .build()
    work.enqueueUniquePeriodicWork(preDownloadWorkName(accountId) + "-periodic", ExistingPeriodicWorkPolicy.UPDATE, periodic)
}

fun cancelPreDownload(
    context: Context,
    accountId: String,
) {
    WorkManager.getInstance(context).cancelUniqueWork(preDownloadWorkName(accountId))
    WorkManager.getInstance(context).cancelUniqueWork(preDownloadWorkName(accountId) + "-periodic")
}

fun preDownloadState(
    work: WorkInfo?,
    accountId: String,
): PreDownloadState =
    when (work?.state) {
        WorkInfo.State.RUNNING -> {
            PreDownloadState.Running(
                if (work.progress.getString("phase") ==
                    "attachments"
                ) {
                    PreDownloadState.Running.Phase.ATTACHMENTS
                } else {
                    PreDownloadState.Running.Phase.TEXT
                },
                work.progress.getInt("page", 0),
                accountId,
            )
        }

        WorkInfo.State.SUCCEEDED -> {
            work.outputData.getLong("completedAt", 0).let { at ->
                if (at == 0L) {
                    PreDownloadState.Idle
                } else {
                    PreDownloadState.Done(
                        at,
                        work.outputData.getInt("textCount", 0),
                        work.outputData.getInt("attachmentCount", 0),
                        accountId,
                    )
                }
            }
        }

        WorkInfo.State.FAILED -> {
            PreDownloadState.Failed(work.outputData.getString("error") ?: "Download failed", accountId)
        }

        else -> {
            PreDownloadState.Idle
        }
    }

internal fun needsFullSnapshot(
    query: CachedQuery?,
    now: Long,
): Boolean =
    query == null || query.lastFullSnapshotAt == 0L || query.incrementalCursor == 0L ||
        now - query.lastFullSnapshotAt >= FULL_SNAPSHOT_INTERVAL_MS

/** Exhaustive pulls use separate tokens from interactive paging, but the same ingestion transaction. */
suspend fun downloadMemoHistory(
    api: MemosApi,
    accountId: String,
    user: User,
    cache: MemoCacheRepository,
    includeExplore: Boolean,
    keep: Int = Int.MAX_VALUE,
    isCurrent: suspend () -> Boolean = { true },
    onPage: suspend (Int) -> Unit = {},
): Int {
    val creator = requireNotNull(api.buildMemoCreatorFilter(user)?.takeIf { it.isNotBlank() }) { "Missing account identity" }
    val lists =
        listOf(Triple(CacheListType.USER, creator, null), Triple(CacheListType.ARCHIVED, creator, "ARCHIVED")) +
            if (includeExplore) listOf(Triple(CacheListType.EXPLORE, "visibility in ['PUBLIC', 'PROTECTED']", null)) else emptyList()
    var total = 0
    for ((type, filter, state) in lists) {
        val key = defaultQueryKey(type) + ":history"
        val startedAt = System.currentTimeMillis()
        val previous = cache.getQuery(accountId, key)
        var incremental = !needsFullSnapshot(previous, startedAt)
        var generation = cache.beginRefresh(accountId, key, type, incremental = incremental)
        var token: String? = null
        val tokens = mutableSetOf<String>()
        var page = 1
        do {
            if (!isCurrent()) throw CancellationException("Account storage changed")
            onPage(page)
            val effectiveFilter =
                if (incremental) {
                    "$filter && update_time >= \"${Instant.ofEpochMilli(
                        previous!!.incrementalCursor,
                    )}\""
                } else {
                    filter
                }
            val response =
                try {
                    api.listMemos(pageSize = 100, pageToken = token, filter = effectiveFilter, state = state)
                } catch (error: retrofit2.HttpException) {
                    if (!incremental || error.code() !in setOf(400, 422)) throw error
                    incremental = false
                    token = null
                    page = 1
                    tokens.clear()
                    generation = cache.beginRefresh(accountId, key, type)
                    api.listMemos(pageSize = 100, filter = filter, state = state)
                }
            val memos = response.memos ?: throw IOException("Server omitted the memo collection")
            val next = response.nextPageToken?.takeIf { it.isNotBlank() }
            next?.let { check(tokens.add(it)) { "Server repeated a pagination token" } }
            BackupCoordinator.withStorageLock {
                if (!isCurrent()) throw CancellationException("Account storage changed")
                cache.ingestPage(
                    accountId,
                    key,
                    type,
                    memos,
                    nextPageToken = next,
                    refresh = page == 1 && !incremental,
                    incremental = incremental,
                    checkpointAt = startedAt,
                    expectedGeneration = generation,
                    expectedPageToken = token,
                )
            }
            total += memos.size
            token = next
            page++
        } while (token != null)
        if (keep < Int.MAX_VALUE) cache.trimCachedMemos(accountId, type, keep)
    }
    return total
}

suspend fun clearTextCache(
    accountId: String,
    cache: MemoCacheRepository,
    settings: DataStoreManager,
) {
    cache.clearCache(accountId)
    settings.saveTextSyncCursor(accountId, 0)
    settings.saveLastPreDownloadAt(accountId, 0)
}

class PreDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            BackupCoordinator.awaitStartupRecovery()
            val accountId = inputData.getString("accountId") ?: return@withContext Result.failure()
            if (inputData.getBoolean("periodic", false)) {
                schedulePreDownload(applicationContext, accountId)
                return@withContext Result.success()
            }
            val koin =
                org.koin.core.context.GlobalContext
                    .get()
            val settings = koin.get<DataStoreManager>()
            val account = settings.getAccounts().firstOrNull { it.id == accountId } ?: return@withContext Result.success()
            val generation = BackupCoordinator.generation

            suspend fun current() =
                !isStopped && !BackupCoordinator.restoring.value && BackupCoordinator.recoveryError.value == null &&
                    BackupCoordinator.generation == generation &&
                    settings.getAccounts().any {
                        it.id == account.id && it.hostUrl == account.hostUrl && it.accessToken == account.accessToken
                    }
            if (!current()) return@withContext Result.retry()
            val text =
                if (inputData.keyValueMap.containsKey(
                        "text",
                    )
                ) {
                    inputData.getBoolean("text", false)
                } else {
                    settings.preDownloadText.first()
                }
            val attachments =
                if (inputData.keyValueMap.containsKey(
                        "attachments",
                    )
                ) {
                    inputData.getBoolean("attachments", false)
                } else {
                    settings.preDownloadAttachments.first()
                }
            if (!text && !attachments) return@withContext Result.success()
            val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
            if (settings.preDownloadWifiOnly.first() && connectivity
                    .getNetworkCapabilities(connectivity.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true
            ) {
                return@withContext Result.retry()
            }
            val cache = koin.get<MemoCacheRepository>()
            val explore = settings.preDownloadExplore.first()
            val baseline =
                cache.getQuery(accountId, "USER:history") != null && (!explore || cache.getQuery(accountId, "EXPLORE:history") != null)
            if (!inputData.getBoolean("manual", false) && baseline &&
                System.currentTimeMillis() - settings.lastPreDownloadAt(accountId).first() < COOLDOWN_MS
            ) {
                return@withContext Result.success()
            }
            try {
                val client =
                    koin
                        .get<OkHttpClient>()
                        .newBuilder()
                        .addInterceptor(AuthInterceptor(account.accessToken))
                        .build()
                val api = MemosApiFactory.create(account.hostUrl, client)
                var textCount = 0
                if (text) {
                    val user = api.getCurrentSession().user ?: throw IOException("Missing account identity")
                    textCount =
                        downloadMemoHistory(
                            api,
                            accountId,
                            user,
                            cache,
                            explore,
                            keep = maxOf(500, settings.textCacheMaxMb.first() * 50),
                            isCurrent = ::current,
                        ) { page ->
                            setProgress(workDataOf("phase" to "text", "page" to page))
                        }
                }
                if (!current()) throw CancellationException("Account storage changed")
                var attachmentCount = 0
                if (attachments) {
                    setProgress(workDataOf("phase" to "attachments"))
                    val metadata = koin.get<AttachmentCacheStore>()
                    val candidates = metadata.getCachedMeta(accountId)
                    val result =
                        koin.get<AttachmentCacheManager>().preloadAttachments(
                            account.hostUrl,
                            account.accessToken,
                            accountId,
                            candidates.map { it to it.memo.orEmpty() },
                        )
                    if (result.failed > 0) throw IOException("${result.failed} attachments could not be downloaded")
                    attachmentCount = result.available
                }
                BackupCoordinator.withStorageLock {
                    if (!current()) throw CancellationException("Account storage changed")
                    settings.saveLastPreDownloadAt(accountId, System.currentTimeMillis())
                }
                Result.success(
                    workDataOf(
                        "completedAt" to System.currentTimeMillis(),
                        "textCount" to textCount,
                        "attachmentCount" to attachmentCount,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is retrofit2.HttpException && error.code() in setOf(400, 401, 403, 404, 422)) {
                    Result.failure(workDataOf("error" to (error.message ?: "Download failed")))
                } else {
                    Result.retry()
                }
            }
        }
}
