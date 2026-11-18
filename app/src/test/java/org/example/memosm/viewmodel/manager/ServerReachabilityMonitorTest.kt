package org.example.memosm.viewmodel.manager

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.ResponseBody.Companion.toResponseBody
import org.example.memosm.account.stub
import org.example.memosm.api.MemosApi
import org.example.memosm.api.RateLimitException
import org.example.memosm.model.InstanceProfile
import org.example.memosm.viewmodel.ConnectionState
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ServerReachabilityMonitorTest {
    @Test fun repeatedOfflineFailuresRecoverWithoutAnyConnectivityEvent() =
        runTest {
            var online = false
            var attempts = 0
            val transitions = mutableListOf<ConnectionState>()
            val api =
                stub<MemosApi> { _, _ ->
                    attempts++
                    if (!online) throw IOException("Blocked")
                    InstanceProfile()
                }
            val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { "A" })
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                monitor.state
                    .map { it.connectionState }
                    .distinctUntilChanged()
                    .collect { transitions += it }
            }
            monitor.start()
            repeat(3) {
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(it + 1, attempts)
                assertEquals(ConnectionState.SERVER_UNREACHABLE, monitor.state.value.connectionState)
            }
            online = true
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(monitor.state.value.isOnline)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(5, attempts)
            assertEquals(1, transitions.count { it == ConnectionState.ONLINE })
        }

    @Test fun overlappingChecksShareOneProbeAndOneOnlineTransition() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var attempts = 0
            val api =
                stub<MemosApi> { _, _ ->
                    attempts++
                    release.await()
                    InstanceProfile()
                }
            val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { "A" })
            val first = monitor.checkNow()
            runCurrent()
            val second = monitor.checkNow()
            assertSame(first, second)
            assertEquals(1, attempts)
            release.complete(Unit)
            runCurrent()
            assertEquals(ConnectionState.ONLINE, monitor.state.value.connectionState)
        }

    @Test fun timedOutProbeLeavesCheckingAndCanBeRetried() =
        runTest {
            var hang = true
            val api =
                stub<MemosApi> { _, _ ->
                    if (hang) awaitCancellation()
                    InstanceProfile()
                }
            val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { "A" })
            monitor.checkNow()
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(ConnectionState.SERVER_UNREACHABLE, monitor.state.value.connectionState)
            hang = false
            monitor.checkNow()
            runCurrent()
            assertTrue(monitor.state.value.isOnline)
        }

    @Test fun policyBlockedStateIsRetriedWhenAccessReturnsWithoutAnEvent() =
        runTest {
            var blocked = true
            var attempts = 0
            val api =
                stub<MemosApi> { _, _ ->
                    attempts++
                    InstanceProfile()
                }
            val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { "A" }, { blocked })
            monitor.checkNow()
            monitor.start()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(0, attempts)
            assertEquals(ConnectionState.OFFLINE, monitor.state.value.connectionState)
            blocked = false
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, attempts)
            assertTrue(monitor.state.value.isOnline)
        }

    @Test fun lateResponseCannotRecoverADifferentAccountOrAnABAActivation() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var account = "A"
            val api =
                stub<MemosApi> { _, _ ->
                    withContext(NonCancellable) { release.await() }
                    InstanceProfile()
                }
            val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { account })
            monitor.checkNow()
            runCurrent()
            account = "B"
            monitor.cancelProbe()
            account = "A"
            release.complete(Unit)
            runCurrent()
            assertFalse(monitor.state.value.isOnline)
        }

    @Test fun healthyPeriodicCheckKeepsTheOnlineStateDuringItsRequest() =
        runTest {
            var release: CompletableDeferred<Unit>? = null
            val api =
                stub<MemosApi> { _, _ ->
                    release?.await()
                    InstanceProfile()
                }
            val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { "A" })
            monitor.checkNow()
            runCurrent()
            release = CompletableDeferred()
            monitor.start()
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(monitor.state.value.isOnline)
            release.complete(Unit)
            runCurrent()
            assertTrue(monitor.state.value.isOnline)
        }

    @Test fun rateLimitStaysReachableAndRecoversAfterTheSuppliedCooldown() =
        runTest {
            var calls = 0
            val api =
                stub<MemosApi> { _, _ ->
                    calls++
                    if (calls == 1) {
                        throw HttpException(
                            Response.error<Any>(
                                429,
                                """{"details":[{"metadata":{"retry_after_seconds":"16"}}]}""".toResponseBody(),
                            ),
                        )
                    }
                    InstanceProfile()
                }
            val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { "A" })
            monitor.start()
            monitor.checkNow()
            runCurrent()
            assertTrue(monitor.state.value.isOnline)
            assertEquals(ConnectionState.RATE_LIMITED, monitor.state.value.connectionState)
            advanceTimeBy(15_999)
            runCurrent()
            assertEquals(1, calls)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, calls)
            assertEquals(ConnectionState.ONLINE, monitor.state.value.connectionState)
        }

    @Test fun consecutiveCooldownsUseOneRetryAndAccountSwitchCancelsIt() =
        runTest {
            var calls = 0
            var account = "A"
            val api =
                stub<MemosApi> { _, _ ->
                    calls++
                    yield()
                    throw RateLimitException((16_000 - testScheduler.currentTime).coerceAtLeast(1_000))
                }
            val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { account })
            monitor.checkNow()
            runCurrent()
            advanceTimeBy(1_000)
            monitor.checkNow()
            runCurrent()
            assertEquals(2, calls)
            advanceTimeBy(14_999)
            runCurrent()
            assertEquals(2, calls)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(3, calls)
            account = "B"
            monitor.cancelProbe()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(3, calls)
        }

    @Test fun authRequiredNeverMasqueradesAsOffline() =
        runTest {
            val api = stub<MemosApi> { _, _ -> throw HttpException(Response.error<Any>(401, "expired".toResponseBody())) }
            val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { "A" })
            monitor.checkNow()
            runCurrent()
            assertEquals(ConnectionState.AUTH_REQUIRED, monitor.state.value.connectionState)
        }
}
