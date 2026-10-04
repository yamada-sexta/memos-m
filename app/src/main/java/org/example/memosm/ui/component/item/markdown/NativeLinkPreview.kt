package org.example.memosm.ui.component.item.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.example.memosm.model.LinkMetadata
import org.example.memosm.data.linkpreview.LinkPreviewRepository

/** Supplied once by MainScreen; memo screens do not need preview-specific parameters. */
data class LinkPreviewEnvironment(val repository: LinkPreviewRepository, val isOnline: Boolean)
val LocalLinkPreviews = compositionLocalOf<LinkPreviewEnvironment?> { null }

/** Keeps the existing paragraph visible until a useful preview is available. */
@Composable
fun NativeLinkPreview(
    url: String,
    repository: LinkPreviewRepository,
    isOnline: Boolean
) {
    var visible by remember(url, repository) { mutableStateOf(false) }
    var metadata by remember(url, repository) { mutableStateOf<LinkMetadata?>(null) }
    LaunchedEffect(url, repository, isOnline, visible) {
        if (visible) metadata = repository.get(url)
    }
    Box(Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
        // boundsInWindow clips to parents, including collapsed memo cards and lazy lists.
        val bounds = coordinates.boundsInWindow()
        visible = bounds.width > 0 && bounds.height > 0
    }) {
        val preview = metadata
        if (preview == null || (preview.title.isBlank() && preview.description.isBlank())) {
            val color = MaterialTheme.colorScheme.primary
            MarkdownText(
                text = remember(url, color) {
                    buildAnnotatedString {
                        pushLink(LinkAnnotation.Url(url))
                        withStyle(SpanStyle(color = color)) { append(url) }
                        pop()
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            LinkPreviewCard(url, preview)
        }
    }
}

@Composable
internal fun LinkPreviewCard(url: String, metadata: LinkMetadata) {
    val uriHandler = LocalUriHandler.current
    val image = metadata.image.trim().takeIf { it.toHttpUrlOrNull() != null }
    var imageFailed by remember(url, image) { mutableStateOf(false) }
    Surface(
        onClick = { uriHandler.openUri(url) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = url.toHttpUrlOrNull()?.host.orEmpty().removePrefix("www."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (metadata.title.isNotBlank()) Text(
                    text = metadata.title.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (metadata.description.isNotBlank()) Text(
                    text = metadata.description.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (image != null && !imageFailed) AsyncImage(
                model = image,
                contentDescription = null,
                modifier = Modifier.width(100.dp).aspectRatio(1.91f).testTag("link_preview_thumbnail"),
                contentScale = ContentScale.Crop,
                onError = { imageFailed = true }
            )
        }
    }
}
