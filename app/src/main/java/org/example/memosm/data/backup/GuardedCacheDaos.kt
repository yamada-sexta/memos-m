package org.example.memosm.data.backup

import org.example.memosm.data.cache.CachedMemo
import org.example.memosm.data.cache.MemoDao
import org.example.memosm.data.media.CachedAttachment
import org.example.memosm.data.media.CachedAttachmentDao
import org.example.memosm.data.media.CachedAttachmentMeta
import org.example.memosm.data.media.CachedAttachmentMetaDao

class GuardedMemoDao(private val delegate: MemoDao) : MemoDao by delegate {
    override suspend fun deleteMissingMemo(accountId: String, name: String, checkedAt: Long) = BackupCoordinator.withStorageLock { delegate.deleteMissingMemo(accountId, name, checkedAt) }
    override suspend fun pruneMissingFromList(accountId: String, listType: String, names: List<String>, startedAt: Long) = BackupCoordinator.withStorageLock { delegate.pruneMissingFromList(accountId, listType, names, startedAt) }
    override suspend fun insertMemos(memos: List<CachedMemo>) = BackupCoordinator.withStorageLock { delegate.insertMemos(memos) }
    override suspend fun insertMemo(memo: CachedMemo) = BackupCoordinator.withStorageLock { delegate.insertMemo(memo) }
    override suspend fun deleteMemos(accountId: String, listType: String) = BackupCoordinator.withStorageLock { delegate.deleteMemos(accountId, listType) }
    override suspend fun deleteMemoByName(accountId: String, name: String) = BackupCoordinator.withStorageLock { delegate.deleteMemoByName(accountId, name) }
    override suspend fun deleteAllForAccount(accountId: String) = BackupCoordinator.withStorageLock { delegate.deleteAllForAccount(accountId) }
    override suspend fun trimListType(accountId: String, listType: String, keep: Int) = BackupCoordinator.withStorageLock { delegate.trimListType(accountId, listType, keep) }
    override suspend fun cacheRemoteMemos(accountId: String, listType: String, memos: List<CachedMemo>, replace: Boolean) = BackupCoordinator.withStorageLock { delegate.cacheRemoteMemos(accountId, listType, memos, replace) }
    override suspend fun replaceMemos(accountId: String, listType: String, memos: List<CachedMemo>) = BackupCoordinator.withStorageLock { delegate.replaceMemos(accountId, listType, memos) }
    override suspend fun saveMemoState(memo: CachedMemo) = BackupCoordinator.withStorageLock { delegate.saveMemoState(memo) }
}

class GuardedAttachmentDao(private val delegate: CachedAttachmentDao) : CachedAttachmentDao by delegate {
    override suspend fun upsert(attachment: CachedAttachment) = BackupCoordinator.withStorageLock { delegate.upsert(attachment) }
    override suspend fun deleteAllForAccount(accountId: String) = BackupCoordinator.withStorageLock { delegate.deleteAllForAccount(accountId) }
    override suspend fun deleteForAttachment(accountId: String, attachmentName: String) = BackupCoordinator.withStorageLock { delegate.deleteForAttachment(accountId, attachmentName) }
}

class GuardedAttachmentMetaDao(private val delegate: CachedAttachmentMetaDao) : CachedAttachmentMetaDao by delegate {
    override suspend fun upsertAll(items: List<CachedAttachmentMeta>) = BackupCoordinator.withStorageLock { delegate.upsertAll(items) }
    override suspend fun clear(accountId: String) = BackupCoordinator.withStorageLock { delegate.clear(accountId) }
}
