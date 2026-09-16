package org.example.memosm.ui.component

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPermissionStateTest {
    @Test
    fun localRequestWaitsForPermissionBeforeContinuing() = runBlocking {
        var allowed = false
        var launches = 0
        val state = LocalNetworkPermissionState({ allowed }, {}, { true })
        state.launchRequest = { launches++ }
        val access = async { state.ensureAccess("http://192.168.1.2") }
        yield()
        assertEquals(1, launches)
        assertFalse(access.isCompleted)
        allowed = true
        state.onResult(true)
        assertTrue(access.await())
        assertTrue(state.granted)
        assertFalse(state.showDeniedDialog)
    }

    @Test
    fun denialStopsLoginAndOffersSettings() = runBlocking {
        var settingsOpened = false
        val state = LocalNetworkPermissionState({ false }, { settingsOpened = true }, { true })
        state.launchRequest = { state.onResult(false) }
        assertFalse(state.ensureAccess("http://memos.local"))
        assertTrue(state.showDeniedDialog)
        state.openSettings()
        assertTrue(settingsOpened)
        assertFalse(state.showDeniedDialog)
    }

    @Test
    fun publicServersWorkWithoutLocalPermission() = runBlocking {
        val state = LocalNetworkPermissionState({ false }, {}, { false })
        state.launchRequest = { error("Public servers must not trigger a permission prompt") }
        assertTrue(state.ensureAccess("https://memos.example.com"))
    }

    @Test
    fun grantedPermissionBypassesDetectionAndPrompt() = runBlocking {
        val state = LocalNetworkPermissionState({ true }, {}, { error("No lookup needed") })
        state.launchRequest = { error("Already granted") }
        assertTrue(state.ensureAccess("http://memos.local"))
    }

    @Test
    fun concurrentRequestsShareOneSystemPrompt() = runBlocking {
        var allowed = false
        var launches = 0
        val state = LocalNetworkPermissionState({ allowed }, {}, { true })
        state.launchRequest = { launches++ }
        val first = async { state.requestAccess() }
        val second = async { state.requestAccess() }
        yield()
        assertEquals(1, launches)
        allowed = true
        state.onResult(true)
        assertTrue(first.await())
        assertTrue(second.await())
    }

    @Test
    fun permissionIsRecheckedAfterRevocationAndReturningFromSettings() = runBlocking {
        var allowed = true
        val state = LocalNetworkPermissionState({ allowed }, {}, { true })
        allowed = false
        state.launchRequest = { state.onResult(false) }
        assertFalse(state.ensureAccess("http://memos.local"))
        assertFalse(state.granted)
        allowed = true
        state.refresh()
        assertTrue(state.granted)
        assertFalse(state.showDeniedDialog)
    }

    @Test
    fun disposalCancelsPendingRequests() = runBlocking {
        val state = LocalNetworkPermissionState({ false }, {}, { true })
        val access = async { state.requestAccess() }
        yield()
        state.dispose()
        access.join()
        assertTrue(access.isCancelled)
    }
}
