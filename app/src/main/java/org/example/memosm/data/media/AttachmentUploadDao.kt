package org.example.memosm.data.media

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AttachmentUploadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(upload: AttachmentUpload)

    @Query("SELECT * FROM attachment_uploads")
    suspend fun getAll(): List<AttachmentUpload>

    @Query("SELECT * FROM attachment_uploads WHERE accountId = :accountId ORDER BY createdAt ASC")
    suspend fun getForAccount(accountId: String): List<AttachmentUpload>

    @Query("SELECT * FROM attachment_uploads WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AttachmentUpload?

    @Query("DELETE FROM attachment_uploads WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        "UPDATE attachment_uploads SET attemptCount = :attemptCount, lastError = :lastError, lastAttemptAt = :lastAttemptAt WHERE id = :id",
    )
    suspend fun markFailed(
        id: String,
        attemptCount: Int,
        lastError: String?,
        lastAttemptAt: Long,
    )

    @Query(
        "UPDATE attachment_uploads SET serverJson = :serverJson, lastError = NULL, permanentlyFailed = 0, authRequired = 0 WHERE id = :id",
    )
    suspend fun complete(
        id: String,
        serverJson: String,
    )

    @Query("UPDATE attachment_uploads SET permanentlyFailed = :permanentlyFailed, authRequired = :authRequired WHERE id = :id")
    suspend fun setFailure(
        id: String,
        permanentlyFailed: Boolean,
        authRequired: Boolean,
    )

    @Query(
        "UPDATE attachment_uploads SET permanentlyFailed = 0, authRequired = 0, lastError = NULL WHERE accountId = :accountId AND serverJson IS NULL",
    )
    suspend fun retryAll(accountId: String)

    @Query("UPDATE attachment_uploads SET authRequired = 0, lastError = NULL WHERE accountId = :accountId AND authRequired = 1")
    suspend fun retryAuthentication(accountId: String)

    @Query("DELETE FROM attachment_uploads WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)
}
