package org.example.memosm.data.store

import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import org.example.memosm.data.DataStoreManager
import java.lang.reflect.Type

/**
 * [SnapshotStore] adapter backed by the preferences DataStore via
 * [DataStoreManager]'s per-domain snapshot mechanism (keys of the form
 * `<domain>_snapshot_<accountId>`; the "session" domain reuses the exact key
 * the existing session snapshot already uses, so stored data stays compatible).
 *
 * A plain [Gson] instance is the default serializer to match the existing
 * snapshot writers (`UserDelegate` persists with `Gson()`), guaranteeing
 * round-trip compatibility with already-stored snapshots.
 *
 * Business code must not use this class directly - it goes through the
 * per-domain facades in `data/offline/` (e.g. `SessionCacheStore`).
 */
class DataStoreSnapshotStore<T>(
    private val dataStoreManager: DataStoreManager,
    private val domain: String,
    private val type: Type,
    private val gson: Gson = Gson()
) : SnapshotStore<T> {

    override suspend fun get(accountId: String): T? =
        dataStoreManager.snapshotJson(domain, accountId).first()?.let { decode(it) }

    override suspend fun save(accountId: String, value: T) {
        dataStoreManager.saveSnapshotJson(domain, accountId, gson.toJson(value))
    }

    override suspend fun clear(accountId: String) {
        dataStoreManager.removeSnapshot(domain, accountId)
    }

    private fun decode(json: String): T? = try {
        gson.fromJson<T>(json, type)
    } catch (e: Exception) {
        null
    }
}
