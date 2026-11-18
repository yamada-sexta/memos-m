package org.example.memosm.viewmodel.manager

import org.example.memosm.model.Memo

val USER_MEMO_COMPARATOR: Comparator<Memo> =
    compareByDescending<Memo> { it.pinned == true }
        .thenByDescending { it.displayTime?.toEpochMilliseconds() ?: 0L }

data class LocalSearchFilter(
    val query: String = "",
    val tags: List<String> = emptyList(),
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    val explore: Boolean = false,
)
