package org.example.memosm.data.offline

import org.example.memosm.data.store.SnapshotStore
import org.example.memosm.model.UserNotification

/**
 * Persisted snapshot of the notifications domain: the most recent successful
 * first page of the user's notifications, so the notifications screen can
 * show last-known data offline instead of an error. [savedAt] is the time of
 * that successful fetch (epoch millis, 0 when unknown).
 */
data class NotificationsSnapshotData(
    val notifications: List<UserNotification> = emptyList(),
    val savedAt: Long = 0L
)

/**
 * Cache-abstraction facade for the notifications snapshot domain. Business
 * code calls this; the DataStore-backed [SnapshotStore] below it is storage
 * SPI.
 */
class NotificationCacheStore(
    private val store: SnapshotStore<NotificationsSnapshotData>
) {

    /** Current notifications snapshot of an account, or null. */
    suspend fun get(accountId: String): NotificationsSnapshotData? = store.get(accountId)

    /** Persist the notifications snapshot of an account. */
    suspend fun save(accountId: String, snapshot: NotificationsSnapshotData) =
        store.save(accountId, snapshot)

    /** Delete the notifications snapshot (when the account is removed). */
    suspend fun clear(accountId: String) = store.clear(accountId)
}
