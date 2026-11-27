package org.example.memosm.data.offline

import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.media.CachedAttachmentMetaDao
import org.example.memosm.data.media.toAttachment
import org.example.memosm.data.media.toCachedMetadata
import org.example.memosm.model.Attachment

/** Resource metadata and query checkpoints live in Room. */
class AttachmentCacheStore(
    private val dao: CachedAttachmentMetaDao,
) {
    fun pagingSource(accountId: String) = dao.pagingSource(accountId)

    suspend fun getQuery(accountId: String) = dao.getQuery(accountId)

    suspend fun beginRefresh(accountId: String) =
        BackupCoordinator.withStorageLock {
            dao.beginRefresh(accountId)
        }

    suspend fun ingestPage(
        accountId: String,
        attachments: List<Attachment>,
        nextPageToken: String?,
        refresh: Boolean,
        expectedGeneration: Long? = null,
        expectedPageToken: String? = null,
    ) = BackupCoordinator.withStorageLock {
        dao.ingestPage(
            accountId,
            attachments.map { it.toCachedMetadata(accountId) },
            nextPageToken,
            refresh,
            expectedGeneration,
            expectedPageToken,
        )
    }

    suspend fun getCachedMeta(
        accountId: String,
        limit: Int? = null,
    ): List<Attachment> {
        val rows = if (limit == null) dao.getAll(accountId) else dao.getAll(accountId, limit)
        return rows.map { it.toAttachment() }
    }

    suspend fun clearMeta(accountId: String) = BackupCoordinator.withStorageLock { dao.clear(accountId) }
}
