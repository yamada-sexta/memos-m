package org.example.memosm.ui.component.setting

import kotlin.math.abs
import kotlin.random.Random

internal enum class MemoCatchPhase { READY, RUNNING, PAUSED, FINISHED }

internal data class FallingMemo(val id: Int, val x: Float, val spawnedAt: Double) {
    fun y(elapsed: Double): Float =
        ((elapsed - spawnedAt) / MemoCatchState.FALL_SECONDS * MemoCatchState.TRAY_Y).toFloat()
}

/** Coordinates are fractions of the playfield; advancing time also checks swept collisions. */
internal data class MemoCatchState(
    val phase: MemoCatchPhase = MemoCatchPhase.READY,
    val elapsed: Double = 0.0,
    val score: Int = 0,
    val trayX: Float = 0.5f,
    val notes: List<FallingMemo> = emptyList(),
    val spawnedCount: Int = 0,
    val lastCatchAt: Double = -1.0
) {
    fun start(): MemoCatchState = MemoCatchState(phase = MemoCatchPhase.RUNNING)

    fun pause(): MemoCatchState =
        if (phase == MemoCatchPhase.RUNNING) copy(phase = MemoCatchPhase.PAUSED) else this

    fun resume(): MemoCatchState =
        if (phase == MemoCatchPhase.PAUSED) copy(phase = MemoCatchPhase.RUNNING) else this

    fun moveTray(x: Float): MemoCatchState =
        if (phase == MemoCatchPhase.RUNNING && x.isFinite()) {
            copy(trayX = x.coerceIn(TRAY_WIDTH / 2, 1 - TRAY_WIDTH / 2))
        } else this

    fun advance(
        seconds: Double,
        nextX: () -> Float = { Random.nextFloat() }
    ): MemoCatchState {
        if (phase != MemoCatchPhase.RUNNING || !seconds.isFinite() || seconds <= 0) return this
        val end = (elapsed + seconds).coerceAtMost(ROUND_SECONDS)
        var count = spawnedCount
        val falling = notes.toMutableList()
        while ((count + 1) * SPAWN_SECONDS <= end) {
            falling += FallingMemo(
                id = count,
                x = nextX().coerceIn(NOTE_WIDTH / 2, 1 - NOTE_WIDTH / 2),
                spawnedAt = (count + 1) * SPAWN_SECONDS
            )
            count++
        }
        var catches = 0
        var caughtAt = lastCatchAt
        val remaining = falling.filter { note ->
            val reachesTrayAt = note.spawnedAt + FALL_SECONDS
            val crossesTray = reachesTrayAt > elapsed && reachesTrayAt <= end
            val caught = crossesTray && abs(note.x - trayX) <= (TRAY_WIDTH + NOTE_WIDTH) / 2
            if (caught) {
                catches++
                caughtAt = maxOf(caughtAt, reachesTrayAt)
            }
            !caught && note.y(end) < 1 + NOTE_WIDTH
        }
        val finished = end >= ROUND_SECONDS
        return copy(
            phase = if (finished) MemoCatchPhase.FINISHED else phase,
            elapsed = end,
            score = score + catches,
            notes = if (finished) emptyList() else remaining,
            spawnedCount = count,
            lastCatchAt = caughtAt
        )
    }

    companion object {
        const val ROUND_SECONDS = 30.0
        const val SPAWN_SECONDS = 0.8
        const val FALL_SECONDS = 3.0
        const val TRAY_Y = 0.86f
        const val TRAY_WIDTH = 0.24f
        const val NOTE_WIDTH = 0.07f
    }
}
