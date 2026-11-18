package org.example.memosm.account

import org.example.memosm.api.GsonProvider
import org.example.memosm.data.sync.*
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoState
import org.junit.Assert.*
import org.junit.Test

class SyncAccountIsolationTest {
    private fun patch(
        id: String,
        memo: Memo,
        mask: String,
        sequence: Long,
    ) = PendingOp
        .new("A", PendingOpType.UPDATE, "memos/1", payloadJson = GsonProvider.gson.toJson(memo), updateMask = mask, id = id)
        .copy(sequence = sequence)

    @Test fun `later pin mutation does not overwrite acknowledged content`() {
        val server = Memo(name = "memos/1", content = "acknowledged new content", pinned = false)
        val oldSnapshot = Memo(name = "memos/1", content = "old content", pinned = true)
        val visible = projectMemo(server, listOf(patch("pin", oldSnapshot, "pinned", 2)))!!
        assertEquals("acknowledged new content", visible.content)
        assertTrue(visible.pinned!!)
    }

    @Test fun `ordered field mutations compose and pending delete stays hidden`() {
        val base = Memo(name = "memos/1", content = "base", pinned = false, state = MemoState.NORMAL)
        val content = patch("content", base.copy(content = "new"), "content", 1)
        val archive = patch("archive", base.copy(state = MemoState.ARCHIVED), "state", 2)
        val visible = projectMemo(base, listOf(archive, content))!!
        assertEquals("new", visible.content)
        assertEquals(MemoState.ARCHIVED, visible.state)
        val delete = PendingOp.new("A", PendingOpType.DELETE, "memos/1").copy(sequence = 3)
        assertNull(projectMemo(base, listOf(content, archive, delete)))
    }

    @Test fun `background conflict snapshot is reconstructable after restart`() {
        val local = Memo(name = "memos/1", content = "local")
        val server = local.copy(content = "server")
        val op =
            patch("edit", local, "content", 1).copy(
                status = PendingOpStatus.CONFLICT.name,
                conflictServerJson = GsonProvider.gson.toJson(server),
            )
        val conflict = op.toConflict()!!
        assertEquals("A", conflict.accountId)
        assertEquals("local", conflict.localMemo.content)
        assertEquals("server", conflict.serverMemo.content)
    }
}
