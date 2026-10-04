package org.example.memosm.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import org.example.memosm.api.MemosApi
import org.example.memosm.model.Account
import kotlin.coroutines.CoroutineContext

/** API and readiness are published atomically; operations capture this before suspending. */
data class AccountConnection(val api: MemosApi, val networkReady: Boolean)

/** One activation, including a new generation when returning to the same account. */
class AccountContext(
    val account: Account,
    api: MemosApi,
    val httpClient: OkHttpClient,
    val generation: Long,
    networkReady: Boolean = true
) {
    @Volatile
    var connection: AccountConnection = AccountConnection(api, networkReady)
        private set
    val api: MemosApi get() = connection.api
    val networkReady: Boolean get() = connection.networkReady

    internal fun completeConnection(api: MemosApi) {
        connection = AccountConnection(api, true)
    }
}

/** Owns cancellable reads. Writes capture [current] and keep their originating API. */
class AccountSession(private val parentScope: CoroutineScope) {
    private val _contexts = MutableStateFlow<AccountContext?>(null)
    val contexts: StateFlow<AccountContext?> = _contexts.asStateFlow()
    val current: AccountContext? get() = _contexts.value
    private var generation = 0L
    @Volatile
    private var readJob: Job = SupervisorJob(parentScope.coroutineContext[Job])

    val readScope: CoroutineScope = object : CoroutineScope {
        override val coroutineContext: CoroutineContext
            get() = parentScope.coroutineContext + readJob
    }

    fun activate(account: Account, api: MemosApi, httpClient: OkHttpClient, networkReady: Boolean = true): AccountContext {
        _contexts.value = null
        readJob.cancel()
        readJob = SupervisorJob(parentScope.coroutineContext[Job])
        return AccountContext(account, api, httpClient, ++generation, networkReady).also { _contexts.value = it }
    }

    /** Version discovery completes this activation; local reads/writes keep their identity. */
    fun completeConnection(context: AccountContext, api: MemosApi): Boolean {
        if (!isCurrent(context)) return false
        context.completeConnection(api)
        return true
    }

    fun clear() {
        _contexts.value = null
        readJob.cancel()
    }

    fun isCurrent(context: AccountContext): Boolean = current === context

    fun update(
        state: MutableStateFlow<MemosUiState>,
        context: AccountContext,
        transform: (MemosUiState) -> MemosUiState
    ) {
        state.update { if (isCurrent(context)) transform(it) else it }
    }
}

/** Reset the complete account-owned UI together; keep only app-wide preferences. */
fun MemosUiState.forAccount(account: Account?): MemosUiState = MemosUiState(
    accounts = accounts,
    appSettings = appSettings,
    session = account?.let {
        SessionState(hostUrl = it.hostUrl, token = it.accessToken, currUser = it.user)
    } ?: SessionState(),
    accountGeneration = accountGeneration + 1,
    attachmentList = AttachmentListState(cellWidth = attachmentList.cellWidth)
)
