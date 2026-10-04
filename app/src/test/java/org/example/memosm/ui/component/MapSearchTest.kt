package org.example.memosm.ui.component

import org.example.memosm.api.MemoOrderBy
import org.example.memosm.model.Memo
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

class MapSearchTest {
    @Test fun `map search combines text tags and inclusive date range`() {
        val day = 86_400_000L
        val memos = listOf(
            Memo(name = "memos/first", content = "COFFEE", tags = listOf("trip", "cafe"), createTime = Instant.fromEpochMilliseconds(day)),
            Memo(name = "memos/last", content = "coffee at night", tags = listOf("trip", "cafe"), createTime = Instant.fromEpochMilliseconds(3 * day - 1)),
            Memo(name = "memos/later", content = "coffee", tags = listOf("trip", "cafe"), createTime = Instant.fromEpochMilliseconds(3 * day)),
            Memo(name = "memos/tag", content = "coffee", tags = listOf("trip"), createTime = Instant.fromEpochMilliseconds(day)),
            Memo(name = "memos/unknown-time", content = "coffee", tags = listOf("trip", "cafe"))
        )
        assertEquals(listOf("memos/last", "memos/first"),
            filterLocalSearchMemos(memos, "coffee", setOf("trip", "cafe"), day, 2 * day, MemoOrderBy.NEWEST).map { it.name })
        assertEquals(listOf("memos/first", "memos/last"),
            filterLocalSearchMemos(memos, "coffee", setOf("trip", "cafe"), day, 2 * day, MemoOrderBy.OLDEST).map { it.name })
    }
}
