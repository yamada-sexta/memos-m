package org.example.memosm.viewmodel.delegates

import org.example.memosm.R
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.memosm.api.MemosApi
import org.example.memosm.data.offline.SessionCacheStore
import org.example.memosm.data.offline.SessionSnapshotData
import org.example.memosm.model.Shortcut
import org.example.memosm.viewmodel.MemosUiState

interface ShortcutDelegate {
    suspend fun fetchShortcuts(userResourceName: String)
    fun toggleShortcutFilter(shortcut: Shortcut)
    fun toggleHashtagFilter(tag: String)
    fun createShortcut(
        title: String, filter: String, onSuccess: () -> Unit, onError: (Int) -> Unit
    )

    fun updateShortcut(
        shortcut: Shortcut,
        title: String,
        filter: String,
        onSuccess: () -> Unit,
        onError: (Int) -> Unit
    )

    fun deleteShortcut(shortcut: Shortcut)
}

class ShortcutDelegateImpl(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MemosUiState>,
    private val apiProvider: () -> MemosApi?,
    private val sessionCacheStore: SessionCacheStore,
    private val onRefreshUserMemos: () -> Unit
) : ShortcutDelegate {

    private val api: MemosApi? get() = apiProvider()

    override suspend fun fetchShortcuts(userResourceName: String) {
        try {
            val response = api?.getShortcuts(userResourceName)
            val shortcuts = response?.shortcuts ?: emptyList()
            uiState.update {
                it.copy(userMemoList = it.userMemoList.copy(shortcuts = shortcuts))
            }
            persistShortcuts(shortcuts)
        } catch (e: Exception) {
            Log.e("MemosViewModel", "Error fetching shortcuts", e)
            restoreShortcuts()
        }
    }

    /** Persist into the session snapshot so the chip row survives an offline cold start. */
    private suspend fun persistShortcuts(shortcuts: List<Shortcut>) {
        val accountId = uiState.value.accounts.find { it.isActive }?.id ?: return
        runCatching {
            val current = sessionCacheStore.get(accountId) ?: SessionSnapshotData()
            sessionCacheStore.save(accountId, current.copy(shortcuts = shortcuts))
        }
    }

    /** Offline/failure fallback: serve the last persisted shortcuts. */
    private suspend fun restoreShortcuts() {
        if (uiState.value.userMemoList.shortcuts.isNotEmpty()) return
        val accountId = uiState.value.accounts.find { it.isActive }?.id ?: return
        val cached = runCatching { sessionCacheStore.get(accountId) }
            .getOrNull()?.shortcuts.orEmpty()
        if (cached.isNotEmpty()) {
            uiState.update {
                it.copy(userMemoList = it.userMemoList.copy(shortcuts = cached))
            }
        }
    }

    override fun toggleShortcutFilter(shortcut: Shortcut) {
        val currShortcut = uiState.value.userMemoList.selectedShortcut
        val newSelection = if (currShortcut == shortcut) null else shortcut

        uiState.update {
            it.copy(
                userMemoList = it.userMemoList.copy(
                    selectedShortcut = newSelection, selectedHashtag = null
                )
            )
        }

        onRefreshUserMemos()
    }

    override fun toggleHashtagFilter(tag: String) {
        val currTag = uiState.value.userMemoList.selectedHashtag
        val newSelection = if (currTag == tag) null else tag

        uiState.update {
            it.copy(
                userMemoList = it.userMemoList.copy(
                    selectedHashtag = newSelection, selectedShortcut = null
                )
            )
        }

        onRefreshUserMemos()
    }

    override fun createShortcut(
        title: String, filter: String, onSuccess: () -> Unit, onError: (Int) -> Unit
    ) {
        scope.launch {
            try {
                val user = uiState.value.session.currUser ?: return@launch
                val shortcut = Shortcut(title = title, filter = filter)
                api?.createShortcut(user.name!!, shortcut)
                fetchShortcuts(user.name!!)
                onSuccess()
            } catch (e: Exception) {
                onError(getErrorResponse(e))
            }
        }
    }

    override fun updateShortcut(
        shortcut: Shortcut,
        title: String,
        filter: String,
        onSuccess: () -> Unit,
        onError: (Int) -> Unit
    ) {
        scope.launch {
            try {
                val user = uiState.value.session.currUser ?: return@launch
                val userName = user.name ?: return@launch
                val currentApi = api ?: return@launch
                val update = shortcut.copy(title = title, filter = filter)
                // shortcut.name is in format "users/{uid}/shortcuts/{id}"
                val shortcutId = shortcut.name?.substringAfterLast("/") ?: ""

                val constants = currentApi.constants
                currentApi.updateShortcut(
                    userName,
                    shortcutId,
                    update,
                    "${constants.shortcutMaskTitle},${constants.shortcutMaskFilter}"
                )
                fetchShortcuts(userName)
                onSuccess()
            } catch (e: Exception) {
                onError(getErrorResponse(e))
            }
        }
    }

    override fun deleteShortcut(shortcut: Shortcut) {
        scope.launch {
            try {
                val user = uiState.value.session.currUser ?: return@launch
                val shortcutId = shortcut.name?.substringAfterLast("/") ?: ""
                api?.deleteShortcut(user.name!!, shortcutId)
                fetchShortcuts(user.name!!)
            } catch (e: Exception) {
            }
        }
    }

    private fun getErrorResponse(e: Exception): Int {
        Log.e("MemosViewModel", "Error saving resource", e)
        return R.string.profile_shortcuts_error_save
    }
}
