package org.example.memosm.data.audit

import androidx.room.Entity
import androidx.room.Index

/** A privacy-preserving local record of sync and recovery actions. */
@Entity(
    tableName = "sync_audit_log",
    indices = [Index(value = ["accountHash", "occurredAt"])]
)
data class SyncAuditEntry(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: Long,
    val accountHash: String,
    val event: String,
    val operation: String? = null,
    val outcome: String,
    val targetHash: String? = null,
    val detailCode: String? = null
)
