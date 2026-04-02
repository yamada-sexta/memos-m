package org.example.memosm.ui.nav

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.model.Draft
import org.example.memosm.state.DraftControls
import org.example.memosm.state.MemoActionControls
import org.example.memosm.state.SessionControls
import org.example.memosm.state.AppSettingsControls
import org.example.memosm.ui.component.composer.ComposerMode
import org.example.memosm.ui.component.composer.MemoComposerScreen
import org.example.memosm.ui.component.item.MemoItem
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftsScreen(
    draftControls: DraftControls,
    actionControls: MemoActionControls?,
    sessionControls: SessionControls?,
    appSettingsControls: AppSettingsControls?,
    onDismiss: () -> Unit
) {
    val drafts = draftControls.state.drafts

    var showComposer by remember { mutableStateOf(false) }
    var activeDraft by remember { mutableStateOf<Draft?>(null) }
    var draftToDelete by remember { mutableStateOf<Draft?>(null) }

    // Predictive Back Animation State
    val scale = remember { Animatable(1f) }

    PredictiveBackHandler { progress ->
        try {
            progress.collect { backEvent ->
                // Shrink slightly as user swipes back (simulating the "particle" effect intent)
                scale.snapTo(1f - backEvent.progress * 0.1f)
            }
            // Commit back gesture
            onDismiss()
        } catch (e: CancellationException) {
            // Revert animation if gesture cancelled
            scale.animateTo(1f)
        }
    }

    Scaffold(modifier = Modifier.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        shape = RoundedCornerShape(28.dp)
        clip = true
    }, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.drafts_title)) }, navigationIcon = {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.memo_detail_back)
                )
            }
        })
    }) { padding ->
        if (drafts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.drafts_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(drafts, key = { it.id }) { draft ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        MemoItem(
                            memo = draft.toMemo(),
                            user = null,
                            currentUser = sessionControls?.state?.currUser,
                            token = sessionControls?.state?.token ?: "",
                            hostUrl = sessionControls?.state?.hostUrl ?: "",
                            onClick = {
                                activeDraft = draft
                                showComposer = true
                            },
                            onEdit = {
                                activeDraft = draft
                                showComposer = true
                            },
                            onDelete = { draftToDelete = draft },
                            onUpsertReaction = { },
                            onDeleteReaction = { },
                            onContentUpdate = null,
                            maxHeight = 400.dp,
                            modifier = Modifier.widthIn(max = 800.dp),
                            headerScale = appSettingsControls?.settings?.headerScale ?: 1.0f
                        )
                    }
                }
            }
        }
    }

    // Edit Draft Screen - opens composer with "Publish" button
    val enterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val exitEasing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    AnimatedVisibility(
        visible = showComposer,
        enter = slideInVertically(
            animationSpec = tween(400, easing = enterEasing), initialOffsetY = { it }) + fadeIn(
            animationSpec = tween(400, easing = enterEasing)
        ),
        exit = slideOutVertically(
            animationSpec = tween(200, easing = exitEasing), targetOffsetY = { it }) + fadeOut(
            animationSpec = tween(200, easing = exitEasing)
        )
    ) {
        if (activeDraft != null) {
            MemoComposerScreen(
                onDismiss = {
                    showComposer = false
                },
                actionControls = actionControls,
                sessionControls = sessionControls,
                draftControls = draftControls,
                hostUrl = sessionControls?.state?.hostUrl ?: "",
                title = stringResource(R.string.drafts_action_edit),
                initialContent = activeDraft!!.content,
                initialAttachments = activeDraft!!.attachments,
                initialVisibility = activeDraft!!.visibility,
                initialLocation = activeDraft!!.location,
                mode = ComposerMode.PUBLISH
            )
        }
    }


    // Delete confirmation dialog
    if (draftToDelete != null) {
        AlertDialog(
            onDismissRequest = { draftToDelete = null },
            title = { Text(stringResource(R.string.drafts_delete_confirm_title)) },
            text = { Text(stringResource(R.string.drafts_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        draftToDelete?.id?.let { draftControls.deleteDraft(it) }
                        draftToDelete = null
                    }, colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { draftToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            })
    }
}
