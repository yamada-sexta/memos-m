package org.example.memosm.viewmodel.manager

import java.io.IOException
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
        release!!.complete(Unit); runCurrent()
        assertTrue(monitor.state.value.isOnline)
    }
}
