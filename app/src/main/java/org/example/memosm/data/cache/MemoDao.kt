package org.example.memosm.data.cache

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.example.memosm.api.GsonProvider
import org.example.memosm.data.media.AttachmentUpload
import org.example.memosm.data.media.CachedAttachmentMeta
import org.example.memosm.data.media.toCachedMetadata
import org.example.memosm.data.sync.EDITABLE_MEMO_FIELDS
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.PendingOpType
import org.example.memosm.data.sync.applyMemoPatch
import org.example.memosm.data.sync.projectMemo
import org.example.memosm.model.Memo
import org.example.memosm.model.hasValidCoordinates

private const val MEMBER_COLUMNS =
    "e.localId AS name,e.accountId,m.listType,e.visibleJson AS memoJson," +
        "m.position AS displayOrder,e.cachedAt,e.content,e.createTime,e.updateTime,e.visibility,e.state,e.tags,e.pinned,e.parentId AS parentName,e.remoteName"
private const val MEMBER_JOIN = " FROM memo_entities e JOIN memo_memberships m ON e.accountId=m.accountId AND e.localId=m.localId "
private const val MEMBER_ORDER =
    " ORDER BY CASE WHEN m.listType='USER' THEN e.pinned ELSE 0 END DESC," +
        "CASE WHEN m.listType='COMMENT' THEN e.displayTime END ASC,e.displayTime DESC,e.localId ASC"
private const val PROTECTED =
    "SELECT memoName FROM pending_ops WHERE accountId=:accountId AND memoName IS NOT NULL " +
        "UNION SELECT parentName FROM pending_ops WHERE accountId=:accountId AND parentName IS NOT NULL"

/** Room owns canonical values, collection membership, and their atomic outbox projections. */
@Dao
interface MemoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEntity(entity: MemoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMembership(membership: MemoMembership)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putQuery(query: CachedQuery)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAttachmentMetadata(items: List<CachedAttachmentMeta>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUploads(uploads: List<AttachmentUpload>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMutation(op: PendingOp)

    @Query("SELECT * FROM memo_entities WHERE accountId=:accountId AND (localId=:name OR remoteName=:name) LIMIT 1")
    suspend fun getEntity(
        accountId: String,
        name: String,
    ): MemoEntity?

    @Query("SELECT * FROM memo_entities WHERE accountId=:accountId AND (localId=:name OR remoteName=:name) LIMIT 1")
    fun observeEntity(
        accountId: String,
        name: String,
    ): Flow<MemoEntity?>

    @Query("SELECT * FROM memo_queries WHERE accountId=:accountId AND `key`=:queryKey")
    suspend fun getQuery(
        accountId: String,
        queryKey: String,
    ): CachedQuery?

    @Query("SELECT * FROM memo_queries WHERE accountId=:accountId AND `key`=:queryKey")
    fun observeQuery(
        accountId: String,
        queryKey: String,
    ): Flow<CachedQuery?>

    @Query(
        "SELECT " + MEMBER_COLUMNS + MEMBER_JOIN +
            "WHERE e.accountId=:accountId AND (m.queryKey=:queryKey OR (:sourceListType IS NOT NULL AND m.listType=:sourceListType)) AND e.deleted=0 GROUP BY e.localId ORDER BY CASE WHEN :order='PINNED_DESC' OR (:order='' AND m.listType='USER') THEN e.pinned ELSE 0 END DESC,CASE WHEN :order='OLDEST' OR (:order='' AND m.listType='COMMENT') THEN e.displayTime END ASC,e.displayTime DESC,e.localId ASC",
    )
    fun pagingSource(
        accountId: String,
        queryKey: String,
        order: String,
        sourceListType: String?,
    ): PagingSource<Int, CachedMemo>

    @Query(
        "SELECT " + MEMBER_COLUMNS + MEMBER_JOIN + "WHERE e.accountId=:accountId AND m.queryKey=:queryKey AND e.deleted=0" + MEMBER_ORDER,
    )
    suspend fun getQueryMemos(
        accountId: String,
        queryKey: String,
    ): List<CachedMemo>

    @Transaction
    suspend fun readCollectionSnapshot(
        accountId: String,
        queryKey: String,
        sourceListTypes: List<String>,
        allowFallback: Boolean,
    ): Pair<List<CachedMemo>, CachedQuery?> {
        val query = getQuery(accountId, queryKey)
        val rows = getQueryMemos(accountId, queryKey)
        if (!allowFallback) return rows to query
        val completeBaseline = query?.complete == true || (query?.lastFullSnapshotAt ?: 0) > 0
        val protected = if (completeBaseline) protectedMemoNames(accountId).toSet() else null
        val fallback =
            sourceListTypes
                .flatMap { getMemos(accountId, it) }
                .filter { protected == null || it.name in protected }
        return (rows + fallback).distinctBy { it.name } to query
    }

    @Query(
        "SELECT " + MEMBER_COLUMNS + MEMBER_JOIN + "WHERE e.accountId=:accountId AND m.queryKey=:queryKey AND e.deleted=0" + MEMBER_ORDER,
    )
    fun observeQueryMemos(
        accountId: String,
        queryKey: String,
    ): Flow<List<CachedMemo>>

    @Query(
        "SELECT " + MEMBER_COLUMNS + MEMBER_JOIN +
            "WHERE e.accountId=:accountId AND m.listType IN (:listTypes) AND e.deleted=0 GROUP BY e.localId" +
            MEMBER_ORDER,
    )
    fun observeMemos(
        accountId: String,
        listTypes: List<String>,
    ): Flow<List<CachedMemo>>

    @Query("SELECT COALESCE(MAX(position),-1)+1 FROM memo_memberships WHERE accountId=:accountId AND queryKey=:queryKey")
    suspend fun nextPosition(
        accountId: String,
        queryKey: String,
    ): Int

    @Query("SELECT COALESCE(MAX(sequence),0)+1 FROM pending_ops WHERE accountId=:accountId")
    suspend fun nextSequence(accountId: String): Long

    @Query("SELECT * FROM pending_ops WHERE accountId=:accountId AND memoName=:name ORDER BY sequence,createdAt,id")
    suspend fun pendingForMemo(
        accountId: String,
        name: String,
    ): List<PendingOp>

    @Query("SELECT * FROM pending_ops WHERE id=:id")
    suspend fun mutation(id: String): PendingOp?

    @Query("DELETE FROM pending_ops WHERE id=:id")
    suspend fun deleteMutation(id: String)

    @Query("UPDATE pending_ops SET baseUpdateTime=:base WHERE accountId=:accountId AND memoName=:name AND type='UPDATE'")
    suspend fun rebaseMutations(
        accountId: String,
        name: String,
        base: String?,
    )

    @Query(
        "UPDATE pending_ops SET baseUpdateTime=:base WHERE accountId=:accountId AND memoName=:name AND type='UPDATE' AND baseUpdateTime IS :previous",
    )
    suspend fun rebaseMatchingMutations(
        accountId: String,
        name: String,
        previous: String?,
        base: String?,
    )

    @Query(
        "SELECT " + MEMBER_COLUMNS + MEMBER_JOIN +
            "WHERE e.accountId=:accountId AND m.listType=:listType AND e.deleted=0 GROUP BY e.localId" +
            MEMBER_ORDER,
    )
    suspend fun getMemos(
        accountId: String,
        listType: String,
    ): List<CachedMemo>

    @Query("SELECT COUNT(*) FROM memo_entities WHERE accountId=:accountId AND deleted=0")
    suspend fun getCountForAccount(accountId: String): Int

    @Query(
        "SELECT EXISTS(SELECT 1 FROM memo_entities WHERE accountId=:accountId AND ((:clientId!='' AND instr(visibleJson,:clientId)>0) OR (:localPath!='' AND instr(visibleJson,:localPath)>0)))",
    )
    suspend fun referencesAttachment(
        accountId: String,
        clientId: String,
        localPath: String,
    ): Boolean

    @Query(
        "SELECT memoName FROM pending_ops WHERE accountId=:accountId AND memoName IS NOT NULL UNION SELECT parentName FROM pending_ops WHERE accountId=:accountId AND parentName IS NOT NULL",
    )
    suspend fun protectedMemoNames(accountId: String): List<String>

    @Query(
        "DELETE FROM memo_memberships WHERE accountId=:accountId AND queryKey=:queryKey AND generation!=:generation AND localId NOT IN (" +
            PROTECTED +
            ")",
    )
    suspend fun pruneQuery(
        accountId: String,
        queryKey: String,
        generation: Long,
    )

    @Query(
        "DELETE FROM memo_memberships WHERE accountId=:accountId AND queryKey=:defaultKey AND cachedAt<=:startedAt AND localId NOT IN (" +
            PROTECTED +
            ") AND NOT EXISTS (SELECT 1 FROM memo_memberships history WHERE history.accountId=:accountId AND history.queryKey=:historyKey AND history.localId=memo_memberships.localId)",
    )
    suspend fun pruneDefaultAfterHistory(
        accountId: String,
        defaultKey: String,
        historyKey: String,
        startedAt: Long,
    )

    @Query("DELETE FROM memo_memberships WHERE accountId=:accountId AND localId=:name")
    suspend fun deleteMemberships(
        accountId: String,
        name: String,
    )

    @Query("DELETE FROM memo_memberships WHERE accountId=:accountId AND listType=:listType")
    suspend fun deleteMemos(
        accountId: String,
        listType: String,
    )

    @Query(
        "DELETE FROM memo_memberships WHERE accountId=:accountId AND localId=:name AND listType IN ('USER','ARCHIVED') AND listType!=:retainedType",
    )
    suspend fun deleteOwnMemberships(
        accountId: String,
        name: String,
        retainedType: String,
    )

    @Query("DELETE FROM memo_memberships WHERE accountId=:accountId AND localId=:name AND listType IN ('EXPLORE','MAP_EXPLORE')")
    suspend fun deleteExploreMemberships(
        accountId: String,
        name: String,
    )

    @Query("DELETE FROM memo_memberships WHERE accountId=:accountId AND localId=:name AND listType IN ('MAP_USER','MAP_EXPLORE','MAP_ALL')")
    suspend fun deleteMapMemberships(
        accountId: String,
        name: String,
    )

    @Query("SELECT COUNT(*) FROM memo_memberships WHERE accountId=:accountId AND localId=:name AND listType IN ('USER','ARCHIVED')")
    suspend fun ownMembershipCount(
        accountId: String,
        name: String,
    ): Int

    @Query("SELECT COUNT(*) FROM memo_memberships WHERE accountId=:accountId AND localId=:name AND queryKey=:queryKey")
    suspend fun queryMembershipCount(
        accountId: String,
        name: String,
        queryKey: String,
    ): Int

    @Query("DELETE FROM memo_entities WHERE accountId=:accountId AND localId=:name")
    suspend fun deleteEntity(
        accountId: String,
        name: String,
    )

    @Query(
        "INSERT OR REPLACE INTO memo_memberships (accountId,queryKey,localId,listType,parentName,position,generation,cachedAt) SELECT accountId,queryKey,:newName,listType,parentName,position,generation,cachedAt FROM memo_memberships WHERE accountId=:accountId AND localId=:oldName",
    )
    suspend fun transferMemberships(
        accountId: String,
        oldName: String,
        newName: String,
    )

    @Query(
        "UPDATE pending_ops SET memoName=CASE WHEN memoName=:oldName THEN :newName ELSE memoName END,parentName=CASE WHEN parentName=:oldName THEN :newName ELSE parentName END WHERE accountId=:accountId AND (memoName=:oldName OR parentName=:oldName)",
    )
    suspend fun transferMutationReferences(
        accountId: String,
        oldName: String,
        newName: String,
    )

    @Query("SELECT * FROM memo_entities WHERE accountId=:accountId AND parentId=:parent")
    suspend fun children(
        accountId: String,
        parent: String,
    ): List<MemoEntity>

    @Query("UPDATE memo_memberships SET parentName=:newName WHERE accountId=:accountId AND parentName=:oldName")
    suspend fun transferParentMemberships(
        accountId: String,
        oldName: String,
        newName: String,
    )

    @Query(
        "INSERT OR REPLACE INTO memo_memberships (accountId,queryKey,localId,listType,parentName,position,generation,cachedAt) SELECT accountId,:newKey,localId,listType,:newParent,position,:generation,cachedAt FROM memo_memberships WHERE accountId=:accountId AND queryKey=:oldKey",
    )
    suspend fun transferThreadMemberships(
        accountId: String,
        oldKey: String,
        newKey: String,
        newParent: String,
        generation: Long,
    )

    @Query("DELETE FROM memo_memberships WHERE accountId=:accountId AND queryKey=:queryKey")
    suspend fun deleteQueryMemberships(
        accountId: String,
        queryKey: String,
    )

    @Query("DELETE FROM memo_queries WHERE accountId=:accountId AND `key`=:queryKey")
    suspend fun deleteQuery(
        accountId: String,
        queryKey: String,
    )

    @Query("UPDATE cached_attachment_meta SET memoName=:newName WHERE accountId=:accountId AND memoName=:oldName")
    suspend fun transferAttachmentReferences(
        accountId: String,
        oldName: String,
        newName: String,
    )

    @Query("DELETE FROM memo_memberships WHERE accountId=:accountId AND localId NOT IN (" + PROTECTED + ")")
    suspend fun clearCleanMemberships(accountId: String)

    @Query("DELETE FROM memo_entities WHERE accountId=:accountId AND localId NOT IN (" + PROTECTED + ")")
    suspend fun clearCleanEntities(accountId: String)

    @Query("DELETE FROM memo_queries WHERE accountId=:accountId")
    suspend fun clearQueries(accountId: String)

    @Query(
        "DELETE FROM memo_entities WHERE accountId=:accountId " +
            "AND localId NOT IN (SELECT localId FROM memo_memberships WHERE accountId=:accountId) AND localId NOT IN (" +
            PROTECTED +
            ") AND localId NOT IN (SELECT parentId FROM memo_entities WHERE accountId=:accountId AND parentId IS NOT NULL)",
    )
    suspend fun deleteCleanOrphans(accountId: String)

    @Query(
        "SELECT COALESCE(remoteName,localId) AS name," +
            "length(CAST(COALESCE(serverJson,'') AS BLOB))+length(CAST(visibleJson AS BLOB))+length(CAST(content AS BLOB))+length(CAST(tags AS BLOB)) AS bytes " +
            "FROM memo_entities WHERE accountId=:accountId AND localId NOT IN (" + PROTECTED + ") " +
            "AND localId NOT IN (SELECT parentId FROM memo_entities WHERE accountId=:accountId AND parentId IS NOT NULL) " +
            "ORDER BY createTime DESC,localId ASC",
    )
    suspend fun cacheSizes(
        accountId: String,
    ): Map<
        @MapColumn("name")
        String,
        @MapColumn("bytes")
        Long,
    >

    @Query(
        "UPDATE memo_queries SET complete=0,lastFullSnapshotAt=0,incrementalCursor=0 WHERE accountId=:accountId AND `key` LIKE '%:history'",
    )
    suspend fun invalidateCacheBaselines(accountId: String)

    @Transaction
    suspend fun trimToBytes(
        accountId: String,
        maxBytes: Long,
        fetchedNames: List<String>,
    ): Pair<Long, Boolean> {
        val sizes = cacheSizes(accountId)
        val fetchedBytes = fetchedNames.sumOf { sizes[it]?.takeIf { bytes -> bytes <= maxBytes } ?: 0L }
        var remaining = maxBytes
        var evicted = false
        for ((name, bytes) in sizes) {
            if (bytes <= remaining) {
                remaining -= bytes
            } else {
                deleteMemoByName(accountId, name)
                evicted = true
            }
        }
        if (evicted) invalidateCacheBaselines(accountId)
        return fetchedBytes to evicted
    }

    @Transaction
    suspend fun insertMemos(memos: List<CachedMemo>) {
        memos.forEach { insertMemo(it) }
    }

    @Transaction
    suspend fun insertMemo(memo: CachedMemo) {
        val value = requireNotNull(memo.toMemo()) { "Invalid cached memo ${memo.name}" }
        storeRemote(memo.accountId, value, memo.parentName)
        val entity = requireNotNull(getEntity(memo.accountId, memo.name)).copy(cachedAt = memo.cachedAt)
        putEntity(entity)
        val type = if (entity.parentId != null) CacheListType.COMMENT else CacheListType.valueOf(memo.listType)
        putMembership(
            MemoMembership(
                memo.accountId,
                defaultQueryKey(type, entity.parentId),
                entity.localId,
                type.name,
                entity.parentId,
                memo.displayOrder,
                0,
                memo.cachedAt,
            ),
        )
        updateStateMembership(entity)
    }

    @Transaction
    suspend fun deleteMemoByName(
        accountId: String,
        name: String,
    ) {
        val local = getEntity(accountId, name)?.localId ?: name
        deleteMemberships(accountId, local)
        deleteEntity(accountId, local)
    }

    @Transaction
    suspend fun deleteAllForAccount(accountId: String) {
        clearCleanMemberships(accountId)
        clearCleanEntities(accountId)
        clearQueries(accountId)
    }

    @Transaction
    suspend fun storeRemote(
        accountId: String,
        memo: Memo,
        parentName: String? = null,
    ): MemoEntity {
        val name = memo.remoteName ?: requireNotNull(memo.name) { "Server memo has no resource name" }
        val previous = getEntity(accountId, name)
        val oldRevision = previous?.takeIf { it.serverVerified }?.serverMemo()?.updateTime
        if (oldRevision != null && memo.updateTime != null && memo.updateTime < oldRevision) return requireNotNull(previous)
        val localId = previous?.localId ?: name
        val parentId = parentName?.let { getEntity(accountId, it)?.localId ?: it } ?: previous?.parentId ?: memo.parent
        val base =
            (
                previous ?: MemoEntity.fromMemo(
                    accountId,
                    memo,
                    localId,
                    parentId,
                )
            ).copy(
                remoteName = name,
                serverJson = GsonProvider.gson.toJson(memo.copy(name = name)),
                serverVerified = true,
                parentId = parentId,
            )
        val updated = base.project(projectMemo(memo.copy(name = localId, parent = parentId), pendingForMemo(accountId, localId)))
        persistEntity(updated)
        return updated
    }

    @Transaction
    suspend fun persistEntity(entity: MemoEntity) {
        putEntity(entity)
        val attachments = entity.serverMemo()?.attachments.orEmpty() + entity.visibleMemo().attachments.orEmpty()
        val metadata =
            attachments
                .filter { it.name != null }
                .distinctBy { it.name }
                .map { it.toCachedMetadata(entity.accountId, entity.localId) }
        if (metadata.isNotEmpty()) putAttachmentMetadata(metadata)
    }

    @Transaction
    suspend fun commitMutation(
        op: PendingOp,
        optimisticMemo: Memo?,
        listType: CacheListType,
        parentName: String?,
        uploads: List<AttachmentUpload> = emptyList(),
    ) {
        mutation(op.id)?.let { existing ->
            require(existing.accountId == op.accountId && existing.type == op.type && existing.payloadJson == op.payloadJson) {
                "Mutation identity already belongs to different content"
            }
            return
        }
        require(uploads.all { it.accountId == op.accountId }) { "Upload belongs to another account" }
        insertUploads(uploads)
        val target = requireNotNull(op.memoName) { "Mutation has no local identity" }
        val existing = getEntity(op.accountId, target)
        require(existing?.deleted != true || op.type == PendingOpType.DELETE.name) { "Memo is pending deletion" }
        if (op.type == PendingOpType.COMMENT_CREATE.name) {
            val parent = requireNotNull(parentName ?: op.parentName) { "Comment has no parent" }
            require(getEntity(op.accountId, parent)?.deleted != true) { "Parent memo is pending deletion" }
        }
        val localId = existing?.localId ?: target
        val stored = op.copy(memoName = localId, sequence = nextSequence(op.accountId))
        insertMutation(stored)
        val initial = optimisticMemo ?: existing?.visibleMemo()
        requireNotNull(initial) { "Mutation has no recoverable local content" }
        val remote = op.type !in setOf(PendingOpType.CREATE.name, PendingOpType.COMMENT_CREATE.name) && target.startsWith("memos/")
        val entity =
            existing ?: MemoEntity
                .fromMemo(
                    op.accountId,
                    initial,
                    localId,
                    parentName,
                    remote = remote,
                ).copy(serverVerified = false)
        val base = entity.serverMemo()?.copy(name = localId, parent = entity.parentId)
        val projected = projectMemo(base ?: initial, pendingForMemo(op.accountId, localId))
        val updated = entity.project(projected)
        persistEntity(updated)
        if (existing == null && listType !in setOf(CacheListType.USER, CacheListType.ARCHIVED)) {
            putMembership(
                MemoMembership(
                    op.accountId,
                    defaultQueryKey(listType, parentName),
                    localId,
                    listType.name,
                    parentName,
                    0,
                    0,
                    System.currentTimeMillis(),
                ),
            )
        }
        val ownedMutation =
            op.type in setOf(PendingOpType.CREATE.name, PendingOpType.UPDATE.name) &&
                listType in setOf(CacheListType.USER, CacheListType.ARCHIVED)
        updateStateMembership(updated, ownedMutation)
    }

    @Transaction
    suspend fun acknowledge(
        op: PendingOp,
        serverMemo: Memo?,
    ) {
        if (mutation(op.id) == null) return
        val name = requireNotNull(op.memoName)
        val entity = getEntity(op.accountId, name)
        deleteMutation(op.id)
        if (op.type == PendingOpType.DELETE.name) {
            deleteMemoByName(op.accountId, name)
            return
        }
        if (entity == null) return
        val duplicate = serverMemo?.name?.let { getEntity(op.accountId, it) }?.takeIf { it.localId != entity.localId }
        val fetched = duplicate?.serverMemo()
        val returned = serverMemo ?: entity.serverMemo() ?: entity.visibleMemo()
        val server =
            if (fetched?.updateTime != null && returned.updateTime != null &&
                fetched.updateTime > returned.updateTime
            ) {
                fetched
            } else {
                returned
            }
        if (duplicate != null) {
            transferMemberships(op.accountId, duplicate.localId, entity.localId)
            transferMutationReferences(op.accountId, duplicate.localId, entity.localId)
            children(op.accountId, duplicate.localId).forEach { child ->
                persistEntity(child.copy(parentId = entity.localId).project(child.visibleMemo().copy(parent = entity.localId)))
            }
            transferParentMemberships(op.accountId, duplicate.localId, entity.localId)
            val oldKey = defaultQueryKey(CacheListType.COMMENT, duplicate.localId)
            val newKey = defaultQueryKey(CacheListType.COMMENT, entity.localId)
            val oldQuery = getQuery(op.accountId, oldKey)
            val newQuery = getQuery(op.accountId, newKey)
            transferThreadMemberships(op.accountId, oldKey, newKey, entity.localId, newQuery?.generation ?: 0L)
            if (oldQuery != null) {
                putQuery(
                    (newQuery ?: CachedQuery(op.accountId, newKey, "COMMENT", entity.localId))
                        .copy(loaded = oldQuery.loaded || newQuery?.loaded == true, complete = false, nextPageToken = null),
                )
            }
            deleteQueryMemberships(op.accountId, oldKey)
            deleteQuery(op.accountId, oldKey)
            transferAttachmentReferences(op.accountId, duplicate.localId, entity.localId)
            deleteMemberships(op.accountId, duplicate.localId)
            deleteEntity(op.accountId, duplicate.localId)
        }
        val base =
            entity.copy(
                remoteName = serverMemo?.name ?: entity.remoteName,
                serverJson = GsonProvider.gson.toJson(server),
                serverVerified = true,
            )
        // Only advance guards that still refer to the acknowledged local transition.
        // A reaction may observe unrelated server edits; it cannot approve those edits.
        val previous = entity.serverMemo()
        val knownTransition =
            server == returned && (
                op.type in setOf(PendingOpType.CREATE.name, PendingOpType.COMMENT_CREATE.name, PendingOpType.UPDATE.name) ||
                    previous != null && applyMemoPatch(previous, server, EDITABLE_MEMO_FIELDS) == previous
            )
        if (knownTransition) {
            if (op.type in setOf(PendingOpType.CREATE.name, PendingOpType.COMMENT_CREATE.name)) {
                rebaseMutations(op.accountId, name, server.updateTime?.toString())
            } else {
                rebaseMatchingMutations(
                    op.accountId,
                    name,
                    op.baseUpdateTime ?: previous?.updateTime?.toString(),
                    server.updateTime?.toString(),
                )
            }
        }
        val updated =
            base.project(
                projectMemo(server.copy(name = entity.localId, parent = entity.parentId), pendingForMemo(op.accountId, name)),
            )
        persistEntity(updated)
        updateStateMembership(updated, op.type in setOf(PendingOpType.CREATE.name, PendingOpType.UPDATE.name))
    }

    @Transaction
    suspend fun discardMutation(
        op: PendingOp,
        serverMemo: Memo?,
    ) {
        deleteMutation(op.id)
        val name = op.memoName ?: return
        val entity = getEntity(op.accountId, name) ?: return
        val server = serverMemo ?: entity.serverMemo()
        if (server == null && pendingForMemo(op.accountId, name).isEmpty()) {
            deleteMemoByName(op.accountId, name)
            return
        }
        val base =
            if (serverMemo != null) {
                entity.copy(
                    serverJson = GsonProvider.gson.toJson(serverMemo),
                    remoteName = serverMemo.name,
                    serverVerified = true,
                )
            } else {
                entity
            }
        rebaseMutations(op.accountId, name, server?.updateTime?.toString())
        val updated =
            base.project(
                projectMemo(server?.copy(name = entity.localId, parent = entity.parentId), pendingForMemo(op.accountId, name)),
            )
        persistEntity(updated)
        updateStateMembership(updated, ownMembershipCount(op.accountId, name) > 0)
    }

    /** Resolve one blocked mutation without exposing a half-updated base to observers. */
    @Transaction
    suspend fun resolveMutation(
        op: PendingOp,
        serverMemo: Memo,
        resolvedPayload: String?,
        resolvedMask: String? = null,
    ) {
        val current = mutation(op.id) ?: return
        require(current.status == "CONFLICT") { "Mutation is no longer conflicted" }
        if (resolvedPayload == null) {
            discardMutation(current, serverMemo)
            return
        }
        val name = requireNotNull(current.memoName)
        val entity = requireNotNull(getEntity(current.accountId, name))
        deleteMutation(current.id)
        insertMutation(
            current.copy(
                id =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                payloadJson = resolvedPayload,
                updateMask = resolvedMask ?: current.updateMask,
                baseUpdateTime = serverMemo.updateTime?.toString(),
                status = "PENDING",
                conflictServerJson = null,
                permanentlyFailed = false,
                attemptCount = 0,
                lastAttemptAt = 0,
                lastError = null,
            ),
        )
        val base = entity.copy(serverJson = GsonProvider.gson.toJson(serverMemo), remoteName = serverMemo.name, serverVerified = true)
        rebaseMutations(current.accountId, name, serverMemo.updateTime?.toString())
        val updated =
            base.project(
                projectMemo(serverMemo.copy(name = entity.localId, parent = entity.parentId), pendingForMemo(current.accountId, name)),
            )
        persistEntity(updated)
        updateStateMembership(updated, true)
    }

    suspend fun updateStateMembership(
        entity: MemoEntity,
        ownedMutation: Boolean = false,
    ) {
        val mappable =
            !entity.deleted && entity.parentId == null && entity.state != "ARCHIVED" &&
                entity.visibleMemo().location.hasValidCoordinates()
        if (!mappable) deleteMapMemberships(entity.accountId, entity.localId)
        if (entity.state == "ARCHIVED" || entity.visibility !in setOf("PUBLIC", "PROTECTED") || entity.parentId != null) {
            deleteExploreMemberships(entity.accountId, entity.localId)
        }
        if (entity.parentId != null) {
            deleteOwnMemberships(entity.accountId, entity.localId, "COMMENT")
            return
        }
        if (!ownedMutation && ownMembershipCount(entity.accountId, entity.localId) == 0) return
        val type = if (entity.state == "ARCHIVED") CacheListType.ARCHIVED else CacheListType.USER
        deleteOwnMemberships(entity.accountId, entity.localId, type.name)
        val types = if (ownedMutation && mappable) listOf(type, CacheListType.MAP_USER, CacheListType.MAP_ALL) else listOf(type)
        for (membershipType in types) {
            val key = defaultQueryKey(membershipType)
            if (queryMembershipCount(entity.accountId, entity.localId, key) == 0) {
                putMembership(
                    MemoMembership(
                        entity.accountId,
                        key,
                        entity.localId,
                        membershipType.name,
                        null,
                        0,
                        getQuery(entity.accountId, key)?.generation ?: 0,
                        entity.cachedAt,
                    ),
                )
            }
        }
    }

    @Transaction
    suspend fun beginRefresh(
        accountId: String,
        queryKey: String,
        listType: CacheListType,
        parentName: String?,
        incremental: Boolean = false,
    ): Long {
        val previous = getQuery(accountId, queryKey) ?: CachedQuery(accountId, queryKey, listType.name, parentName)
        val generation = previous.generation + 1
        putQuery(previous.copy(generation = generation, complete = if (incremental) previous.complete else false, nextPageToken = null))
        return generation
    }

    @Transaction
    suspend fun ingestPage(
        accountId: String,
        queryKey: String,
        listType: CacheListType,
        memos: List<Memo>,
        parentName: String?,
        nextPageToken: String?,
        refresh: Boolean,
        incremental: Boolean = false,
        checkpointAt: Long = System.currentTimeMillis(),
        expectedGeneration: Long? = null,
        expectedPageToken: String? = null,
    ) {
        val previous = getQuery(accountId, queryKey) ?: CachedQuery(accountId, queryKey, listType.name, parentName)
        if (expectedGeneration != null &&
            (previous.generation != expectedGeneration || (!refresh && previous.nextPageToken != expectedPageToken))
        ) {
            throw CancellationException("Collection refresh was superseded")
        }
        val generation = if (expectedGeneration == null && refresh && !incremental) previous.generation + 1 else previous.generation
        val start = if (refresh) 0 else nextPosition(accountId, queryKey)
        val now = System.currentTimeMillis()
        memos.forEachIndexed { index, memo ->
            val entity = storeRemote(accountId, memo, parentName)
            putMembership(MemoMembership(accountId, queryKey, entity.localId, listType.name, parentName, start + index, generation, now))
            if (queryKey.endsWith(":history")) {
                val key = defaultQueryKey(listType, parentName)
                putMembership(
                    MemoMembership(
                        accountId,
                        key,
                        entity.localId,
                        listType.name,
                        parentName,
                        start + index,
                        getQuery(accountId, key)?.generation ?: 0L,
                        now,
                    ),
                )
            }
            updateStateMembership(entity)
        }
        val token = nextPageToken?.takeIf { it.isNotBlank() }
        if (token == null && !incremental) pruneQuery(accountId, queryKey, generation)
        if (token == null && !incremental && queryKey.endsWith(":history")) {
            pruneDefaultAfterHistory(accountId, defaultQueryKey(listType, parentName), queryKey, checkpointAt)
        }
        if (token == null && !incremental) deleteCleanOrphans(accountId)
        putQuery(
            previous.copy(
                loaded = true,
                complete = if (incremental) previous.complete else token == null,
                nextPageToken = token,
                generation = generation,
                lastFullSnapshotAt = if (token == null && !incremental) checkpointAt else previous.lastFullSnapshotAt,
                incrementalCursor = if (token == null) checkpointAt else previous.incrementalCursor,
            ),
        )
    }
}
