package org.example.memosm.ui.component.item.media

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import org.example.memosm.model.Attachment
import org.example.memosm.ui.component.item.AttachmentCard
import org.example.memosm.ui.component.item.AttachmentActionsButton
import org.example.memosm.ui.component.item.AttachmentCompactMode
import org.example.memosm.ui.component.item.AttachmentInfoContent
import org.example.memosm.ui.component.item.rememberAttachmentInfo

@Composable
fun FullScreenAttachmentViewer(
    attachments: List<Attachment>,
    initialIndex: Int,
    token: String?,
    hostUrl: String,
    onDismiss: () -> Unit,
    onPageChanged: ((Int) -> Unit)? = null,
    originBounds: ((Attachment) -> Rect?)? = null,
    attachmentKeys: List<String>? = null
) {
    if (attachments.isEmpty() || initialIndex !in attachments.indices) return

    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { attachments.size })
    var aspectRatio by remember(pagerState.currentPage) { mutableStateOf<Float?>(null) }
    LaunchedEffect(pagerState.currentPage) {
        onPageChanged?.invoke(pagerState.currentPage)
    }
    FullScreenMediaDialog(
        onDismiss = onDismiss,
        mediaAspectRatio = aspectRatio,
        gestureKey = pagerState.currentPage,
        originBounds = { originBounds?.invoke(attachments[pagerState.currentPage.coerceIn(attachments.indices)]) },
        infoContent = {
            val attachment = attachments[pagerState.currentPage.coerceIn(attachments.indices)]
            AttachmentInfoContent(rememberAttachmentInfo(attachment))
        },
        actionsContent = { showInfo ->
            val attachment = attachments[pagerState.currentPage.coerceIn(attachments.indices)]
            AttachmentActionsButton(attachment, token, hostUrl, attachment.filename, showInfo)
        }
    ) {
        HorizontalPager(
            state = pagerState, modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !LocalViewerGesturesBlocked.current,
            key = { page -> attachmentKeys?.get(page) ?: page }
        ) { page ->
            CompositionLocalProvider(LocalViewerMediaActive provides (page == pagerState.currentPage)) {
            AttachmentCard(
                attachment = attachments[page], token = token, hostUrl = hostUrl,
                modifier = Modifier.fillMaxSize(), showInfo = false, showActions = false,
                showSize = false, showFilename = false, compactMode = AttachmentCompactMode.Never,
                isFullScreen = true,
                onRatioAvailable = { ratio, exact ->
                    val type = attachments[page].displayType
                    if (page == pagerState.currentPage && exact &&
                        (type.contains("image", ignoreCase = true) || type.contains("video", ignoreCase = true))) {
                        aspectRatio = ratio
                    }
                }
            )
            }
        }
    }
}
