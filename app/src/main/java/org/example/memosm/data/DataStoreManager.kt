package org.example.memosm.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.example.memosm.model.Account
import org.example.memosm.model.User
import org.example.memosm.model.toUserSnapshot

class DataStoreManager(private val dataStore: DataStore<Preferences>) {

    private val gson = Gson()
    private var cachedAccounts: List<Account>? = null

    companion object {
        val ACCOUNTS_JSON = stringPreferencesKey("accounts_json")
        val PAGE_SIZE = intPreferencesKey("page_size")
        const val DEFAULT_PAGE_SIZE = 10
        val HEADER_SCALE = androidx.datastore.preferences.core.floatPreferencesKey("header_scale")
        const val DEFAULT_HEADER_SCALE = 1.0f

        // --- Offline / pre-download settings ---
        val PRE_DOWNLOAD_TEXT = androidx.datastore.preferences.core.booleanPreferencesKey("pre_download_text")
        const val DEFAULT_PRE_DOWNLOAD_TEXT = true
        val PRE_DOWNLOAD_ATTACHMENTS =
            androidx.datastore.preferences.core.booleanPreferencesKey("pre_download_attachments")
        const val DEFAULT_PRE_DOWNLOAD_ATTACHMENTS = true
        val PRE_DOWNLOAD_WIFI_ONLY =
            androidx.datastore.preferences.core.booleanPreferencesKey("pre_download_wifi_only")
        const val DEFAULT_PRE_DOWNLOAD_WIFI_ONLY = true
        val PRE_DOWNLOAD_EXPLORE =
            androidx.datastore.preferences.core.booleanPreferencesKey("pre_download_explore")
        const val DEFAULT_PRE_DOWNLOAD_EXPLORE = false
        val ATTACHMENT_CACHE_MAX_MB = intPreferencesKey("attachment_cache_max_mb")
        const val DEFAULT_ATTACHMENT_CACHE_MAX_MB = 250
        val TEXT_CACHE_MAX_MB = intPreferencesKey("text_cache_max_mb")
        const val DEFAULT_TEXT_CACHE_MAX_MB = 100
        val THEME_CACHE_MAX_MB = intPreferencesKey("theme_cache_max_mb")
        const val DEFAULT_THEME_CACHE_MAX_MB = 200
        // Incremental-sync cursor for the text pre-downloader. Deliberately
        // separate from the per-account last-sync time (which SyncManager
        // updates): only the pre-downloader writes this, so syncNow() cannot
        // shrink the "what changed since the last full download" window and
        // cause updates to be skipped.
        val TEXT_SYNC_CURSOR = androidx.datastore.preferences.core.longPreferencesKey("text_sync_cursor")
    }

    val accounts: Flow<List<Account>> = dataStore.data
        .map { it[ACCOUNTS_JSON] }
        .distinctUntilChanged()
        .map { json ->
            if (json.isNullOrEmpty()) {
                emptyList()
            } else {
                val type = object : TypeToken<List<Account>>() {}.type
                try {
                    val list: List<Account> = gson.fromJson(json, type)
                    cachedAccounts = list
                    list
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }

    val account: Flow<Account?> = accounts.map { list ->
        list.find { it.isActive }
    }

    suspend fun saveAccounts(accounts: List<Account>) {
        dataStore.edit { preferences ->
            preferences[ACCOUNTS_JSON] = gson.toJson(accounts)
        }
        cachedAccounts = accounts
    }


    // --- Account Helpers ---

    suspend fun getAccounts(): List<Account> {
        cachedAccounts?.let { return it }

        val json = dataStore.data.map { it[ACCOUNTS_JSON] }.first()

        if (json.isNullOrEmpty()) {
            return emptyList()
        }

        val type = object : TypeToken<List<Account>>() {}.type
        val list: List<Account> = try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }

        var needsSave = false
        val sanitized = list.map { account ->
            @Suppress("SENSELESS_COMPARISON") if (account.id == null || account.hostUrl == null || account.accessToken == null) {
                needsSave = true
                @Suppress("USELESS_ELVIS") account.copy(
                    id = account.id ?: java.util.UUID.randomUUID().toString(),
                    hostUrl = account.hostUrl ?: "",
                    accessToken = account.accessToken ?: ""
                )
            } else {
                account
            }
        }

        val final = if (sanitized.isNotEmpty() && sanitized.none { it.isActive }) {
            needsSave = true
            sanitized.mapIndexed { index, account ->
                if (index == 0) account.copy(isActive = true) else account
            }
        } else {
            sanitized
        }

        if (needsSave) {
            saveAccounts(final)
        } else {
            cachedAccounts = final
        }

        return final
    }

    suspend fun addAccount(
        hostUrl: String,
        accessToken: String
    ) {
        val current = getAccounts().toMutableList()
        // Check if account already exists to avoid duplicates
        val existingIndex =
            current.indexOfFirst { it.hostUrl == hostUrl && it.accessToken == accessToken }

        if (existingIndex != -1) {
            // Just activate it
            setActiveAccount(current[existingIndex].id)
        } else {
            // Deactivate others
            val updated = current.map { it.copy(isActive = false) }.toMutableList()
            updated.add(
                Account(
                    hostUrl = hostUrl,
                    accessToken = accessToken,
                    isActive = true
                )
            )
            saveAccounts(updated)
        }
    }

    suspend fun setActiveAccount(id: String) {
        val current = getAccounts()
        val updated = current.map { it.copy(isActive = it.id == id) }
        saveAccounts(updated)
    }

    suspend fun updateAccountLastUsed(id: String, timestamp: Long) {
        val current = getAccounts()
        val updated = current.map {
            if (it.id == id) it.copy(lastUsed = timestamp) else it
        }
        saveAccounts(updated)
    }

    suspend fun deleteAccount(id: String) {
        val current = getAccounts()
        val updated = current.filterNot { it.id == id }
        saveAccounts(updated)

        // If we deleted the active one, set new active
        if (updated.isNotEmpty() && updated.none { it.isActive }) {
            setActiveAccount(updated.first().id)
        }
    }

    suspend fun updateAccount(
        id: String,
        hostUrl: String,
        token: String
    ) {
        val current = getAccounts()
        val updated = current.map {
            if (it.id == id) {
                it.copy(hostUrl = hostUrl, accessToken = token)
            } else it
        }
        saveAccounts(updated)
    }

    suspend fun updateAccountUser(id: String, user: User) {
        val current = getAccounts()
        val updated = current.map {
            if (it.id == id) {
                it.copy(
                    user = user.toUserSnapshot(),
                    name = user.username,
                    displayName = user.displayName,
                    avatarUrl = user.avatarUrl,
                    email = user.email,
                    description = user.description
                )
            } else it
        }
        saveAccounts(updated)
    }

    suspend fun updateAccountToken(id: String, token: String) {
        val current = getAccounts()
        val updated = current.map {
            if (it.id == id) {
                it.copy(accessToken = token)
            } else it
        }
        saveAccounts(updated)
    }

    val pageSize: Flow<Int> = dataStore.data.map { preferences ->
        preferences[PAGE_SIZE] ?: DEFAULT_PAGE_SIZE
    }

    suspend fun savePageSize(size: Int) {
        dataStore.edit { preferences ->
            preferences[PAGE_SIZE] = size
        }
    }

    val headerScale: Flow<Float> = dataStore.data.map { preferences ->
        preferences[HEADER_SCALE] ?: DEFAULT_HEADER_SCALE
    }

    suspend fun saveHeaderScale(scale: Float) {
        dataStore.edit { preferences ->
            preferences[HEADER_SCALE] = scale
        }
    }

    // --- Offline / pre-download settings ---

    val preDownloadText: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PRE_DOWNLOAD_TEXT] ?: DEFAULT_PRE_DOWNLOAD_TEXT
    }

    suspend fun savePreDownloadText(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PRE_DOWNLOAD_TEXT] = enabled
        }
    }

    val preDownloadAttachments: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PRE_DOWNLOAD_ATTACHMENTS] ?: DEFAULT_PRE_DOWNLOAD_ATTACHMENTS
    }

    suspend fun savePreDownloadAttachments(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PRE_DOWNLOAD_ATTACHMENTS] = enabled
        }
    }

    val preDownloadWifiOnly: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PRE_DOWNLOAD_WIFI_ONLY] ?: DEFAULT_PRE_DOWNLOAD_WIFI_ONLY
    }

    suspend fun savePreDownloadWifiOnly(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PRE_DOWNLOAD_WIFI_ONLY] = enabled
        }
    }

    val preDownloadExplore: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PRE_DOWNLOAD_EXPLORE] ?: DEFAULT_PRE_DOWNLOAD_EXPLORE
    }

    suspend fun savePreDownloadExplore(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PRE_DOWNLOAD_EXPLORE] = enabled
        }
    }

    val attachmentCacheMaxMb: Flow<Int> = dataStore.data.map { preferences ->
        preferences[ATTACHMENT_CACHE_MAX_MB] ?: DEFAULT_ATTACHMENT_CACHE_MAX_MB
    }

    suspend fun saveAttachmentCacheMaxMb(mb: Int) {
        dataStore.edit { preferences ->
            preferences[ATTACHMENT_CACHE_MAX_MB] = mb
        }
    }

    /**
     * Last successful sync time, stored per account (like the session
     * snapshot): a global key would leak one account's sync time into every
     * other account's UI.
     */
    fun lastSyncTimeKey(accountId: String): Preferences.Key<Long> =
        androidx.datastore.preferences.core.longPreferencesKey("last_sync_time_$accountId")

    fun lastSyncTime(accountId: String?): Flow<Long> {
        if (accountId == null) return flowOf(0L)
        return dataStore.data.map { preferences ->
            preferences[lastSyncTimeKey(accountId)] ?: 0L
        }
    }

    suspend fun saveLastSyncTime(accountId: String, timestamp: Long) {
        dataStore.edit { preferences ->
            preferences[lastSyncTimeKey(accountId)] = timestamp
        }
    }

    val textSyncCursor: Flow<Long> = dataStore.data.map { preferences ->
        preferences[TEXT_SYNC_CURSOR] ?: 0L
    }

    suspend fun saveTextSyncCursor(timestamp: Long) {
        dataStore.edit { preferences ->
            preferences[TEXT_SYNC_CURSOR] = timestamp
        }
    }

    /**
     * Last pre-download completion time, stored per account. Unlike the
     * in-memory cooldown in PreDownloadManager (lost on process restart),
     * this survives relaunches so a fresh start right after a completed
     * pre-download doesn't re-trigger the whole download again.
     */
    fun lastPreDownloadAtKey(accountId: String): Preferences.Key<Long> =
        androidx.datastore.preferences.core.longPreferencesKey("last_pre_download_at_$accountId")

    fun lastPreDownloadAt(accountId: String?): Flow<Long> {
        if (accountId == null) return flowOf(0L)
        return dataStore.data.map { preferences ->
            preferences[lastPreDownloadAtKey(accountId)] ?: 0L
        }
    }

    suspend fun saveLastPreDownloadAt(accountId: String, timestamp: Long) {
        dataStore.edit { preferences ->
            preferences[lastPreDownloadAtKey(accountId)] = timestamp
        }
    }

    // --- Per-tier cache size limits ---

    val textCacheMaxMb: Flow<Int> = dataStore.data.map { preferences ->
        preferences[TEXT_CACHE_MAX_MB] ?: DEFAULT_TEXT_CACHE_MAX_MB
    }

    suspend fun saveTextCacheMaxMb(mb: Int) {
        dataStore.edit { preferences ->
            preferences[TEXT_CACHE_MAX_MB] = mb
        }
    }

    val themeCacheMaxMb: Flow<Int> = dataStore.data.map { preferences ->
        preferences[THEME_CACHE_MAX_MB] ?: DEFAULT_THEME_CACHE_MAX_MB
    }

    suspend fun saveThemeCacheMaxMb(mb: Int) {
        dataStore.edit { preferences ->
            preferences[THEME_CACHE_MAX_MB] = mb
        }
    }

    // --- Generic per-domain snapshots ---
    // Key scheme "<domain>_snapshot_<accountId>" (the session snapshot uses
    // domain "session"): the snapshot of one account must never be restored
    // into another account's session (e.g. after an offline account switch).

    fun snapshotJsonKey(domain: String, accountId: String): Preferences.Key<String> =
        stringPreferencesKey("${domain}_snapshot_$accountId")

    fun snapshotJson(domain: String, accountId: String): Flow<String?> =
        dataStore.data.map { preferences ->
            preferences[snapshotJsonKey(domain, accountId)]
        }

    suspend fun saveSnapshotJson(domain: String, accountId: String, json: String) {
        dataStore.edit { preferences ->
            preferences[snapshotJsonKey(domain, accountId)] = json
        }
    }

    suspend fun removeSnapshot(domain: String, accountId: String) {
        dataStore.edit { preferences ->
            preferences.remove(snapshotJsonKey(domain, accountId))
        }
    }

    suspend fun removeLastSyncTime(accountId: String) {
        dataStore.edit { preferences ->
            preferences.remove(lastSyncTimeKey(accountId))
        }
    }
}
