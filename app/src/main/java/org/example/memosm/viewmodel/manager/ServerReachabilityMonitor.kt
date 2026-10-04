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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.example.memosm.api.MemosApi
import org.example.memosm.viewmodel.ConnectionState

/** Account-owned reachability, independently of Android's Internet validation. */
data class ReachabilityState(
    val isOnline: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.CHECKING,
    val error: String? = null
)

/** Call from the owning scope's dispatcher, including the periodic scheduler. */
class ServerReachabilityMonitor(
    private val scope: CoroutineScope,
    private val apiProvider: () -> MemosApi?,
    private val accountIdProvider: () -> String?,
    private val isBlockedProvider: () -> Boolean = { false }
) {
    private val _state = MutableStateFlow(ReachabilityState())
    val state: StateFlow<ReachabilityState> = _state.asStateFlow()
    private var probeJob: Job? = null
    private var schedulerJob: Job? = null
    private var generation = 0L
    private var probeAccount: String? = null
    private var probeApi: MemosApi? = null
    private val callbacks = linkedMapOf<() -> Unit, Boolean>()

    /** Coalesce concurrent checks without cancelling a caller's recovery hook. */
    fun checkNow(onReachable: (() -> Unit)? = null): Job? = probe(onReachable, onlyOnRecovery = false)

    private fun probe(onReachable: (() -> Unit)?, onlyOnRecovery: Boolean): Job? {
        val expectedAccount = accountIdProvider() ?: return null
        val expectedApi = apiProvider() ?: return null
        if (isBlockedProvider()) {
            cancelProbe()
            _state.value = ReachabilityState(connectionState = ConnectionState.OFFLINE)
            return null
        }
        if (probeJob?.isActive == true && probeAccount == expectedAccount && probeApi === expectedApi) {
            if (onReachable != null) callbacks[onReachable] = callbacks[onReachable]?.let { it && onlyOnRecovery } ?: onlyOnRecovery
            return probeJob
        }
        cancelProbe(resetState = false)
        probeAccount = expectedAccount
        probeApi = expectedApi
        val expectedGeneration = generation
        val wasOnline = _state.value.isOnline
        if (onReachable != null) callbacks[onReachable] = onlyOnRecovery
        val job = scope.launch(start = CoroutineStart.LAZY) {
            fun isCurrent() = generation == expectedGeneration && accountIdProvider() == expectedAccount &&
                apiProvider() === expectedApi
            if (!wasOnline) _state.value = ReachabilityState()
            val result = try {
                withTimeout(30_000) { expectedApi.getInstanceProfile() }
                ReachabilityState(isOnline = true, connectionState = ConnectionState.ONLINE)
            } catch (e: TimeoutCancellationException) {
                ReachabilityState(connectionState = ConnectionState.SERVER_UNREACHABLE, error = "Connection timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReachabilityState(connectionState = if (e is retrofit2.HttpException && e.code() in listOf(401, 403))
                    ConnectionState.AUTH_REQUIRED else ConnectionState.SERVER_UNREACHABLE, error = e.message)
            }
            if (!isCurrent()) return@launch
            _state.value = result
            val completedCallbacks = callbacks.toMap()
            callbacks.clear()
            if (result.isOnline) completedCallbacks.forEach { (callback, recoveryOnly) ->
                if (isCurrent() && (!recoveryOnly || !wasOnline)) callback()
            }
        }
        probeJob = job
        job.start()
        return job
    }

    /** Every failed state remains retryable, even if no connectivity event arrives. */
    fun start(onRecovered: () -> Unit) {
        schedulerJob?.cancel()
        schedulerJob = scope.launch {
            while (true) {
                delay(60_000)
                probe(onRecovered, onlyOnRecovery = true)?.join()
            }
        }
    }

    fun cancelProbe(resetState: Boolean = true) {
        generation++
        probeJob?.cancel()
        probeJob = null
        callbacks.clear()
        if (resetState) _state.value = ReachabilityState()
    }

    fun stop() {
        cancelProbe()
        schedulerJob?.cancel()
    }
}
