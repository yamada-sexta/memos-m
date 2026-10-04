package org.example.memosm.ui.component.setting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoCatchStateTest {
    @Test
    fun caughtNoteScoresOnceAndDisappearsEvenAcrossALongFrame() {
        val game = MemoCatchState().start().advance(3.9) { 0.5f }
        assertEquals(1, game.score)
        assertTrue(game.notes.none { it.id == 0 })
        assertEquals(1, game.advance(0.1) { 0.5f }.score)
    }

    @Test
    fun missedNoteFallsAwayWithoutEndingTheRound() {
        val game = MemoCatchState().start().advance(5.0) { 0.95f }
        assertEquals(0, game.score)
        assertEquals(MemoCatchPhase.RUNNING, game.phase)
        assertTrue(game.notes.none { it.id == 0 })
    }

    @Test
    fun trayIsClampedAndCanCatchAtBothEdges() {
        val left = MemoCatchState().start().moveTray(-1f)
        val right = MemoCatchState().start().moveTray(2f)
        assertEquals(0.12f, left.trayX, 0.001f)
        assertEquals(0.88f, right.trayX, 0.001f)
        assertEquals(1, left.advance(3.9) { 0f }.score)
        assertEquals(1, right.advance(3.9) { 1f }.score)
    }

    @Test
    fun onlyNotesOverlappingTrayAreCaught() {
        val inside = MemoCatchState().start().advance(3.9) { 0.65f }
        val outside = MemoCatchState().start().advance(3.9) { 0.66f }
        assertEquals(1, inside.score)
        assertEquals(0, outside.score)
    }

    @Test
    fun roundEndsAtThirtySecondsAndReplayClearsEverything() {
        val game = MemoCatchState().start().advance(50.0) { 0.5f }
        assertEquals(30.0, game.elapsed, 0.0)
        assertEquals(MemoCatchPhase.FINISHED, game.phase)
        assertEquals(33, game.score)
        assertTrue(game.notes.isEmpty())
        assertEquals(game, game.advance(1.0))
        assertEquals(MemoCatchState(phase = MemoCatchPhase.RUNNING), game.start())
    }

    @Test
    fun pauseFreezesTimeAndResumeContinuesSameRound() {
        val running = MemoCatchState().start().advance(2.0) { 0.5f }
        val paused = running.pause()
        assertEquals(MemoCatchPhase.PAUSED, paused.phase)
        assertEquals(paused, paused.advance(20.0))
        assertEquals(paused, paused.moveTray(0.1f))
        assertEquals(running, paused.resume())
        assertEquals(1, paused.resume().advance(2.0) { 0.5f }.score)
        assertEquals(MemoCatchState(), MemoCatchState().pause())
    }

    @Test
    fun smallFramesAndLargeFramesProduceSameGameplay() {
        var stepped = MemoCatchState().start()
        repeat(300) { stepped = stepped.advance(0.1) { 0.5f } }
        val jumped = MemoCatchState().start().advance(30.0) { 0.5f }
        assertEquals(jumped.phase, stepped.phase)
        assertEquals(jumped.score, stepped.score)
        assertEquals(jumped.spawnedCount, stepped.spawnedCount)
    }
}
