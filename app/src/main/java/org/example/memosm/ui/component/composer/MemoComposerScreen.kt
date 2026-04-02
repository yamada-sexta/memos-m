package org.example.memosm.ui.component.composer

import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.model.Attachment
import org.example.memosm.model.Location
import org.example.memosm.model.Memo
import org.example.memosm.model.Visibility
import org.example.memosm.state.DraftControls
import org.example.memosm.state.MemoActionControls
import org.example.memosm.state.SessionControls
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoComposerScreen(
    onDismiss: () -> Unit,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    actionControls: MemoActionControls?,
    sessionControls: SessionControls?,
    draftControls: DraftControls?,
    hostUrl: String,
    title: String,
    initialMemo: Memo? = null,
    parentMemo: Memo? = null,
    initialContent: String = "",
    initialUris: List<Uri> = emptyList(),
    initialAttachments: List<Attachment> = emptyList(),
    initialVisibility: Visibility? = null,
    initialLocation: Location? = null,
    mode: ComposerMode = when {
        initialMemo != null -> ComposerMode.UPDATE
        parentMemo != null -> ComposerMode.COMMENT
        else -> ComposerMode.PUBLISH
    }
) {
    // Hide bottom nav bar while composer is visible
    DisposableEffect(Unit) {
        onToggleNavBar?.invoke(false)
        onDispose { onToggleNavBar?.invoke(true) }
    }

    // Predictive Back Animation State
    val scale = remember { Animatable(1f) }

    PredictiveBackHandler { progress ->
        try {
            progress.collect { backEvent ->
                scale.snapTo(1f - backEvent.progress * 0.1f)
            }
            onDismiss()
        } catch (e: CancellationException) {
            scale.animateTo(1f)
        }
    }

    Scaffold(
        modifier = Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            shape = RoundedCornerShape(28.dp)
            clip = true
        },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.memo_detail_back)
                        )
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            val effectiveInitialContent = initialMemo?.content ?: initialContent

            MemoComposer(
                onPublish = { content, visibility, attachments, location ->
                    when {
                        initialMemo != null -> {
                            // TODO actionControls?.updateMemo?.invoke(initialMemo, content, visibility, attachments, location)
                            onDismiss()
                        }

                        parentMemo != null -> {
                            // TODO actionControls?.createComment?.invoke(parentMemo, content)
                            onDismiss()
                        }

                        else -> {
                            val memo = Memo(content = content, visibility = visibility, attachments = attachments, location = location)
                            actionControls?.postMemo?.invoke(memo)
                            onDismiss()
                        }
                    }
                },
                onUploadFile = { uri, context ->
                    // TODO actionControls?.uploadAttachment(uri, context)
                    null // return null to match signature
                },
                availableTags = sessionControls?.state?.userStats?.tagCount ?: emptyMap(),
                token = sessionControls?.state?.token ?: "",
                hostUrl = hostUrl,
                isPosting = false, // TODO
                initialContent = effectiveInitialContent,
                initialVisibility = initialMemo?.visibility ?: initialVisibility
                ?: parentMemo?.visibility ?: sessionControls?.state?.userSettings?.memoVisibility
                ?: Visibility.PRIVATE,
                initialAttachments = initialMemo?.attachments ?: initialAttachments,
                initialUris = if (initialMemo == null) initialUris else emptyList(),
                initialLocation = initialMemo?.location ?: initialLocation,
                mode = mode,
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                onDraftChanged = if (initialMemo == null && parentMemo == null) {
                    { content, visibility, attachments, location ->
                        draftControls?.saveDraft?.invoke(
                             org.example.memosm.model.Draft(
                                content = content,
                                attachments = attachments,
                                visibility = visibility,
                                location = location
                            )
                        )
                    }
                } else null
            )
        }
    }
}

@Composable
fun MemoEditScreen(
    memo: Memo, onDismiss: () -> Unit,
    actionControls: MemoActionControls?, sessionControls: SessionControls?, draftControls: DraftControls?, hostUrl: String,
    onToggleNavBar: ((Boolean) -> Unit)? = null
) {
    MemoComposerScreen(
        onDismiss = onDismiss,
        onToggleNavBar = onToggleNavBar,
        actionControls = actionControls,
        sessionControls = sessionControls,
        draftControls = draftControls,
        hostUrl = hostUrl,
        title = stringResource(R.string.memo_dialog_edit_title),
        initialMemo = memo,
        mode = ComposerMode.UPDATE
    )
}
