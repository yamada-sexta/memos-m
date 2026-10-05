package org.example.memosm.ui.component.item.media

import androidx.media3.common.Player

internal fun playbackSeekPosition(position: Long, duration: Long): Long =
    position.coerceIn(0L, duration.coerceAtLeast(0L))

internal fun playbackProgress(position: Long, duration: Long): Float =
    if (duration > 0L) playbackSeekPosition(position, duration).toFloat() / duration else 0f

internal fun togglePlayback(player: Player) {
    when {
        player.playbackState == Player.STATE_ENDED -> {
            if (player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) player.seekTo(0L)
            player.play()
        }
        player.playWhenReady -> player.pause()
        else -> player.play()
    }
}

