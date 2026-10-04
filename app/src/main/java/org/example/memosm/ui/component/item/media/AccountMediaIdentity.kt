package org.example.memosm.ui.component.item.media

import androidx.compose.runtime.staticCompositionLocalOf

/** Supplied by the account screen tree; a host URL alone cannot identify an account. */
data class AccountMediaIdentity(val id: String, val generation: Long)

val LocalAccountMediaIdentity = staticCompositionLocalOf<AccountMediaIdentity?> { null }

fun accountMediaCacheKey(accountId: String?, resource: String): String =
    "${accountId ?: "anonymous"}\u0000$resource"
