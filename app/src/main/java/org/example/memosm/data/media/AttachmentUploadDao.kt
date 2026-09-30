package org.example.memosm.data.media

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AttachmentUploadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(upload: AttachmentUpload)

    @Query("SELECT * FROM attachment_uploads WHERE accountId = :accountId ORDER BY createdAt ASC")
    suspend fun getForAccount(accountId: String): List<AttachmentUpload>

    @Query("SELECT * FROM attachment_uploads WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AttachmentUpload?

    @Query("DELETE FROM attachment_uploads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE attachment_uploads SET attemptCount = :attemptCount, lastError = :lastError, lastAttemptAt = :lastAttemptAt WHERE id = :id")
    suspend fun markFailed(id: String, attemptCount: Int, lastError: String?, lastAttemptAt: Long)

    @Query("DELETE FROM attachment_uploads WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)
}
