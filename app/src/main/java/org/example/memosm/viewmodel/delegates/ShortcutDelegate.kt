package org.example.memosm.viewmodel.delegates

import org.example.memosm.R
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import org.example.memosm.viewmodel.AccountContext
import org.example.memosm.viewmodel.AccountSession
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
    private val sessionCacheStore: SessionCacheStore,
    private val accountSession: AccountSession,
    private val onRefreshUserMemos: () -> Unit
) : ShortcutDelegate {


    override suspend fun fetchShortcuts(userResourceName: String) {
        val context = accountSession.current ?: return
        val api = context.api
        try {
            val response = api.getShortcuts(userResourceName)
            val shortcuts = response.shortcuts ?: emptyList()
            accountSession.update(uiState, context) {
                it.copy(userMemoList = it.userMemoList.copy(shortcuts = shortcuts))
            }
            persistShortcuts(context, shortcuts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MemosViewModel", "Error fetching shortcuts", e)
            restoreShortcuts(context)
        }
    }

    /** Persist into the session snapshot so the chip row survives an offline cold start. */
    private suspend fun persistShortcuts(context: AccountContext, shortcuts: List<Shortcut>) {
        if (!accountSession.isCurrent(context)) return
        val accountId = context.account.id
        runCatching {
            val current = sessionCacheStore.get(accountId) ?: SessionSnapshotData()
            sessionCacheStore.save(accountId, current.copy(shortcuts = shortcuts))
        }
    }

    /** Offline/failure fallback: serve the last persisted shortcuts. */
    private suspend fun restoreShortcuts(context: AccountContext) {
        if (uiState.value.userMemoList.shortcuts.isNotEmpty()) return
        if (!accountSession.isCurrent(context)) return
        val accountId = context.account.id
        val cached = runCatching { sessionCacheStore.get(accountId) }
            .getOrNull()?.shortcuts.orEmpty()
        if (cached.isNotEmpty()) {
            accountSession.update(uiState, context) {
                it.copy(userMemoList = it.userMemoList.copy(shortcuts = cached))
            }
        }
    }

    override fun toggleShortcutFilter(shortcut: Shortcut) {
        val context = accountSession.current ?: return
        val currShortcut = uiState.value.userMemoList.selectedShortcut
        val newSelection = if (currShortcut == shortcut) null else shortcut

        accountSession.update(uiState, context) {
            it.copy(
                userMemoList = it.userMemoList.copy(
                    selectedShortcut = newSelection, selectedHashtag = null
                )
            )
        }

        onRefreshUserMemos()
    }

    override fun toggleHashtagFilter(tag: String) {
        val context = accountSession.current ?: return
        val currTag = uiState.value.userMemoList.selectedHashtag
        val newSelection = if (currTag == tag) null else tag

        accountSession.update(uiState, context) {
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
        val context = accountSession.current ?: return
        val api = context.api
        val user = uiState.value.session.currUser ?: return
        scope.launch {
            try {
                val shortcut = Shortcut(title = title, filter = filter)
                api.createShortcut(user.name!!, shortcut)
                if (accountSession.isCurrent(context)) fetchShortcuts(user.name!!)
                if (accountSession.isCurrent(context)) onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (accountSession.isCurrent(context)) onError(getErrorResponse(e))
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
        val context = accountSession.current ?: return
        val api = context.api
        val user = uiState.value.session.currUser ?: return
        scope.launch {
            try {
                val userName = user.name ?: return@launch
                val currentApi = api
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
                if (accountSession.isCurrent(context)) fetchShortcuts(userName)
                if (accountSession.isCurrent(context)) onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (accountSession.isCurrent(context)) onError(getErrorResponse(e))
            }
        }
    }

    override fun deleteShortcut(shortcut: Shortcut) {
        val context = accountSession.current ?: return
        val api = context.api
        val user = uiState.value.session.currUser ?: return
        scope.launch {
            try {
                val shortcutId = shortcut.name?.substringAfterLast("/") ?: ""
                api.deleteShortcut(user.name!!, shortcutId)
                if (accountSession.isCurrent(context)) fetchShortcuts(user.name!!)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    private fun getErrorResponse(e: Exception): Int {
        Log.e("MemosViewModel", "Error saving resource", e)
        return R.string.profile_shortcuts_error_save
    }
}
