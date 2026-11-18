package org.example.memosm.ui.component.composer

import android.net.Uri
import org.example.memosm.model.Attachment

/** Preserve selected sources; the durable write path normalizes inline and restored files. */
internal suspend fun collectComposerAttachments(
    items: List<Pair<Uri, Attachment?>>,
    stage: suspend (Uri) -> Attachment,
    onPrepared: (Int, Attachment) -> Unit = { _, _ -> },
): List<Attachment> =
    items.mapIndexed { index, (uri, attachment) ->
        attachment ?: run {
            require(uri != Uri.EMPTY) { "Attachment has no source" }
            stage(uri).also { onPrepared(index, it) }
        }
    }
