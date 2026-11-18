package org.example.memosm.data.backup

import org.example.memosm.data.media.CachedAttachment
import org.example.memosm.data.media.CachedAttachmentDao
import org.example.memosm.data.media.CachedAttachmentMeta
import org.example.memosm.data.media.CachedAttachmentMetaDao

class GuardedAttachmentDao(
    private val delegate: CachedAttachmentDao,
) : CachedAttachmentDao by delegate {
    override suspend fun upsert(attachment: CachedAttachment) = BackupCoordinator.withStorageLock { delegate.upsert(attachment) }

    override suspend fun deleteAllForAccount(accountId: String) =
        BackupCoordinator.withStorageLock { delegate.deleteAllForAccount(accountId) }

    override suspend fun deleteForAttachment(
        accountId: String,
        attachmentName: String,
    ) = BackupCoordinator.withStorageLock {
        delegate.deleteForAttachment(accountId, attachmentName)
    }
}

class GuardedAttachmentMetaDao(
    private val delegate: CachedAttachmentMetaDao,
) : CachedAttachmentMetaDao by delegate {
    override suspend fun upsertAll(items: List<CachedAttachmentMeta>) = BackupCoordinator.withStorageLock { delegate.upsertAll(items) }

    override suspend fun clear(accountId: String) = BackupCoordinator.withStorageLock { delegate.clear(accountId) }
}
