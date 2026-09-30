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
import org.example.memosm.viewmodel.MemosViewModel
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoComposerScreen(
    onDismiss: () -> Unit,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    viewModel: MemosViewModel,
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
    val uiState by viewModel.uiState.collectAsState()

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
                            viewModel.memoActionDelegate.updateMemo(
                                initialMemo, content, visibility, attachments, location
                            ) {
                                onDismiss()
                            }
                        }

                        parentMemo != null -> {
                            viewModel.memoActionDelegate.createComment(parentMemo, content)
                            onDismiss()
                        }

                        else -> {
                            viewModel.memoActionDelegate.createMemo(
                                content,
                                visibility,
                                attachments,
                                location
                            ) {
                                onDismiss()
                            }
                        }
                    }
                },
                onUploadFile = { uri, context ->
                    viewModel.memoActionDelegate.uploadAttachment(uri, context)
                },
                availableTags = uiState.session.userStats?.tagCount ?: emptyMap(),
                token = uiState.session.token,
                hostUrl = hostUrl,
                isPosting = uiState.isPosting,
                initialContent = effectiveInitialContent,
                initialVisibility = initialMemo?.visibility ?: initialVisibility
                ?: parentMemo?.visibility ?: uiState.session.userSettings?.memoVisibility
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
                        viewModel.draftDelegate.saveDraft(
                            content,
                            visibility,
                            attachments,
                            location
                        )
                    }
                } else null,
                onDiscardQueuedUpload = { clientId ->
                    viewModel.memoActionDelegate.discardQueuedUploadIfOrphaned(clientId)
                }
            )
        }
    }
}

@Composable
fun MemoEditScreen(
    memo: Memo, onDismiss: () -> Unit, viewModel: MemosViewModel, hostUrl: String,
    onToggleNavBar: ((Boolean) -> Unit)? = null
) {
    MemoComposerScreen(
        onDismiss = onDismiss,
        onToggleNavBar = onToggleNavBar,
        viewModel = viewModel,
        hostUrl = hostUrl,
        title = stringResource(R.string.memo_dialog_edit_title),
        initialMemo = memo,
        mode = ComposerMode.UPDATE
    )
}
