package org.example.memosm.viewmodel

import androidx.annotation.StringRes

/** A locale-resolved UI message represented as a resource and format arguments. */
data class UiMessage(
    @param:StringRes val resourceId: Int,
    val formatArgs: List<Any> = emptyList(),
)
