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
import org.example.memosm.model.AppearancePreferences
import org.example.memosm.model.ThemeMode
import org.example.memosm.model.ColorTheme
import org.example.memosm.model.AppFont
import org.example.memosm.model.Account
import org.example.memosm.model.User
import org.example.memosm.model.toUserSnapshot

class DataStoreManager(
    private val dataStore: DataStore<Preferences>,
    private val onAccountsChanged: () -> Unit = {}
) {

    private val gson = Gson()

    companion object {
        val APPEARANCE_THEME_MODE = stringPreferencesKey("appearance_theme_mode")
        val APPEARANCE_COLOR_THEME = stringPreferencesKey("appearance_color_theme")
        val APPEARANCE_CUSTOM_HUE = androidx.datastore.preferences.core.floatPreferencesKey("appearance_custom_hue")
        val APPEARANCE_FONT = stringPreferencesKey("appearance_font")
        val ACCOUNTS_JSON = stringPreferencesKey("accounts_json")
        val PAGE_SIZE = intPreferencesKey("page_size")
        const val DEFAULT_PAGE_SIZE = 10
        val HEADER_SCALE = androidx.datastore.preferences.core.floatPreferencesKey("header_scale")
        const val DEFAULT_HEADER_SCALE = 1.0f
        val LINK_PREVIEW_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("link_preview_enabled")
        const val DEFAULT_LINK_PREVIEW_ENABLED = true

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
        editPreferences { preferences ->
            preferences[ACCOUNTS_JSON] = gson.toJson(accounts)
        }
        onAccountsChanged()
    }


    // --- Account Helpers ---

    suspend fun getAccounts(): List<Account> {

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
        }

        return final
    }

    private suspend fun mutateAccounts(transform: (List<Account>) -> List<Account>) {
        editPreferences { preferences ->
            val type = object : TypeToken<List<Account>>() {}.type
            val current: List<Account> = preferences[ACCOUNTS_JSON]?.let {
                gson.fromJson(it, type)
            } ?: emptyList()
            val updated = transform(current)
            preferences[ACCOUNTS_JSON] = gson.toJson(updated)
        }
        onAccountsChanged()
    }

    suspend fun addAccount(hostUrl: String, accessToken: String) {
        mutateAccounts { current ->
            val existing = current.firstOrNull {
                it.hostUrl == hostUrl && it.accessToken == accessToken
            }
            if (existing != null) current.map { it.copy(isActive = it.id == existing.id) }
            else current.map { it.copy(isActive = false) } +
                Account(hostUrl = hostUrl, accessToken = accessToken, isActive = true)
        }
    }

    suspend fun setActiveAccount(id: String) {
        mutateAccounts { current ->
            if (current.none { it.id == id }) current
            else current.map { it.copy(isActive = it.id == id) }
        }
    }

    suspend fun updateAccountLastUsed(id: String, timestamp: Long) {
        mutateAccounts { current ->
            current.map { if (it.id == id) it.copy(lastUsed = timestamp) else it }
        }
    }

    suspend fun deleteAccount(id: String) {
        mutateAccounts { current ->
            val remaining = current.filterNot { it.id == id }
            if (remaining.isNotEmpty() && remaining.none { it.isActive }) {
                remaining.mapIndexed { index, account -> account.copy(isActive = index == 0) }
            } else remaining
        }
    }

    suspend fun updateAccount(id: String, hostUrl: String, token: String) {
        mutateAccounts { current ->
            val previous = current.find { it.id == id } ?: return@mutateAccounts current
            fun server(url: String) = url.trim().trimEnd('/').removeSuffix("/api/v1")
            if (server(previous.hostUrl) == server(hostUrl)) {
                // Token renewal retains this account's offline history and pending work.
                current.map { if (it.id == id) it.copy(hostUrl = hostUrl, accessToken = token) else it }
            } else {
                // A different server gets a new namespace. Keep the original credentials
                // and unsynced work available in the original saved account.
                current.map { if (it.id == id) it.copy(isActive = false) else it } +
                    Account(id = java.util.UUID.randomUUID().toString(), hostUrl = hostUrl,
                        accessToken = token, isActive = previous.isActive)
            }
        }
    }

    suspend fun updateAccountUser(id: String, user: User) {
        mutateAccounts { current ->
            current.map {
                if (it.id == id) it.copy(
                    user = user.toUserSnapshot(), name = user.username,
                    displayName = user.displayName, avatarUrl = user.avatarUrl,
                    email = user.email, description = user.description
                ) else it
            }
        }
    }

    suspend fun updateAccountToken(id: String, token: String) {
        mutateAccounts { current ->
            current.map { if (it.id == id) it.copy(accessToken = token) else it }
        }
    }

    val appearance: Flow<AppearancePreferences> = dataStore.data.map { preferences ->
        val hue = preferences[APPEARANCE_CUSTOM_HUE]?.takeIf { it.isFinite() && it in 0f..360f }
        val colorTheme = ColorTheme.entries.firstOrNull { it.name == preferences[APPEARANCE_COLOR_THEME] }
            ?: ColorTheme.SYSTEM
        AppearancePreferences(
            themeMode = ThemeMode.entries.firstOrNull { it.name == preferences[APPEARANCE_THEME_MODE] }
                ?: ThemeMode.SYSTEM,
            colorTheme = if (colorTheme == ColorTheme.CUSTOM && hue == null) ColorTheme.SYSTEM else colorTheme,
            customHue = hue?.rem(360f),
            font = AppFont.entries.firstOrNull { it.name == preferences[APPEARANCE_FONT] } ?: AppFont.SYSTEM
        )
    }.distinctUntilChanged()

    suspend fun saveThemeMode(mode: ThemeMode) {
        dataStore.edit { it[APPEARANCE_THEME_MODE] = mode.name }
    }

    suspend fun saveColorTheme(theme: ColorTheme, hue: Float? = null) {
        require(theme != ColorTheme.CUSTOM || (hue != null && hue.isFinite() && hue in 0f..360f))
        dataStore.edit {
            it[APPEARANCE_COLOR_THEME] = theme.name
            if (hue != null && hue.isFinite() && hue in 0f..360f) {
                it[APPEARANCE_CUSTOM_HUE] = hue % 360f
            }
        }
    }

    suspend fun saveFont(font: AppFont) {
        dataStore.edit { it[APPEARANCE_FONT] = font.name }
    }

    val pageSize: Flow<Int> = dataStore.data.map { preferences ->
        preferences[PAGE_SIZE] ?: DEFAULT_PAGE_SIZE
    }

    suspend fun savePageSize(size: Int) {
        editPreferences { preferences ->
            preferences[PAGE_SIZE] = size
        }
    }

    val headerScale: Flow<Float> = dataStore.data.map { preferences ->
        preferences[HEADER_SCALE] ?: DEFAULT_HEADER_SCALE
    }

    suspend fun saveHeaderScale(scale: Float) {
        editPreferences { preferences ->
            preferences[HEADER_SCALE] = scale
        }
    }

    // --- Offline / pre-download settings ---

    val linkPreviewEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[LINK_PREVIEW_ENABLED] ?: DEFAULT_LINK_PREVIEW_ENABLED
    }

    suspend fun saveLinkPreviewEnabled(enabled: Boolean) {
        dataStore.edit { it[LINK_PREVIEW_ENABLED] = enabled }
    }

    val preDownloadText: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PRE_DOWNLOAD_TEXT] ?: DEFAULT_PRE_DOWNLOAD_TEXT
    }

    suspend fun savePreDownloadText(enabled: Boolean) {
        editPreferences { preferences ->
            preferences[PRE_DOWNLOAD_TEXT] = enabled
        }
    }

    val preDownloadAttachments: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PRE_DOWNLOAD_ATTACHMENTS] ?: DEFAULT_PRE_DOWNLOAD_ATTACHMENTS
    }

    suspend fun savePreDownloadAttachments(enabled: Boolean) {
        editPreferences { preferences ->
            preferences[PRE_DOWNLOAD_ATTACHMENTS] = enabled
        }
    }

    val preDownloadWifiOnly: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PRE_DOWNLOAD_WIFI_ONLY] ?: DEFAULT_PRE_DOWNLOAD_WIFI_ONLY
    }

    suspend fun savePreDownloadWifiOnly(enabled: Boolean) {
        editPreferences { preferences ->
            preferences[PRE_DOWNLOAD_WIFI_ONLY] = enabled
        }
    }

    val preDownloadExplore: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PRE_DOWNLOAD_EXPLORE] ?: DEFAULT_PRE_DOWNLOAD_EXPLORE
    }

    suspend fun savePreDownloadExplore(enabled: Boolean) {
        editPreferences { preferences ->
            preferences[PRE_DOWNLOAD_EXPLORE] = enabled
        }
    }

    val attachmentCacheMaxMb: Flow<Int> = dataStore.data.map { preferences ->
        preferences[ATTACHMENT_CACHE_MAX_MB] ?: DEFAULT_ATTACHMENT_CACHE_MAX_MB
    }

    suspend fun saveAttachmentCacheMaxMb(mb: Int) {
        editPreferences { preferences ->
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
        editPreferences { preferences ->
            preferences[lastSyncTimeKey(accountId)] = timestamp
        }
    }

    private fun textSyncCursorKey(accountId: String) =
        androidx.datastore.preferences.core.longPreferencesKey("text_sync_cursor_$accountId")

    // The legacy global cursor has unknown ownership and must never be inherited.
    fun textSyncCursor(accountId: String): Flow<Long> = dataStore.data.map { preferences ->
        preferences[textSyncCursorKey(accountId)] ?: 0L
    }

    suspend fun saveTextSyncCursor(accountId: String, timestamp: Long) {
        editPreferences { preferences ->
            preferences[textSyncCursorKey(accountId)] = timestamp
        }
    }

    suspend fun removeDownloadState(accountId: String) {
        editPreferences { preferences ->
            preferences.remove(textSyncCursorKey(accountId))
            preferences.remove(lastPreDownloadAtKey(accountId))
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
        editPreferences { preferences ->
            preferences[lastPreDownloadAtKey(accountId)] = timestamp
        }
    }

    // --- Per-tier cache size limits ---

    val textCacheMaxMb: Flow<Int> = dataStore.data.map { preferences ->
        preferences[TEXT_CACHE_MAX_MB] ?: DEFAULT_TEXT_CACHE_MAX_MB
    }

    suspend fun saveTextCacheMaxMb(mb: Int) {
        editPreferences { preferences ->
            preferences[TEXT_CACHE_MAX_MB] = mb
        }
    }

    val themeCacheMaxMb: Flow<Int> = dataStore.data.map { preferences ->
        preferences[THEME_CACHE_MAX_MB] ?: DEFAULT_THEME_CACHE_MAX_MB
    }

    suspend fun saveThemeCacheMaxMb(mb: Int) {
        editPreferences { preferences ->
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
        editPreferences { preferences ->
            preferences[snapshotJsonKey(domain, accountId)] = json
        }
    }

    suspend fun removeSnapshot(domain: String, accountId: String) {
        editPreferences { preferences ->
            preferences.remove(snapshotJsonKey(domain, accountId))
        }
    }

    suspend fun removeLastSyncTime(accountId: String) {
        editPreferences { preferences ->
            preferences.remove(lastSyncTimeKey(accountId))
        }
    }

    private suspend fun editPreferences(action: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) =
        org.example.memosm.data.backup.BackupCoordinator.withStorageLock { dataStore.edit(action) }

    private val portableKeys: List<Preferences.Key<*>> get() = listOf(
        APPEARANCE_THEME_MODE, APPEARANCE_COLOR_THEME, APPEARANCE_CUSTOM_HUE, APPEARANCE_FONT,
        PAGE_SIZE, HEADER_SCALE, LINK_PREVIEW_ENABLED, PRE_DOWNLOAD_TEXT, PRE_DOWNLOAD_ATTACHMENTS,
        PRE_DOWNLOAD_WIFI_ONLY, PRE_DOWNLOAD_EXPLORE, ATTACHMENT_CACHE_MAX_MB, TEXT_CACHE_MAX_MB, THEME_CACHE_MAX_MB
    )

    suspend fun backupSettings(): com.google.gson.JsonObject {
        val values = dataStore.data.first().asMap().mapKeys { it.key.name }
        return com.google.gson.JsonObject().apply {
            portableKeys.forEach { key -> values[key.name]?.let { add(key.name, gson.toJsonTree(it)) } }
        }
    }

    suspend fun backupSnapshot(domain: String, accountId: String): com.google.gson.JsonObject? =
        snapshotJson(domain, accountId).first()?.let { com.google.gson.JsonParser.parseString(it).asJsonObject }

    fun validateBackupSettings(settings: com.google.gson.JsonObject) {
        require(settings.keySet().all { name -> portableKeys.any { it.name == name } }) { "Unknown backup setting" }
        settings.entrySet().forEach { (name, value) ->
            require(value.isJsonPrimitive) { "Invalid backup setting" }
            when (name) {
                APPEARANCE_THEME_MODE.name -> org.example.memosm.model.ThemeMode.valueOf(value.asString)
                APPEARANCE_COLOR_THEME.name -> org.example.memosm.model.ColorTheme.valueOf(value.asString)
                APPEARANCE_FONT.name -> org.example.memosm.model.AppFont.valueOf(value.asString)
                APPEARANCE_CUSTOM_HUE.name, HEADER_SCALE.name -> require(value.asJsonPrimitive.isNumber && value.asFloat.isFinite())
                PAGE_SIZE.name, ATTACHMENT_CACHE_MAX_MB.name, TEXT_CACHE_MAX_MB.name, THEME_CACHE_MAX_MB.name ->
                    require(value.asJsonPrimitive.isNumber && value.asBigDecimal.stripTrailingZeros().scale() <= 0 && value.asLong in 1..Int.MAX_VALUE.toLong())
                else -> require(value.asJsonPrimitive.isBoolean) { "Invalid boolean setting" }
            }
        }
    }

    /** One atomic preferences update; cache freshness state is deliberately never inherited. */
    suspend fun applyBackup(manifest: org.example.memosm.data.backup.BackupManifest) {
        val categories = manifest.categories
        editPreferences { prefs ->
            if (org.example.memosm.data.backup.BackupCategory.SETTINGS in categories) {
                val values = manifest.settings!!
                validateBackupSettings(values)
                portableKeys.forEach { key ->
                    prefs.remove(key)
                    val value = values.get(key.name) ?: return@forEach
                    when (key) {
                        APPEARANCE_THEME_MODE, APPEARANCE_COLOR_THEME, APPEARANCE_FONT -> prefs[stringPreferencesKey(key.name)] = value.asString
                        APPEARANCE_CUSTOM_HUE, HEADER_SCALE -> prefs[androidx.datastore.preferences.core.floatPreferencesKey(key.name)] = value.asFloat
                        PAGE_SIZE, ATTACHMENT_CACHE_MAX_MB, TEXT_CACHE_MAX_MB, THEME_CACHE_MAX_MB -> prefs[intPreferencesKey(key.name)] = value.asInt
                        else -> prefs[androidx.datastore.preferences.core.booleanPreferencesKey(key.name)] = value.asBoolean
                    }
                }
            }
            manifest.accounts.forEach { data ->
                val id = data.identity.id
                if (org.example.memosm.data.backup.BackupCategory.CACHE in categories) {
                    listOf("session" to data.session, "notifications" to data.notifications).forEach { (domain, obj) ->
                        val key = snapshotJsonKey(domain, id)
                        if (obj == null) prefs.remove(key) else prefs[key] = gson.toJson(obj)
                    }
                }
                if (org.example.memosm.data.backup.BackupCategory.CACHE in categories || org.example.memosm.data.backup.BackupCategory.MEDIA in categories) {
                    prefs.remove(textSyncCursorKey(id)); prefs.remove(lastPreDownloadAtKey(id)); prefs.remove(lastSyncTimeKey(id))
                }
                if (org.example.memosm.data.backup.BackupCategory.QUEUED_EDITS in categories) {
                    prefs[stringPreferencesKey("restored_edits_$id")] = gson.toJson(data.queuedEdits)
                }
            }
            if (org.example.memosm.data.backup.BackupCategory.ACCOUNTS in categories) {
                val type = object : TypeToken<List<Account>>() {}.type
                val current: List<Account> = prefs[ACCOUNTS_JSON]?.let { gson.fromJson(it, type) } ?: emptyList()
                val restored = manifest.accounts.mapNotNull { it.account }
                val replacements = restored.associateBy { it.id }
                val combined = current.map { replacements[it.id] ?: it } + restored.filter { account -> current.none { it.id == account.id } }
                val active = restored.firstOrNull { it.isActive }?.id ?: current.firstOrNull { it.isActive }?.id ?: combined.firstOrNull()?.id
                prefs[ACCOUNTS_JSON] = gson.toJson(combined.map { it.copy(isActive = it.id == active) })
            }
        }
        if (org.example.memosm.data.backup.BackupCategory.ACCOUNTS in categories) onAccountsChanged()
    }

    fun restoredEdits(accountId: String): Flow<com.google.gson.JsonArray> =
        dataStore.data.map { prefs -> prefs[stringPreferencesKey("restored_edits_$accountId")]?.let { com.google.gson.JsonParser.parseString(it).asJsonArray } ?: com.google.gson.JsonArray() }

    suspend fun dismissRestoredEdit(accountId: String, editId: String) {
        editPreferences { prefs ->
            val key = stringPreferencesKey("restored_edits_$accountId")
            val edits = prefs[key]?.let { com.google.gson.JsonParser.parseString(it).asJsonArray } ?: return@editPreferences
            val remaining = com.google.gson.JsonArray().apply { edits.filter { it.asJsonObject.get("id").asString != editId }.forEach { add(it) } }
            prefs[key] = gson.toJson(remaining)
        }
    }

}
