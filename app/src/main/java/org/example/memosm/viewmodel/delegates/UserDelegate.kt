package org.example.memosm.viewmodel.delegates

import org.example.memosm.R
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.memosm.viewmodel.AccountContext
import org.example.memosm.viewmodel.AccountSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.memosm.api.MemosApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.offline.SessionCacheStore
import org.example.memosm.data.offline.SessionSnapshotData
import org.example.memosm.model.Account
import org.example.memosm.model.InstanceSetting
import org.example.memosm.model.User
import org.example.memosm.model.UserGeneralSetting
import org.example.memosm.model.UserSetting
import org.example.memosm.model.Visibility
import org.example.memosm.model.toUserSnapshot
import org.example.memosm.viewmodel.MemosUiState
import org.example.memosm.viewmodel.UiMessage

interface UserDelegate {
    suspend fun restoreCachedSessionNow(replace: Boolean = false)
    suspend fun reconcileAccount()
    fun restoreCachedSession()
    suspend fun fetchUsers(names: List<String>)
    fun fetchCurrentUser(
        onUserFetched: suspend (User) -> Unit = {}
    )

    suspend fun fetchInstanceProfile()
    suspend fun fetchInstanceSettings()
    fun refreshInstanceSettings()
    suspend fun fetchUserStats(userResourceName: String)
    fun refreshUserStats()
    suspend fun fetchActivities()
    suspend fun fetchUserSettings(userResourceName: String)
    fun updateUserGeneralSetting(locale: String? = null, memoVisibility: Visibility? = null)
    fun updateUserProfile(
        username: String? = null,
        email: String? = null,
        displayName: String? = null,
        avatarUrl: String? = null,
        description: String? = null,
        password: String? = null,
        onResult: (Boolean) -> Unit = {}
    )

    fun addAccount(hostUrl: String, token: String)
    fun removeAccount(account: Account)
    fun updateAccountCredentials(account: Account, hostUrl: String, token: String)
    fun updateCurrentAccountInList()
    fun switchAccount(account: Account)
}

class UserDelegateImpl(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MemosUiState>,
    private val dataStoreManager: DataStoreManager,
    private val sessionCacheStore: SessionCacheStore,
    private val accountSession: AccountSession,
    private val onAccountSwitched: suspend (Account?) -> Unit,
    private val onAccountRemoved: (Account) -> Unit = {}
) : UserDelegate {

    private val retryUserAfter = mutableMapOf<Pair<Long, String>, Long>()
    private val pendingUserRequests = mutableSetOf<Pair<Long, String>>()
    private val accountMutex = Mutex()


    /**
     * Offline fallback snapshot of the session's statistics/data, persisted
     * through [SessionCacheStore] after every successful fetch and restored
     * when a fetch fails (e.g. offline), so stats stay available offline.
     *
     * [SessionSnapshotData.currUser] is stored as a `UserSnapshot` (a concrete
     * data class): Gson cannot instantiate the [User] interface when reading
     * the snapshot back.
     */
    private fun persistSessionSnapshot(context: AccountContext) {
        if (!accountSession.isCurrent(context)) return
        val accountId = context.account.id
        val state = uiState.value
        val s = state.session
        accountSession.readScope.launch {
            runCatching {
                sessionCacheStore.save(
                    accountId,
                    SessionSnapshotData(
                        currUser = s.currUser?.toUserSnapshot(),
                        userStats = s.userStats,
                        userSettings = s.userSettings,
                        webhooks = s.webhooks,
                        instanceProfile = s.instanceProfile,
                        instanceSettings = s.instanceSettings,
                        activities = s.activities,
                        shortcuts = state.userMemoList.shortcuts,
                        savedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun restoreSessionSnapshot(context: AccountContext) {
        accountSession.readScope.launch { readSessionSnapshot(context) }
    }

    private suspend fun readSessionSnapshot(context: AccountContext, replace: Boolean = false) {
        val snap = try {
            sessionCacheStore.get(context.account.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return
        accountSession.update(uiState, context) { state ->
            val cur = state.session
            state.copy(
                session = cur.copy(
                    currUser = if (replace) snap.currUser ?: cur.currUser else cur.currUser ?: snap.currUser,
                    userStats = if (replace) snap.userStats else cur.userStats ?: snap.userStats,
                    userSettings = if (replace) snap.userSettings else cur.userSettings ?: snap.userSettings,
                    webhooks = if (replace) snap.webhooks else cur.webhooks.ifEmpty { snap.webhooks },
                    instanceProfile = if (replace) snap.instanceProfile else cur.instanceProfile ?: snap.instanceProfile,
                    instanceSettings = if (replace) snap.instanceSettings else cur.instanceSettings ?: snap.instanceSettings,
                    activities = if (replace) snap.activities else cur.activities.ifEmpty { snap.activities }
                ),
                userMemoList = state.userMemoList.let { list ->
                    if (replace || list.shortcuts.isEmpty()) list.copy(shortcuts = snap.shortcuts) else list
                }
            )
        }
    }

    override suspend fun restoreCachedSessionNow(replace: Boolean) {
        val context = accountSession.current ?: return
        readSessionSnapshot(context, replace)
    }

    override suspend fun reconcileAccount() {
        accountMutex.withLock { refreshAccountsLocked() }
    }

    override fun restoreCachedSession() {
        val context = accountSession.current ?: return
        restoreSessionSnapshot(context)
    }

    override suspend fun fetchUsers(names: List<String>) {
        val context = accountSession.current ?: return
        val api = context.api
        val currentUser = uiState.value.session.currUser
        if (currentUser?.name != null && currentUser.name !in uiState.value.users)
            accountSession.update(uiState, context) { it.copy(users = it.users + (currentUser.name!! to currentUser)) }
        val currentUsers = uiState.value.users
        val now = System.nanoTime() / 1_000_000
        retryUserAfter.entries.removeAll { it.key.first != context.generation || it.value <= now }
        val toFetch = names.distinct().filter { it !in currentUsers &&
            (context.generation to it) !in pendingUserRequests && (context.generation to it) !in retryUserAfter }
        Log.d("MemosUsers", "fetchUsers: requested=$names cached=${currentUsers.keys} toFetch=$toFetch")
        if (toFetch.isEmpty()) return

        pendingUserRequests.addAll(toFetch.map { context.generation to it })
        accountSession.readScope.launch {
            try {
                // Failed or absent creators must not retry on every list/cache state update.
                toFetch.forEach { retryUserAfter[context.generation to it] = System.nanoTime() / 1_000_000 + 60_000 }
                val fetchedUsers = api.getUsers(toFetch).orEmpty()
                Log.d("MemosUsers", "fetchUsers: resolved=${fetchedUsers.keys}")
                if (fetchedUsers.isNotEmpty()) {
                    fetchedUsers.keys.forEach { retryUserAfter.remove(context.generation to it) }
                    accountSession.update(uiState, context) { it.copy(users = it.users + fetchedUsers) }
                }
                val unresolved = toFetch.filter { it !in fetchedUsers }
                if (unresolved.isNotEmpty()) {
                    Log.w("MemosUsers", "fetchUsers: unresolved=$unresolved")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Error fetching users $toFetch", e)
            } finally {
                pendingUserRequests.removeAll(toFetch.map { context.generation to it }.toSet())
            }
        }
    }

    override fun fetchCurrentUser(
        onUserFetched: suspend (User) -> Unit
    ) {
        val context = accountSession.current ?: return
        val api = context.api
        accountSession.readScope.launch {
            try {
                val user = api.getCurrentSession().user
                Log.d("MemosViewModel", "fetchCurrentUser: user=$user")
                if (user != null) {
                    accountSession.update(uiState, context) {
                        Log.d("MemosViewModel", "Updating session with user: ${user.name}")
                        it.copy(session = it.session.copy(currUser = user))
                    }

                    // Store user in local account for offline access
                    val activeAccount = context.account
                    dataStoreManager.updateAccountUser(activeAccount.id, user)

                    if (!accountSession.isCurrent(context)) return@launch
                    onUserFetched(user)

                    val resourceName = user.name ?: ""
                    if (resourceName.isNotBlank()) {

                        launch { fetchUserSettings(resourceName) }
                        launch { fetchUserStats(resourceName) }
                        launch { fetchActivities() }
                    }

                    fetchInstanceProfile()
                    fetchInstanceSettings()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Error fetching current user", e)
                // Offline fallback: serve the previously stored user snapshot so
                // the UI stays fully usable (e.g. the composer FAB, which is
                // gated on session.currUser being non-null).
                val cached = context.account.user
                if (cached != null) {
                    accountSession.update(uiState, context) {
                        it.copy(session = it.session.copy(currUser = cached))
                    }
                    if (!accountSession.isCurrent(context)) return@launch
                    onUserFetched(cached)
                }
                // Also restore the persisted session snapshot (user stats,
                // settings, activities, ...) so the Profile page and other
                // offline surfaces stay populated on an offline cold start.
                restoreSessionSnapshot(context)
            }
        }
    }

    override suspend fun fetchInstanceProfile() {
        val context = accountSession.current ?: return
        val api = context.api
        try {
            val profile = api.getInstanceProfile()
            accountSession.update(uiState, context) { it.copy(session = it.session.copy(instanceProfile = profile)) }
            persistSessionSnapshot(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MemosViewModel", "Error fetching instance profile", e)
            restoreSessionSnapshot(context)
        }
    }

    override suspend fun fetchInstanceSettings() {
        val context = accountSession.current ?: return
        val api = context.api
        try {
            val settingNames = listOf("GENERAL", "STORAGE", "MEMO_RELATED")
            val results = settingNames.associateWith { name ->
                try {
                    api.getInstanceSetting("settings/$name")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("MemosViewModel", "Error fetching $name instance settings", e)
                    null
                }
            }

            // Merge all settings into a single InstanceSetting
            if (results.values.any { it != null }) {
                val merged = InstanceSetting(
                    generalSetting = results["GENERAL"]?.generalSetting,
                    storageSetting = results["STORAGE"]?.storageSetting,
                    memoRelatedSetting = results["MEMO_RELATED"]?.memoRelatedSetting
                )
                accountSession.update(uiState, context) { it.copy(session = it.session.copy(instanceSettings = merged)) }
                persistSessionSnapshot(context)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MemosViewModel", "Error fetching instance settings", e)
            restoreSessionSnapshot(context)
        }
    }

    override fun refreshInstanceSettings() {
        val context = accountSession.current ?: return
        val api = context.api
        accountSession.readScope.launch {
            fetchInstanceSettings()
        }
    }

    override suspend fun fetchUserStats(userResourceName: String) {
        val context = accountSession.current ?: return
        val api = context.api
        try {
            val stats = api.getUserStats(userResourceName)
            accountSession.update(uiState, context) { it.copy(session = it.session.copy(userStats = stats)) }
            persistSessionSnapshot(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MemosViewModel", "Error fetching user stats", e)
            restoreSessionSnapshot(context)
        }
    }

    override fun refreshUserStats() {
        val context = accountSession.current ?: return
        val api = context.api
        accountSession.readScope.launch {
            val user = uiState.value.session.currUser
            val userName = user?.name
            if (userName != null) {
                fetchUserStats(userName)
            }
        }
    }

    override suspend fun fetchActivities() {
        val context = accountSession.current ?: return
        val api = context.api
        try {
            val hostUrl = uiState.value.session.hostUrl
            Log.d(
                "MemosViewModel",
                "fetchActivities: Fetching from $hostUrl/api/v1/activities?pageSize=1000"
            )
            val response = api.listActivities(pageSize = 1000)
            val activities = response.activities ?: emptyList()
            accountSession.update(uiState, context) { it.copy(session = it.session.copy(activities = activities)) }
            persistSessionSnapshot(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MemosViewModel", "Error fetching activities", e)
            restoreSessionSnapshot(context)
        }
    }

    override suspend fun fetchUserSettings(userResourceName: String) {
        val context = accountSession.current ?: return
        val api = context.api
        try {
            val response = api.listUserSettings(userResourceName)
            val general =
                response.settings?.find { it.name?.endsWith("general") == true || it.generalSetting != null }?.generalSetting
            if (general != null) {
                accountSession.update(uiState, context) { it.copy(session = it.session.copy(userSettings = general)) }
                persistSessionSnapshot(context)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MemosViewModel", "Error fetching user settings", e)
            restoreSessionSnapshot(context)
        }
    }

    override fun updateUserGeneralSetting(locale: String?, memoVisibility: Visibility?) {
        val context = accountSession.current ?: return
        val api = context.api
        val state = uiState.value
        scope.launch {
            try {
                // Early return if api doesn't exist
                val currentApi = api
                val user = state.session.currUser ?: return@launch
                val userName = user.name ?: return@launch
                val currentSetting = state.session.userSettings ?: UserGeneralSetting()
                val newSetting = currentSetting.copy(
                    locale = locale ?: currentSetting.locale,
                    memoVisibility = memoVisibility ?: currentSetting.memoVisibility
                )
                val maskParts = mutableListOf<String>()
                if (locale != null) maskParts.add(currentApi.constants.userSettingLocaleMask)
                if (memoVisibility != null) maskParts.add(
                    currentApi.constants.userSettingMemoVisibilityMask
                )
                val updateMask = maskParts.joinToString(",")

                if (updateMask.isNotEmpty()) {
                    currentApi.updateUserSetting(
                        userName,
                        currentApi.constants.userSettingGeneralKey,
                        UserSetting(generalSetting = newSetting),
                        updateMask
                    )
                    if (accountSession.isCurrent(context)) fetchUserSettings(userName)
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Operation failed", e)
                accountSession.update(uiState, context) { it.copy(error = UiMessage(R.string.common_operation_failed)) }
            }
        }
    }

    override fun updateUserProfile(
        username: String?,
        email: String?,
        displayName: String?,
        avatarUrl: String?,
        description: String?,
        password: String?,
        onResult: (Boolean) -> Unit
    ) {
        val context = accountSession.current ?: return
        val api = context.api
        val state = uiState.value
        scope.launch {
            try {
                val currentUser = state.session.currUser ?: return@launch
                val currentApi = api
                val update = org.example.memosm.model.UserSnapshot(
                    username = username,
                    email = email,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    description = description,
                    password = password
                )
                val maskParts = mutableListOf<String>()
                val constants = currentApi.constants
                if (username != null) maskParts.add(constants.userMaskUsername)
                if (email != null) maskParts.add(constants.userMaskEmail)
                if (displayName != null) maskParts.add(constants.userMaskDisplayName)
                if (avatarUrl != null) maskParts.add(constants.userMaskAvatarUrl)
                if (description != null) maskParts.add(constants.userMaskDescription)
                if (password != null) maskParts.add(constants.userMaskPassword)

                val mask = maskParts.joinToString(",")

                if (mask.isNotEmpty()) {
                    currentApi.updateUser(currentUser.name!!, update, mask)
                    // We need to refresh current user
                    // Note: fetchCurrentUser is async/launch, so we can't await it easily unless we modify it
                    // But here we want onResult to be called after
                    val user = currentApi.getCurrentSession().user
                    if (user != null) {
                        accountSession.update(uiState, context) {
                            it.copy(session = it.session.copy(currUser = user))
                        }
                        // Store user in local account for offline access
                        val activeAccount = context.account
                        dataStoreManager.updateAccountUser(activeAccount.id, user)
                    }
                    if (accountSession.isCurrent(context)) onResult(true)
                } else {
                    if (accountSession.isCurrent(context)) onResult(true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Operation failed", e)
                accountSession.update(uiState, context) { it.copy(error = UiMessage(R.string.common_operation_failed)) }
                if (accountSession.isCurrent(context)) onResult(false)
            }
        }
    }

    private fun launchAccountChange(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Account change failed", e)
                uiState.update { it.copy(error = UiMessage(R.string.common_operation_failed)) }
            }
        }
    }

    override fun addAccount(hostUrl: String, token: String) {
        launchAccountChange {
            accountMutex.withLock {
                dataStoreManager.addAccount(hostUrl, token)
                refreshAccountsLocked()
            }
        }
    }

    override fun removeAccount(account: Account) {
        launchAccountChange {
            accountMutex.withLock {
                dataStoreManager.deleteAccount(account.id)
                refreshAccountsLocked()
                onAccountRemoved(account)
            }
        }
    }

    override fun updateAccountCredentials(account: Account, hostUrl: String, token: String) {
        launchAccountChange {
            accountMutex.withLock {
                dataStoreManager.updateAccount(account.id, hostUrl, token)
                refreshAccountsLocked()
            }
        }
    }

    override fun updateCurrentAccountInList() {
        launchAccountChange { accountMutex.withLock { refreshAccountsLocked() } }
    }

    private suspend fun refreshAccountsLocked() {
        val accounts = dataStoreManager.getAccounts()
        val active = accounts.find { it.isActive }
        val previous = accountSession.current?.account
        uiState.update { it.copy(accounts = accounts) }
        if (active?.id != previous?.id || active?.hostUrl != previous?.hostUrl ||
            active?.accessToken != previous?.accessToken || active == null) {
            pendingUserRequests.clear()
            retryUserAfter.clear()
            onAccountSwitched(active)
        }
    }

    override fun switchAccount(account: Account) {
        launchAccountChange {
            accountMutex.withLock {
                // Read the stored credentials, rather than a potentially stale UI row.
                if (dataStoreManager.getAccounts().none { it.id == account.id }) return@withLock
                dataStoreManager.setActiveAccount(account.id)
                dataStoreManager.updateAccountLastUsed(account.id, System.currentTimeMillis())
                refreshAccountsLocked()
            }
        }
    }
}
