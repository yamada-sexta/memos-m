package org.example.memosm.ui.component.item.media

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import org.example.memosm.model.Attachment
import kotlin.math.abs

/** Coordinates remain live while a thumbnail is composed; detached or clipped tiles use the fallback. */
internal class AttachmentOrigin {
    var coordinates: LayoutCoordinates? = null

    fun boundsOnScreen(): Rect? {
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return null
        val visible = coordinates.boundsInWindow()
        if (visible.isEmpty || abs(visible.width - coordinates.size.width) > 1f ||
            abs(visible.height - coordinates.size.height) > 1f) return null
        val position = coordinates.localToScreen(Offset.Zero)
        if (!position.isValid()) return null
        return Rect(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height)
    }
}

internal class AttachmentOrigins {
    private val origins = mutableMapOf<String, AttachmentOrigin>()
    private fun key(attachment: Attachment) = attachment.name ?: attachment.clientId
        ?: "${attachment.filename}:${attachment.type}:${attachment.createTime}"

    fun boundsFor(attachment: Attachment): Rect? = origins[key(attachment)]?.boundsOnScreen()

    fun register(attachment: Attachment, origin: AttachmentOrigin) { origins[key(attachment)] = origin }
    fun unregister(attachment: Attachment, origin: AttachmentOrigin) {
        if (origins[key(attachment)] === origin) origins.remove(key(attachment))
    }
}

internal fun Modifier.attachmentOrigin(origins: AttachmentOrigins, attachment: Attachment): Modifier = composed {
    val origin = remember { AttachmentOrigin() }
    DisposableEffect(origins, attachment) {
        origins.register(attachment, origin)
        onDispose { origins.unregister(attachment, origin) }
    }
    onGloballyPositioned { origin.coordinates = it }
}
