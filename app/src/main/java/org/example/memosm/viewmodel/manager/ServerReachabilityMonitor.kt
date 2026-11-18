package org.example.memosm.viewmodel.manager

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.example.memosm.api.MemosApi
import org.example.memosm.api.ServerRateLimit
import org.example.memosm.viewmodel.ConnectionState
import retrofit2.HttpException

/** Server reachability is independent of Android's Internet validation, including local networks. */
data class ReachabilityState(
    val isOnline: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.CHECKING,
    val error: String? = null,
)

/** One account-owned probe; consumers react to the state flow rather than recovery callbacks. */
class ServerReachabilityMonitor(
    private val scope: CoroutineScope,
    private val apiProvider: () -> MemosApi?,
    private val accountIdProvider: () -> String?,
    private val isBlockedProvider: () -> Boolean = { false },
) {
    private val _state = MutableStateFlow(ReachabilityState())
    val state: StateFlow<ReachabilityState> = _state.asStateFlow()
    private var probeJob: Job? = null
    private var schedulerJob: Job? = null
    private var retryJob: Job? = null
    private var generation = 0L
    private var probeAccount: String? = null
    private var probeApi: MemosApi? = null

    fun checkNow(): Job? {
        val account = accountIdProvider() ?: return null
        val api = apiProvider() ?: return null
        if (isBlockedProvider()) {
            cancelProbe()
            _state.value = ReachabilityState(connectionState = ConnectionState.OFFLINE)
            return null
        }
        if (probeJob?.isActive == true && probeAccount == account && probeApi === api) {
            return probeJob
        }
        cancelProbe(resetState = false)
        probeAccount = account
        probeApi = api
        val expectedGeneration = generation

        fun isCurrent() = expectedGeneration == generation && accountIdProvider() == account && apiProvider() === api
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                if (!_state.value.isOnline) _state.value = ReachabilityState()
                var retryAfter: Long? = null
                val result =
                    try {
                        withTimeout(30_000) { api.getInstanceProfile() }
                        ReachabilityState(isOnline = true, connectionState = ConnectionState.ONLINE)
                    } catch (_: TimeoutCancellationException) {
                        ReachabilityState(connectionState = ConnectionState.SERVER_UNREACHABLE, error = "Connection timed out")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        retryAfter = ServerRateLimit.retryDelay(error)
                        when {
                            retryAfter != null -> {
                                ReachabilityState(
                                    isOnline = true,
                                    connectionState = ConnectionState.RATE_LIMITED,
                                    error = "Server rate limit; retrying automatically",
                                )
                            }

                            error is HttpException && error.code() in setOf(401, 403) -> {
                                ReachabilityState(
                                    connectionState = ConnectionState.AUTH_REQUIRED,
                                    error = error.message,
                                )
                            }

                            else -> {
                                ReachabilityState(connectionState = ConnectionState.SERVER_UNREACHABLE, error = error.message)
                            }
                        }
                    }
                if (!isCurrent()) return@launch
                _state.value = result
                retryAfter?.let { wait ->
                    retryJob =
                        scope.launch {
                            delay(wait)
                            retryJob = null
                            if (isCurrent()) checkNow()
                        }
                }
            }
        probeJob = job
        job.start()
        return job
    }

    /** Failed probes retry even when Android sends no additional connectivity event. */
    fun start() {
        schedulerJob?.cancel()
        schedulerJob =
            scope.launch {
                while (isActive) {
                    delay(60_000)
                    checkNow()?.join()
                }
            }
    }

    fun cancelProbe(resetState: Boolean = true) {
        generation++
        retryJob?.cancel()
        retryJob = null
        probeJob?.cancel()
        probeJob = null
        if (resetState) _state.value = ReachabilityState()
    }

    fun stop() {
        cancelProbe()
        schedulerJob?.cancel()
    }
}
