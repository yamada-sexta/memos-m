package org.example.memosm.data.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.media.CachedAttachmentMeta
import org.example.memosm.data.media.toAttachment
import org.example.memosm.data.offline.AttachmentCacheStore
import org.example.memosm.model.Attachment
import org.example.memosm.viewmodel.AccountContext
import java.io.IOException

@OptIn(ExperimentalPagingApi::class)
fun attachmentPages(
    account: AccountContext,
    cache: AttachmentCacheStore,
    canFetch: () -> Boolean,
    isCurrent: () -> Boolean,
): Flow<PagingData<Attachment>> {
    val storageGeneration = BackupCoordinator.generation

    fun current() = isCurrent() && BackupCoordinator.generation == storageGeneration
    return Pager(
        config = PagingConfig(pageSize = 30, enablePlaceholders = false),
        remoteMediator =
            object : RemoteMediator<Int, CachedAttachmentMeta>() {
                private val tokens = mutableSetOf<String>()

                override suspend fun initialize() =
                    if (canFetch()) InitializeAction.LAUNCH_INITIAL_REFRESH else InitializeAction.SKIP_INITIAL_REFRESH

                override suspend fun load(
                    loadType: LoadType,
                    state: PagingState<Int, CachedAttachmentMeta>,
                ): MediatorResult {
                    if (loadType == LoadType.PREPEND) return MediatorResult.Success(true)
                    if (!current()) throw CancellationException("Account changed")
                    return try {
                        val previous = cache.getQuery(account.account.id)
                        val token = if (loadType == LoadType.REFRESH) null else previous?.nextPageToken
                        if (loadType == LoadType.APPEND && previous?.complete == true && token == null) return MediatorResult.Success(true)
                        if (!canFetch()) return offlineLoadResult(loadType)
                        if (loadType == LoadType.REFRESH) tokens.clear()
                        val generation =
                            if (loadType == LoadType.REFRESH) {
                                BackupCoordinator.withStorageLock {
                                    if (!current()) throw CancellationException("Account storage changed")
                                    cache.beginRefresh(account.account.id)
                                }
                            } else {
                                previous?.generation
                            }
                        val response = account.api.listAttachments(pageSize = state.config.pageSize, pageToken = token)
                        if (!current()) throw CancellationException("Account changed")
                        val next = response.nextPageToken?.takeIf { it.isNotBlank() }
                        token?.let(tokens::add)
                        check(next == null || tokens.add(next)) { "Server repeated a pagination token" }
                        BackupCoordinator.withStorageLock {
                            if (!current()) throw CancellationException("Account storage changed")
                            cache.ingestPage(
                                account.account.id,
                                response.attachments ?: throw IOException("Server omitted the attachment collection"),
                                next,
                                loadType == LoadType.REFRESH,
                                expectedGeneration = generation,
                                expectedPageToken = token,
                            )
                        }
                        MediatorResult.Success(next == null)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        MediatorResult.Error(error)
                    }
                }
            },
        pagingSourceFactory = { cache.pagingSource(account.account.id) },
    ).flow.map { page -> page.map { it.toAttachment() } }
}
