package org.example.memosm.data.offline

import org.example.memosm.data.store.SnapshotStore
import org.example.memosm.model.Activity
import org.example.memosm.model.InstanceProfile
import org.example.memosm.model.InstanceSetting
import org.example.memosm.model.Shortcut
import org.example.memosm.model.UserGeneralSetting
import org.example.memosm.model.UserSnapshot
import org.example.memosm.model.UserStats
import org.example.memosm.model.UserWebhook

/**
 * Persisted snapshot of the session domain (profile page and other offline
 * surfaces). Stored as [UserSnapshot] because Gson cannot instantiate the
 * `User` interface when reading back.
 *
 * Extensible by adding fields with defaults - old JSON simply deserializes
 * with the defaults. [savedAt] is the time of the last successful network
 * fetch that wrote the snapshot (epoch millis, 0 when unknown).
 */
data class SessionSnapshotData(
    val currUser: UserSnapshot? = null,
    val userStats: UserStats? = null,
    val userSettings: UserGeneralSetting? = null,
    val webhooks: List<UserWebhook> = emptyList(),
    val instanceProfile: InstanceProfile? = null,
    val instanceSettings: InstanceSetting? = null,
    val activities: List<Activity> = emptyList(),
    val shortcuts: List<Shortcut> = emptyList(),
    val savedAt: Long = 0L
)

/**
 * Cache-abstraction facade for the session snapshot domain (current user,
 * stats, settings, activities, webhooks). Business code calls this; the
 * DataStore-backed [SnapshotStore] below it is storage SPI.
 */
class SessionCacheStore(
    private val store: SnapshotStore<SessionSnapshotData>
) {

    /** Current session snapshot of an account, or null. */
    suspend fun get(accountId: String): SessionSnapshotData? = store.get(accountId)

    /** Persist the session snapshot of an account. */
    suspend fun save(accountId: String, snapshot: SessionSnapshotData) =
        store.save(accountId, snapshot)

    /**
     * Delete the session snapshot of an account (called when the account is
     * removed). Clears the `session_snapshot_<accountId>` DataStore key.
     */
    suspend fun clear(accountId: String) = store.clear(accountId)
}
