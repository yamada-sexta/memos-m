package org.example.memosm.viewmodel.manager

import java.io.IOException
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.example.memosm.account.stub
import org.example.memosm.api.MemosApi
import org.example.memosm.model.InstanceProfile
import org.example.memosm.viewmodel.ConnectionState
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerReachabilityMonitorTest {
    @Test fun `repeated offline failures recover without any connectivity event`() = runTest {
        var online = false
        var attempts = 0
        var recovered = 0
        val api = stub<MemosApi> { _, _ -> attempts++; if (!online) throw IOException("Blocked"); InstanceProfile() }
        val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { "A" })
        monitor.start { recovered++ }
        repeat(3) {
            advanceTimeBy(60_000); runCurrent()
            assertEquals(it + 1, attempts)
            assertEquals(ConnectionState.SERVER_UNREACHABLE, monitor.state.value.connectionState)
        }
        online = true
        advanceTimeBy(60_000); runCurrent()
        assertEquals(4, attempts)
        assertTrue(monitor.state.value.isOnline)
        assertEquals(1, recovered)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(5, attempts)
        assertEquals(1, recovered)
    }

    @Test fun `overlapping probes keep every distinct recovery callback and use one request`() = runTest {
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        var callbacks = 0
        val api = stub<MemosApi> { _, _ -> attempts++; release.await(); InstanceProfile() }
        val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { "A" })
        val first = monitor.checkNow { callbacks++ }
        runCurrent()
        val second = monitor.checkNow { callbacks += 2 }
        assertSame(first, second)
        assertEquals(1, attempts)
        release.complete(Unit); runCurrent()
        assertEquals(3, callbacks)
        assertTrue(monitor.state.value.isOnline)
    }

    @Test fun `timed out probe leaves checking and can be retried`() = runTest {
        var hang = true
        val api = stub<MemosApi> { _, _ -> if (hang) awaitCancellation(); InstanceProfile() }
        val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { "A" })
        monitor.checkNow(); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(ConnectionState.SERVER_UNREACHABLE, monitor.state.value.connectionState)
        hang = false
        monitor.checkNow(); runCurrent()
        assertTrue(monitor.state.value.isOnline)
    }

    @Test fun `policy blocked state is retried when access returns even without an event`() = runTest {
        var blocked = true
        var attempts = 0
        val api = stub<MemosApi> { _, _ -> attempts++; InstanceProfile() }
        val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { "A" }, { blocked })
        monitor.checkNow(); monitor.start {}
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, attempts)
        assertEquals(ConnectionState.OFFLINE, monitor.state.value.connectionState)
        blocked = false
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, attempts)
        assertTrue(monitor.state.value.isOnline)
    }

    @Test fun `late response cannot recover a different account or an A B A activation`() = runTest {
        val release = CompletableDeferred<Unit>()
        var account = "A"
        var callbacks = 0
        val api = stub<MemosApi> { _, _ -> withContext(NonCancellable) { release.await() }; InstanceProfile() }
        val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { account })
        monitor.checkNow { callbacks++ }; runCurrent()
        account = "B"; monitor.cancelProbe()
        account = "A"
        release.complete(Unit); runCurrent()
        assertFalse(monitor.state.value.isOnline)
        assertEquals(0, callbacks)
    }

    @Test fun `healthy periodic check does not briefly report offline`() = runTest {
        var release: CompletableDeferred<Unit>? = null
        val api = stub<MemosApi> { _, _ -> release?.await(); InstanceProfile() }
        val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { "A" })
        monitor.checkNow(); runCurrent()
        release = CompletableDeferred()
        monitor.start {}
        advanceTimeBy(60_000); runCurrent()
        assertTrue(monitor.state.value.isOnline)
        release.complete(Unit); runCurrent()
        assertTrue(monitor.state.value.isOnline)
    }
    @Test fun `429 stays reachable and automatically recovers after the supplied cooldown`() = runTest {
        var calls = 0
        var recoveries = 0
        val api = stub<MemosApi> { _, _ ->
            calls++
            if (calls == 1) throw retrofit2.HttpException(retrofit2.Response.error<Any>(429,
                """{"details":[{"metadata":{"retry_after_seconds":"16"}}]}""".toResponseBody()))
            InstanceProfile()
        }
        val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { "A" })
        monitor.start { recoveries++ }
        monitor.checkNow(); runCurrent()
        assertTrue(monitor.state.value.isOnline)
        assertEquals(ConnectionState.RATE_LIMITED, monitor.state.value.connectionState)
        assertEquals(0, recoveries)
        advanceTimeBy(15_999); runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, calls)
        assertEquals(1, recoveries)
        assertEquals(ConnectionState.ONLINE, monitor.state.value.connectionState)
    }

    @Test fun `cached cooldown does not postpone recovery and account switch cancels retries`() = runTest {
        var calls = 0
        var account = "A"
        val api = stub<MemosApi> { _, _ ->
            calls++
            yield() // Deliver checked IO failures through the suspend continuation, as Retrofit does.
            throw org.example.memosm.api.RateLimitException((16_000 - testScheduler.currentTime).coerceAtLeast(1_000))
        }
        val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { account })
        monitor.checkNow(); runCurrent()
        advanceTimeBy(1_000)
        monitor.checkNow(); runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(14_999); runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, calls)
        account = "B"; monitor.cancelProbe()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, calls)
    }

    @Test fun `consecutive cooldowns recover once without multiplying recovery callbacks`() = runTest {
        var calls = 0
        var recoveries = 0
        var manualHook = 0
        val api = stub<MemosApi> { _, _ ->
            calls++
            yield()
            if (calls <= 3) throw org.example.memosm.api.RateLimitException(1_000)
            InstanceProfile()
        }
        val monitor = ServerReachabilityMonitor(backgroundScope, { api }, { "A" })
        val hook: () -> Unit = { manualHook++ }
        monitor.start { recoveries++ }
        monitor.checkNow(hook); runCurrent()
        repeat(3) { advanceTimeBy(1_000); runCurrent() }
        assertEquals(4, calls)
        assertEquals(1, recoveries)
        assertEquals(1, manualHook)
    }

}
