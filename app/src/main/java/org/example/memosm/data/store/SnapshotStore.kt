package org.example.memosm.data.store

/**
 * Storage SPI: a single JSON-serializable snapshot per account and domain.
 *
 * Layering rule: business code (ViewModels, delegates) must never talk to this
 * interface directly - it goes through the per-domain facades in
 * `data/offline/` (e.g. `SessionCacheStore`). This package is the storage SPI
 * that adapts the existing implementations (Room, DataStore, filesystem)
 * behind a small, stable contract.
 *
 * Serialization uses Gson, matching the rest of the app (`DataStoreManager`,
 * `CachedMemo`, `UserDelegate`'s session snapshot).
 */
interface SnapshotStore<T> {

    /** Return the current snapshot for an account, or null. */
    suspend fun get(accountId: String): T?

    /** Persist the snapshot for an account. */
    suspend fun save(accountId: String, value: T)

    /** Delete the snapshot for an account (e.g. when the account is removed). */
    suspend fun clear(accountId: String)
}
