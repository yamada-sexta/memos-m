package org.example.memosm.ui.component.setting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.isActive
import org.example.memosm.R
import kotlin.math.ceil
import kotlin.math.sin

@Composable
internal fun MemoCatchDialog(onDismiss: () -> Unit) {
    // Intentionally ephemeral: closing or recreating the screen starts a fresh discovery.
    var game by remember { mutableStateOf(MemoCatchState()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                game = game.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(game.phase, lifecycleOwner) {
        if (game.phase != MemoCatchPhase.RUNNING) return@LaunchedEffect
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            game = game.pause()
            return@LaunchedEffect
        }
        var previous = withFrameNanos { it }
        while (isActive && game.phase == MemoCatchPhase.RUNNING) {
            val now = withFrameNanos { it }
            game = game.advance((now - previous) / 1_000_000_000.0)
            previous = now
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.memo_catch_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.common_close))
                    }
                }
                Text(
                    stringResource(R.string.memo_catch_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Column(
                    Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.memo_catch_score, game.score))
                        Text(stringResource(
                            R.string.memo_catch_time,
                            ceil(MemoCatchState.ROUND_SECONDS - game.elapsed).toInt()
                        ))
                    }
                    LinearProgressIndicator(
                        progress = { (1 - game.elapsed / MemoCatchState.ROUND_SECONDS).toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                BoxWithConstraints(
                    Modifier.weight(1f).widthIn(max = 600.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    val compact = maxHeight < 260.dp
                    MemoCatchPlayfield(game, onMove = { game = game.moveTray(it) })
                    if (game.phase != MemoCatchPhase.RUNNING) {
                        Box(
                            Modifier.fillMaxSize().background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(Modifier.padding(16.dp).widthIn(max = 360.dp)) {
                                Column(
                                    Modifier.verticalScroll(rememberScrollState()).padding(if (compact) 12.dp else 20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (!compact) {
                                        Text(
                                            if (game.phase == MemoCatchPhase.FINISHED) "(ﾉ´ヮ`)ﾉ*:･ﾟ✧" else "(o^ᴗ^o)",
                                            style = MaterialTheme.typography.headlineMedium
                                        )
                                    }
                                    Text(
                                        stringResource(when (game.phase) {
                                            MemoCatchPhase.FINISHED -> R.string.memo_catch_finished
                                            MemoCatchPhase.PAUSED -> R.string.memo_catch_paused
                                            else -> R.string.memo_catch_welcome
                                        }),
                                        style = MaterialTheme.typography.titleMedium,
                                        textAlign = TextAlign.Center
                                    )
                                    if (game.phase == MemoCatchPhase.FINISHED) {
                                        Text(stringResource(R.string.memo_catch_score, game.score))
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(onClick = {
                                            game = if (game.phase == MemoCatchPhase.PAUSED) game.resume() else game.start()
                                        }) {
                                            Text(stringResource(when (game.phase) {
                                                MemoCatchPhase.FINISHED -> R.string.memo_catch_replay
                                                MemoCatchPhase.PAUSED -> R.string.memo_catch_resume
                                                else -> R.string.memo_catch_play
                                            }))
                                        }
                                        if (game.phase == MemoCatchPhase.FINISHED) {
                                            TextButton(onClick = onDismiss) {
                                                Text(stringResource(R.string.common_close))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoCatchPlayfield(game: MemoCatchState, onMove: (Float) -> Unit) {
    val move by rememberUpdatedState(onMove)
    val description = stringResource(R.string.memo_catch_instruction)
    val trayPosition = stringResource(R.string.memo_catch_tray_position, (game.trayX * 100).toInt())
    val moveLeft = stringResource(R.string.memo_catch_move_left)
    val moveRight = stringResource(R.string.memo_catch_move_right)
    val colors = MaterialTheme.colorScheme
    val noteColors = listOf(colors.primaryContainer, colors.secondaryContainer, colors.tertiaryContainer)
    val inkColors = listOf(colors.onPrimaryContainer, colors.onSecondaryContainer, colors.onTertiaryContainer)
    Canvas(
        Modifier.fillMaxSize().testTag("memo_catch_playfield")
            .semantics {
                if (game.phase == MemoCatchPhase.RUNNING) {
                    contentDescription = description
                    stateDescription = trayPosition
                    customActions = listOf(
                        CustomAccessibilityAction(moveLeft) { move(game.trayX - 0.1f); true },
                        CustomAccessibilityAction(moveRight) { move(game.trayX + 0.1f); true }
                    )
                }
            }
            .pointerInput(game.phase) {
                if (game.phase == MemoCatchPhase.RUNNING) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        move(down.position.x / size.width)
                        down.consume()
                        horizontalDrag(down.id) { change ->
                            move(change.position.x / size.width)
                            change.consume()
                        }
                    }
                }
            }
    ) {
        val noteSize = size.width * MemoCatchState.NOTE_WIDTH
        game.notes.forEach { note ->
            val left = note.x * size.width - noteSize / 2
            val top = note.y(game.elapsed) * size.height - noteSize / 2
            val ink = inkColors[note.id % inkColors.size]
            drawRoundRect(
                noteColors[note.id % noteColors.size], Offset(left, top), Size(noteSize, noteSize),
                CornerRadius(noteSize * 0.12f)
            )
            val fold = Path().apply {
                moveTo(left + noteSize * 0.7f, top)
                lineTo(left + noteSize, top + noteSize * 0.3f)
                lineTo(left + noteSize * 0.7f, top + noteSize * 0.3f)
                close()
            }
            drawPath(fold, ink.copy(alpha = 0.2f))
            for (line in 0..1) {
                val y = top + noteSize * (0.5f + line * 0.2f)
                drawLine(
                    ink.copy(alpha = 0.4f), Offset(left + noteSize * 0.2f, y),
                    Offset(left + noteSize * 0.7f, y), strokeWidth = noteSize * 0.05f
                )
            }
        }
        val trayWidth = size.width * MemoCatchState.TRAY_WIDTH
        val trayHeight = minOf(trayWidth * 0.4f, size.height * 0.12f)
        val sinceCatch = game.elapsed - game.lastCatchAt
        val celebrating = game.lastCatchAt >= 0 && sinceCatch < 0.35
        val bounce = if (celebrating) sin(sinceCatch / 0.35 * Math.PI).toFloat() * trayHeight * 0.18f else 0f
        val left = game.trayX * size.width - trayWidth / 2
        val top = size.height * MemoCatchState.TRAY_Y - bounce
        drawRoundRect(
            colors.primary, Offset(left, top), Size(trayWidth, trayHeight), CornerRadius(trayHeight * 0.35f)
        )
        val eyeY = top + trayHeight * 0.38f
        for (eye in listOf(0.35f, 0.65f)) {
            drawCircle(colors.onPrimary, trayHeight * 0.06f, Offset(left + trayWidth * eye, eyeY))
        }
        drawArc(
            colors.onPrimary, startAngle = 0f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(left + trayWidth * 0.4f, top + trayHeight * 0.42f),
            size = Size(trayWidth * 0.2f, trayHeight * if (celebrating) 0.4f else 0.25f),
            style = Stroke(width = trayHeight * 0.05f)
        )
        if (celebrating) {
            for (side in listOf(-1, 1)) {
                val center = Offset(game.trayX * size.width + side * trayWidth * 0.65f, top)
                drawLine(colors.tertiary, center - Offset(4.dp.toPx(), 0f), center + Offset(4.dp.toPx(), 0f), 2.dp.toPx())
                drawLine(colors.tertiary, center - Offset(0f, 4.dp.toPx()), center + Offset(0f, 4.dp.toPx()), 2.dp.toPx())
            }
        }
    }
}
