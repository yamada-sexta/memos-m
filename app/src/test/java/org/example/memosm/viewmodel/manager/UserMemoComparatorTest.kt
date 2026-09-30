package org.example.memosm.viewmodel.manager

import kotlin.time.Instant
import org.example.memosm.model.Memo
import org.junit.Assert.assertEquals
import org.junit.Test

class UserMemoComparatorTest {

    @Test
    fun newestOfflineMemoSortsBeforeOlderMemo() {
        val older = Memo(
            name = "memos/1",
            content = "older",
            displayTime = Instant.parse("2026-08-15T10:00:00Z")
        )
        val offlineCreate = Memo(
            name = "offline-1",
            content = "new",
            displayTime = Instant.parse("2026-08-15T10:01:00Z")
        )

        val sorted = listOf(older, offlineCreate).sortedWith(USER_MEMO_COMPARATOR)

        assertEquals("offline-1", sorted.first().name)
    }

    @Test
    fun pinnedMemoStillSortsBeforeNewerUnpinnedMemo() {
        val pinned = Memo(
            name = "memos/pinned",
            content = "pinned",
            pinned = true,
            displayTime = Instant.parse("2026-08-15T10:00:00Z")
        )
        val newer = Memo(
            name = "offline-2",
            content = "new",
            pinned = false,
            displayTime = Instant.parse("2026-08-15T10:01:00Z")
        )

        val sorted = listOf(newer, pinned).sortedWith(USER_MEMO_COMPARATOR)

        assertEquals("memos/pinned", sorted.first().name)
    }
}
