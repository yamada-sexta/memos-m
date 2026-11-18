package org.example.memosm.data.paging

import org.example.memosm.api.MemoOrderBy
import org.example.memosm.api.MemoSearchFilter
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoState
import org.example.memosm.model.UserSnapshot
import org.example.memosm.model.Visibility
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant

class MemoQueryTest {
    private val user = UserSnapshot(name = "users/me")
    private val memo =
        Memo(
            name = "memos/one",
            creator = "users/me",
            content = "Coffee trip",
            tags = listOf("trip", "coffee"),
            createTime = Instant.fromEpochMilliseconds(10_000),
        )

    @Test fun `comment thread identity is independent of row count`() {
        assertEquals("COMMENT:memos/one", MemoQuery(CacheListType.COMMENT, parentName = "memos/one").key)
        assertNotEquals(
            MemoQuery(CacheListType.COMMENT, parentName = "memos/one").key,
            MemoQuery(CacheListType.COMMENT, parentName = "memos/two").key,
        )
    }

    @Test fun `search identity includes scope and ordering with normalized tag sets`() {
        val a = MemoQuery(CacheListType.SEARCH, search = MemoSearchFilter(tags = linkedSetOf("trip", "coffee")))
        val b = a.copy(search = MemoSearchFilter(tags = linkedSetOf("coffee", "trip")))
        assertEquals(a.key, b.key)
        assertNotEquals(a.key, a.copy(explore = true).key)
        assertNotEquals(a.key, a.copy(order = MemoOrderBy.OLDEST).key)
    }

    @Test fun `offline search cannot expose private memos in Explore or another users personal feed`() {
        val personal = MemoQuery(CacheListType.SEARCH, search = MemoSearchFilter())
        val explore = personal.copy(explore = true)
        assertTrue(matchesLocalQuery(memo, personal, user))
        assertFalse(matchesLocalQuery(memo.copy(creator = "users/other"), personal, user))
        assertFalse(matchesLocalQuery(memo, explore, user))
        assertTrue(matchesLocalQuery(memo.copy(visibility = Visibility.PUBLIC), explore, user))
        assertTrue(matchesLocalQuery(memo.copy(visibility = Visibility.PROTECTED), explore, user))
    }

    @Test fun `personal search removes archived projections just as the servers default normal search does`() {
        val query = MemoQuery(CacheListType.SEARCH, search = MemoSearchFilter())
        assertTrue(matchesLocalQuery(memo.copy(state = MemoState.NORMAL), query, user))
        assertFalse(matchesLocalQuery(memo.copy(state = MemoState.ARCHIVED), query, user))
    }

    @Test fun `offline predicates apply text all tags and inclusive end day`() {
        val query =
            MemoQuery(
                CacheListType.SEARCH,
                search =
                    MemoSearchFilter(query = "coffee", tags = setOf("trip", "coffee"), startDateMillis = 0, endDateMillis = 0),
            )
        assertTrue(matchesLocalQuery(memo, query, user))
        assertFalse(matchesLocalQuery(memo.copy(tags = listOf("coffee")), query, user))
        assertFalse(matchesLocalQuery(memo.copy(createTime = Instant.fromEpochMilliseconds(86_400_000)), query, user))
        assertTrue(matchesLocalQuery(memo.copy(createTime = Instant.fromEpochMilliseconds(86_399_999)), query, user))
    }

    @Test fun `hashtag filters never return unrelated rows and arbitrary server filters never use unfiltered fallback`() {
        assertTrue(matchesLocalQuery(memo, MemoQuery(CacheListType.USER, hashtag = "#trip"), user))
        assertFalse(matchesLocalQuery(memo, MemoQuery(CacheListType.USER, hashtag = "other"), user))
        assertFalse(matchesLocalQuery(memo, MemoQuery(CacheListType.USER, serverFilter = "has_link == true"), user))
    }
}
