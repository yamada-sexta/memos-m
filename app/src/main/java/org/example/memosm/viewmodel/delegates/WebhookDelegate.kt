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
    private val accountSession: AccountSession
) : WebhookDelegate {


    override suspend fun fetchWebhooks(userResourceName: String) {
        val context = accountSession.current ?: return
        val api = context.api
        try {
            val response = api.listUserWebhooks(userResourceName)
            val hooks = response?.webhooks ?: emptyList()
            accountSession.update(uiState, context) { it.copy(session = it.session.copy(webhooks = hooks)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MemosViewModel", "Error fetching webhooks", e)
        }
    }

    override fun createWebhook(
        displayName: String, url: String, onSuccess: () -> Unit, onError: (Int) -> Unit
    ) {
        val context = accountSession.current ?: return
        val api = context.api
        val user = uiState.value.session.currUser ?: return
        scope.launch {
            try {
                val webhook = UserWebhook(displayName = displayName, url = url)
                api.createUserWebhook(user.name!!, webhook)
                if (accountSession.isCurrent(context)) fetchWebhooks(user.name!!)
                if (accountSession.isCurrent(context)) onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (accountSession.isCurrent(context)) onError(getErrorResponse(e))
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
        val context = accountSession.current ?: return
        val api = context.api
        val user = uiState.value.session.currUser ?: return
        scope.launch {
            try {
                val userName = user.name ?: return@launch
                val currentApi = api
                val update = webhook.copy(displayName = displayName, url = url)
                val webhookId = webhook.name?.substringAfterLast("/") ?: ""

                val constants = currentApi.constants
                currentApi.updateUserWebhook(
                    userName,
                    webhookId,
                    update,
                    "${constants.webhookMaskDisplayName},${constants.webhookMaskUrl}"
                )
                if (accountSession.isCurrent(context)) fetchWebhooks(userName)
                if (accountSession.isCurrent(context)) onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (accountSession.isCurrent(context)) onError(getErrorResponse(e))
            }
        }
    }

    override fun deleteWebhook(webhook: UserWebhook) {
        val context = accountSession.current ?: return
        val api = context.api
        val user = uiState.value.session.currUser ?: return
        scope.launch {
            try {
                val webhookId = webhook.name?.substringAfterLast("/") ?: ""
                api.deleteUserWebhook(user.name!!, webhookId)
                if (accountSession.isCurrent(context)) fetchWebhooks(user.name!!)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    private fun getErrorResponse(e: Exception): Int {
        Log.e("MemosViewModel", "Error saving webhook", e)
        return R.string.profile_webhooks_error_save
    }
}
