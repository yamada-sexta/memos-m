package org.example.memosm.viewmodel.delegates

import org.example.memosm.R
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.memosm.api.MemosApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.model.Account
import org.example.memosm.model.InstanceSetting
import org.example.memosm.model.User
import org.example.memosm.model.UserGeneralSetting
import org.example.memosm.model.UserSetting
import org.example.memosm.model.Visibility
import org.example.memosm.viewmodel.MemosUiState
import org.example.memosm.viewmodel.UiMessage

interface UserDelegate {
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
    private val apiProvider: () -> MemosApi?,
    private val dataStoreManager: DataStoreManager,
    private val onAccountSwitched: suspend (Account) -> Unit
) : UserDelegate {

    private val pendingUserRequests = mutableSetOf<String>()

    private val api: MemosApi? get() = apiProvider()

    override suspend fun fetchUsers(names: List<String>) {
        val currentUsers = uiState.value.users
        val toFetch = names.filter { it !in currentUsers && it !in pendingUserRequests }
        Log.d("MemosUsers", "fetchUsers: requested=$names cached=${currentUsers.keys} toFetch=$toFetch")
        if (toFetch.isEmpty()) return

        pendingUserRequests.addAll(toFetch)
        scope.launch {
            try {
                val fetchedUsers = api?.getUsers(toFetch).orEmpty()
                Log.d("MemosUsers", "fetchUsers: resolved=${fetchedUsers.keys}")
                if (fetchedUsers.isNotEmpty()) {
                    uiState.update { it.copy(users = it.users + fetchedUsers) }
                }
                val unresolved = toFetch.filter { it !in fetchedUsers }
                if (unresolved.isNotEmpty()) {
                    Log.w("MemosUsers", "fetchUsers: unresolved=$unresolved")
                }
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Error fetching users $toFetch", e)
            } finally {
                pendingUserRequests.removeAll(toFetch.toSet())
            }
        }
    }

    override fun fetchCurrentUser(
        onUserFetched: suspend (User) -> Unit
    ) {
        scope.launch {
            try {
                val user = api?.getCurrentSession()?.user
                Log.d("MemosViewModel", "fetchCurrentUser: user=$user")
                if (user != null) {
                    uiState.update {
                        Log.d("MemosViewModel", "Updating session with user: ${user.name}")
                        it.copy(session = it.session.copy(currUser = user))
                    }

                    // Store user in local account for offline access
                    val activeAccount = uiState.value.accounts.find { it.isActive }
                    if (activeAccount != null) {
                        dataStoreManager.updateAccountUser(activeAccount.id, user)
                    }

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
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Error fetching current user", e)
            }
        }
    }

    override suspend fun fetchInstanceProfile() {
        try {
            val profile = api?.getInstanceProfile()
            if (profile != null) {
                uiState.update { it.copy(session = it.session.copy(instanceProfile = profile)) }
            }
        } catch (e: Exception) {
            Log.e("MemosViewModel", "Error fetching instance profile", e)
        }
    }

    override suspend fun fetchInstanceSettings() {
        try {
            val settingNames = listOf("GENERAL", "STORAGE", "MEMO_RELATED")
            val results = settingNames.associateWith { name ->
                try {
                    api?.getInstanceSetting("settings/$name")
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
                uiState.update { it.copy(session = it.session.copy(instanceSettings = merged)) }
            }
        } catch (e: Exception) {
            Log.e("MemosViewModel", "Error fetching instance settings", e)
        }
    }

    override fun refreshInstanceSettings() {
        scope.launch {
            fetchInstanceSettings()
        }
    }

    override suspend fun fetchUserStats(userResourceName: String) {
        try {
            val stats = api?.getUserStats(userResourceName)
            if (stats != null) {
                uiState.update { it.copy(session = it.session.copy(userStats = stats)) }
            }
        } catch (e: Exception) {
            Log.e("MemosViewModel", "Error fetching user stats", e)
        }
    }

    override fun refreshUserStats() {
        scope.launch {
            val user = uiState.value.session.currUser
            val userName = user?.name
            if (userName != null) {
                fetchUserStats(userName)
            }
        }
    }

    override suspend fun fetchActivities() {
        try {
            val hostUrl = uiState.value.session.hostUrl
            Log.d(
                "MemosViewModel",
                "fetchActivities: Fetching from $hostUrl/api/v1/activities?pageSize=1000"
            )
            val response = api?.listActivities(pageSize = 1000)
            val activities = response?.activities ?: emptyList()
            uiState.update { it.copy(session = it.session.copy(activities = activities)) }
        } catch (e: Exception) {
            Log.e("MemosViewModel", "Error fetching activities", e)
        }
    }

    override suspend fun fetchUserSettings(userResourceName: String) {
        try {
            val response = api?.listUserSettings(userResourceName)
            val general =
                response?.settings?.find { it.name?.endsWith("general") == true || it.generalSetting != null }?.generalSetting
            if (general != null) {
                uiState.update { it.copy(session = it.session.copy(userSettings = general)) }
            }
        } catch (e: Exception) {
            Log.e("MemosViewModel", "Error fetching user settings", e)
        }
    }

    override fun updateUserGeneralSetting(locale: String?, memoVisibility: Visibility?) {
        scope.launch {
            try {
                // Early return if api doesn't exist
                val currentApi = api ?: return@launch
                val user = uiState.value.session.currUser ?: return@launch
                val userName = user.name ?: return@launch
                val currentSetting = uiState.value.session.userSettings ?: UserGeneralSetting()
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
                    fetchUserSettings(userName)
                }

            } catch (e: Exception) {
                Log.e("MemosViewModel", "Operation failed", e)
                uiState.update { it.copy(error = UiMessage(R.string.common_operation_failed)) }
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
        scope.launch {
            try {
                val currentUser = uiState.value.session.currUser ?: return@launch
                val currentApi = api ?: return@launch
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
                        uiState.update {
                            it.copy(session = it.session.copy(currUser = user))
                        }
                        // Store user in local account for offline access
                        val activeAccount = uiState.value.accounts.find { it.isActive }
                        if (activeAccount != null) {
                            dataStoreManager.updateAccountUser(activeAccount.id, user)
                        }
                    }
                    onResult(true)
                } else {
                    onResult(true)
                }
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Operation failed", e)
                uiState.update { it.copy(error = UiMessage(R.string.common_operation_failed)) }
                onResult(false)
            }
        }
    }

    override fun addAccount(hostUrl: String, token: String) {
        scope.launch {
            try {
                Log.d("MemosViewModel", "Adding account for $hostUrl")
                dataStoreManager.addAccount(hostUrl, token)
                // Trigger account update
                // The Original called updateCurrentAccountInList
                // check how we handle this callback
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Operation failed", e)
                uiState.update { it.copy(error = UiMessage(R.string.common_operation_failed)) }
            }
        }
    }

    override fun removeAccount(account: Account) {
        scope.launch {
            try {
                dataStoreManager.deleteAccount(account.id)
                // trigger update
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Error deleting account", e)
            }
        }
    }

    override fun updateAccountCredentials(
        account: Account, hostUrl: String, token: String
    ) {
        scope.launch {
            try {
                dataStoreManager.updateAccount(account.id, hostUrl, token)
                // trigger update
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Error updating credentials", e)
            }
        }
    }

    override fun updateCurrentAccountInList() {
        scope.launch {
            val accounts = dataStoreManager.getAccounts()
            val activeAccount = accounts.find { it.isActive }

            uiState.update { it.copy(accounts = accounts) }

            if (activeAccount != null) {
                switchAccount(activeAccount)
            } else {
                uiState.update { it.copy(error = UiMessage(R.string.common_no_active_account)) }
            }
        }
    }

    override fun switchAccount(account: Account) {
        scope.launch {
            try {
                dataStoreManager.setActiveAccount(account.id)
                dataStoreManager.updateAccountLastUsed(account.id, System.currentTimeMillis())

                uiState.update {
                    it.copy(
                        session = org.example.memosm.viewmodel.SessionState(
                            token = account.accessToken,
                            hostUrl = account.hostUrl,
                            currUser = account.user
                        ), accounts = it.accounts.map { acc ->
                            acc.copy(isActive = acc.id == account.id)
                        }, users = emptyMap()
                    )
                }
                pendingUserRequests.clear()

                // The calling ViewModel needs to recreate Api and Managers
                onAccountSwitched(account)

            } catch (e: Exception) {
                Log.e("MemosViewModel", "Error switching account", e)
                Log.e("MemosViewModel", "Operation failed", e)
                uiState.update { it.copy(error = UiMessage(R.string.common_operation_failed)) }
            }
        }
    }
}
