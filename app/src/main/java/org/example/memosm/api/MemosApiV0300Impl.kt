package org.example.memosm.api

import org.example.memosm.model.Shortcut
import org.example.memosm.model.ShortcutResponse
import retrofit2.HttpException

class MemosApiV0300Impl(
    private val apiV0300: MemosApiV0300,
    private val legacyApi: MemosApi = MemosApiV0280Impl(apiV0300)
) : MemosApi by legacyApi {
    override val constants = legacyApi.constants.copy(celTimestampLiterals = true)

    @Volatile
    private var memoViewsAvailable = true

    override suspend fun getShortcuts(user: String): ShortcutResponse {
        return withLegacyFallback(
            memoViewCall = {
                ShortcutResponse(shortcuts = apiV0300.listMemoViewsV0300(user).memoViews)
            },
            legacyCall = { legacyApi.getShortcuts(user) }
        )
    }

    override suspend fun createShortcut(
        user: String,
        shortcut: Shortcut,
        validateOnly: Boolean?
    ): Shortcut {
        return withLegacyFallback(
            memoViewCall = { apiV0300.createMemoViewV0300(user, shortcut, validateOnly) },
            legacyCall = { legacyApi.createShortcut(user, shortcut, validateOnly) }
        )
    }

    override suspend fun deleteShortcut(user: String, shortcut: String) {
        return withLegacyFallback(
            memoViewCall = { apiV0300.deleteMemoViewV0300(user, shortcut) },
            legacyCall = { legacyApi.deleteShortcut(user, shortcut) }
        )
    }

    override suspend fun updateShortcut(
        user: String,
        shortcut: String,
        shortcutData: Shortcut,
        updateMask: String?
    ): Shortcut {
        return withLegacyFallback(
            memoViewCall = {
                apiV0300.updateMemoViewV0300(user, shortcut, shortcutData, updateMask)
            },
            legacyCall = { legacyApi.updateShortcut(user, shortcut, shortcutData, updateMask) }
        )
    }

    private suspend fun <T> withLegacyFallback(
        memoViewCall: suspend () -> T,
        legacyCall: suspend () -> T
    ): T {
        if (!memoViewsAvailable) return legacyCall()

        return try {
            memoViewCall()
        } catch (exception: HttpException) {
            if (exception.code() != 404) throw exception
            memoViewsAvailable = false
            legacyCall()
        }
    }
}
