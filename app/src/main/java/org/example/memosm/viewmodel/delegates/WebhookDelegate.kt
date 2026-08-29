package org.example.memosm.viewmodel.delegates

import org.example.memosm.R
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.memosm.api.MemosApi
import org.example.memosm.model.UserWebhook
import org.example.memosm.viewmodel.MemosUiState

interface WebhookDelegate {
    suspend fun fetchWebhooks(userResourceName: String)
    fun createWebhook(
        displayName: String, url: String, onSuccess: () -> Unit, onError: (Int) -> Unit
    )

    fun updateWebhook(
        webhook: UserWebhook,
        displayName: String,
        url: String,
        onSuccess: () -> Unit,
        onError: (Int) -> Unit
    )

    fun deleteWebhook(webhook: UserWebhook)
}

class WebhookDelegateImpl(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MemosUiState>,
    private val apiProvider: () -> MemosApi?
) : WebhookDelegate {

    private val api: MemosApi? get() = apiProvider()

    override suspend fun fetchWebhooks(userResourceName: String) {
        try {
            val response = api?.listUserWebhooks(userResourceName)
            val hooks = response?.webhooks ?: emptyList()
            uiState.update { it.copy(session = it.session.copy(webhooks = hooks)) }
        } catch (e: Exception) {
            Log.e("MemosViewModel", "Error fetching webhooks", e)
        }
    }

    override fun createWebhook(
        displayName: String, url: String, onSuccess: () -> Unit, onError: (Int) -> Unit
    ) {
        scope.launch {
            try {
                val user = uiState.value.session.currUser ?: return@launch
                val webhook = UserWebhook(displayName = displayName, url = url)
                api?.createUserWebhook(user.name!!, webhook)
                fetchWebhooks(user.name!!)
                onSuccess()
            } catch (e: Exception) {
                onError(getErrorResponse(e))
            }
        }
    }

    override fun updateWebhook(
        webhook: UserWebhook,
        displayName: String,
        url: String,
        onSuccess: () -> Unit,
        onError: (Int) -> Unit
    ) {
        scope.launch {
            try {
                val user = uiState.value.session.currUser ?: return@launch
                val userName = user.name ?: return@launch
                val currentApi = api ?: return@launch
                val update = webhook.copy(displayName = displayName, url = url)
                val webhookId = webhook.name?.substringAfterLast("/") ?: ""

                val constants = currentApi.constants
                currentApi.updateUserWebhook(
                    userName,
                    webhookId,
                    update,
                    "${constants.webhookMaskDisplayName},${constants.webhookMaskUrl}"
                )
                fetchWebhooks(userName)
                onSuccess()
            } catch (e: Exception) {
                onError(getErrorResponse(e))
            }
        }
    }

    override fun deleteWebhook(webhook: UserWebhook) {
        scope.launch {
            try {
                val user = uiState.value.session.currUser ?: return@launch
                val webhookId = webhook.name?.substringAfterLast("/") ?: ""
                api?.deleteUserWebhook(user.name!!, webhookId)
                fetchWebhooks(user.name!!)
            } catch (e: Exception) {
            }
        }
    }

    private fun getErrorResponse(e: Exception): Int {
        Log.e("MemosViewModel", "Error saving webhook", e)
        return R.string.profile_webhooks_error_save
    }
}
