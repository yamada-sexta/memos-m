package org.example.memosm.viewmodel.manager

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.memosm.api.MemosApi
import org.example.memosm.viewmodel.ConnectionState

/**
 * Snapshot of the server-reachability state machine. The ViewModel bridges
 * this into its UI state (isOnline / connectionState / syncError).
 */
data class ReachabilityState(
    val isOnline: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.CHECKING,
    val error: String? = null
)

/**
 * Server-reachability state machine: one-shot probes plus a periodic probe
 * that catches the case where the OS keeps reporting validated connectivity
 * while the server process dies (or comes back) without any connectivity
 * change. Probes are keyed to the account/API they started with, so a stale
 * probe can never clobber state after an account switch.
 */
class ServerReachabilityMonitor(
    private val scope: CoroutineScope,
    private val apiProvider: () -> MemosApi?,
    private val accountIdProvider: () -> String?
) {

    private val _state = MutableStateFlow(ReachabilityState())
    val state: StateFlow<ReachabilityState> = _state.asStateFlow()

    private var probeJob: Job? = null

    // Separate from probeJob: checkNow() cancels probeJob before every one-shot
    // probe, so the periodic scheduler must live in its own job to avoid being
    // cancelled by its own probe.
    private var schedulerJob: Job? = null

    // Interval for the periodic server-reachability probe. Kept modest so a
    // server that dies while the OS still reports validated connectivity is
    // detected within about a minute, and so recovery after SERVER_UNREACHABLE
    // is not gated on the next connectivity change.
    private val probeIntervalMs = 60_000L

    /**
     * One-shot reachability probe. [onReachable] runs once when the probe
     * succeeds - callers use it to trigger fetches / the recovery sequence.
     */
    fun checkNow(onReachable: (() -> Unit)? = null) {
        probeJob?.cancel()
        val expectedAccount = accountIdProvider() ?: return
        val expectedApi = apiProvider() ?: return
        probeJob = scope.launch {
            _state.update {
                it.copy(isOnline = false, connectionState = ConnectionState.CHECKING)
            }
            delay(250)
            try {
                expectedApi.getInstanceProfile()
                if (accountIdProvider() != expectedAccount || apiProvider() !== expectedApi) return@launch
                _state.update {
                    it.copy(
                        isOnline = true,
                        connectionState = ConnectionState.ONLINE,
                        error = null
                    )
                }
                onReachable?.invoke()
            } catch (e: CancellationException) {
                // A cancelled probe (superseded by a newer one) must not write
                // a failure state over the newer probe's result.
                throw e
            } catch (e: Exception) {
                if (accountIdProvider() != expectedAccount || apiProvider() !== expectedApi) return@launch
                val state = if (e is retrofit2.HttpException && e.code() in listOf(401, 403)) {
                    ConnectionState.AUTH_REQUIRED
                } else {
                    ConnectionState.SERVER_UNREACHABLE
                }
                _state.update {
                    it.copy(
                        isOnline = false,
                        connectionState = state,
                        error = e.message
                    )
                }
            }
        }
    }

    /**
     * Start the periodic probe. [onRecovered] is the recovery hook run when
     * the server comes back after SERVER_UNREACHABLE (sync flush, list
     * refresh, pre-download).
     */
    fun start(onRecovered: () -> Unit) {
        schedulerJob?.cancel()
        schedulerJob = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(probeIntervalMs)
                // Skip only when the connectivity observer has ALREADY driven us
                // to a terminal offline state (OFFLINE / SERVER_UNREACHABLE): the
                // observer's connectivity-change callback owns probing there. We
                // must NOT gate on connectivityObserver.isOnline: on emulators over
                // adb reverse (or unvalidated captive portals) the OS reports no
                // validated network while the server stays reachable, so gating on
                // isOnline would silently disable the probe exactly when it is the
                // only thing that can detect a dead server.
                val expectedAccount = accountIdProvider() ?: continue
                val expectedApi = apiProvider() ?: continue
                val reachable = try {
                    expectedApi.getInstanceProfile()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                // A stale account/API must not clobber state after a switch.
                if (accountIdProvider() != expectedAccount || apiProvider() !== expectedApi) continue
                if (reachable) {
                    // Server is back: flip online and flush queued writes.
                    // Reuse checkNow so the state transition (and its CHECKING
                    // flash) matches the one-shot probe path.
                    if (_state.value.connectionState == ConnectionState.SERVER_UNREACHABLE) {
                        checkNow(onRecovered)
                    }
                } else if (_state.value.connectionState == ConnectionState.ONLINE) {
                    // Server died while the OS still reports connectivity:
                    // transition exactly like a one-shot probe failure.
                    checkNow()
                }
            }
        }
    }

    /** Cancel any in-flight one-shot probe (e.g. on connectivity loss). */
    fun cancelProbe() {
        probeJob?.cancel()
    }

    /** Stop both the one-shot probe and the periodic scheduler. */
    fun stop() {
        probeJob?.cancel()
        schedulerJob?.cancel()
    }
}
