package org.example.memosm.ui.component.composer

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.memosm.R
import org.example.memosm.data.base64ToTempUri
import org.example.memosm.data.uriToBase64Attachment
import org.example.memosm.model.Attachment
import org.example.memosm.model.Location
import org.example.memosm.model.Visibility
import org.example.memosm.ui.findActivity
import org.example.memosm.ui.getFileSize


/**
 * Mode for the memo composer, determines the submit button label.
 * All callers MUST specify an explicit mode.
 */
enum class ComposerMode {
    /** Creating a new memo or publishing a draft */
    PUBLISH,

    /** Editing an existing memo */
    UPDATE,

    /** Adding a comment to a memo */
    COMMENT
}

@Composable
fun MemoComposer(
    modifier: Modifier = Modifier,
    onPublish: (String, Visibility, List<Attachment>, Location?) -> Unit,
    onUploadFile: suspend (Uri, Context) -> Attachment?,
    availableTags: Map<String, Int>,
    token: String,
    hostUrl: String,
    mode: ComposerMode,
    isPosting: Boolean = false,
    initialContent: String = "",
    initialVisibility: Visibility = Visibility.PRIVATE,
    initialAttachments: List<Attachment> = emptyList(),
    initialUris: List<Uri> = emptyList(),
    initialLocation: Location? = null,
    onDraftChanged: ((String, Visibility, List<Attachment>, Location?) -> Unit)? = null
) {
    // Depending on the Composer Mode there will be different placeholder
    val placeholder = when (mode) {
        ComposerMode.PUBLISH -> stringResource(R.string.memo_composer_placeholder)
        ComposerMode.UPDATE -> stringResource(R.string.memo_composer_placeholder)
        ComposerMode.COMMENT -> stringResource(R.string.memo_detail_comment_placeholder)
    }
    val context = LocalContext.current
    val fileTooLargeMessage = stringResource(R.string.memo_composer_error_file_too_large)

    // Changed to TextFieldValue for VisualTransformation support
    var contentState by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                initialContent
            )
        )
    }
    var visibility by remember { mutableStateOf(initialVisibility) }
    var location by remember { mutableStateOf(initialLocation) }

    val draftAttachmentsState = remember {
        // Combine existing attachments (from editing) with new URIs (from share intent)
        val fromAttachments: List<Pair<Uri, Attachment?>> =
            initialAttachments.map { Uri.EMPTY to (it as Attachment?) }


        val fromUris: List<Pair<Uri, Attachment?>> = initialUris.map { it to null }
        mutableStateOf(fromAttachments + fromUris)
    }

    var draftAttachments by draftAttachmentsState

    var uploadingUris by remember { mutableStateOf(setOf<Uri>()) }
    var isUploadingCount by remember { mutableIntStateOf(0) }
    var showLocationEditDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Convert initialUris (from share intent) to base64 attachments in background
    // This mirrors the picker flow so they persist when the draft is saved
    LaunchedEffect(Unit) {
        initialUris.forEach { uri ->
            scope.launch {
                val attachment = uriToBase64Attachment(uri, context)
                if (attachment != null) {
                    draftAttachments = draftAttachments.map { (u, a) ->
                        if (u == uri && a == null) uri to attachment else u to a
                    }
                }
            }
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            // Add URIs immediately for display, then convert to base64 in background
            val newUris = uris.map { it to null as Attachment? }
            draftAttachments = draftAttachments + newUris

            // Convert to base64 in background for each URI
            uris.forEach { uri ->
                scope.launch {
                    val attachment = uriToBase64Attachment(uri, context)
                    if (attachment != null) {
                        // Update the draft attachments with the converted attachment
                        draftAttachments = draftAttachments.map { (u, a) ->
                            if (u == uri && a == null) uri to attachment else u to a
                        }
                    }
                }
            }
        }
    }

    // TRIGGER CACHE UPDATE when local changes occur
    // Debounced to avoid blocking UI on every keystroke
    // Only saves attachments that have already been converted to base64 (non-null)
    LaunchedEffect(contentState.text, visibility, draftAttachments, location) {
        if (onDraftChanged == null) return@LaunchedEffect

        // Debounce: wait 500ms before saving draft
        delay(500)

        // Only save attachments that have been converted (non-null Attachment)
        // Attachments still being converted in background will be saved on next trigger
        val convertedAttachments = draftAttachments.mapNotNull { (_, attachment) -> attachment }

        onDraftChanged.invoke(
            contentState.text, visibility, convertedAttachments, location
        )
    }

    // Drag and Drop state
    var isDragging by remember { mutableStateOf(false) }
    val dndTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                isDragging = false
                val dragEvent = event.toAndroidDragEvent()

                // Request drag and drop permissions for cross-app access
                context.findActivity()?.requestDragAndDropPermissions(dragEvent)

                val clipData = dragEvent.clipData
                if (clipData != null) {
                    val uris = mutableListOf<Uri>()
                    for (i in 0 until clipData.itemCount) {
                        clipData.getItemAt(i).uri?.let { uris.add(it) }
                    }
                    if (uris.isNotEmpty()) {
                        scope.launch {
                            val validUris = uris.filter { uri ->
                                val size = getFileSize(context, uri)
                                if (size > 10 * 1024 * 1024) {
                                    Toast.makeText(
                                        context, fileTooLargeMessage, Toast.LENGTH_SHORT
                                    ).show()
                                    false
                                } else {
                                    true
                                }
                            }

                            val newAttachments = validUris.map { uri ->
                                val base64Attachment = uriToBase64Attachment(uri, context)
                                uri to base64Attachment
                            }
                            draftAttachments = draftAttachments + newAttachments
                        }
                        return true
                    }
                }
                return false
            }

            override fun onEntered(event: DragAndDropEvent) {
                isDragging = true
            }

            override fun onExited(event: DragAndDropEvent) {
                isDragging = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                isDragging = false
            }
        }
    }

    var componentWidth by remember { mutableStateOf(0.dp) }

    Column(
        modifier = modifier
            .onSizeChanged {
                componentWidth = with(density) { it.width.toDp() }
            }
            .dragAndDropTarget(
                shouldStartDragAndDrop = { true }, target = dndTarget
            )
            .background(
                if (isDragging) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent, RoundedCornerShape(8.dp)
            )
    ) {
        MemoInput(
            contentState = contentState,
            onContentChange = { contentState = it },
            placeholder = placeholder,
            availableTags = availableTags,
            enabled = !isPosting,
            autoFocus = true,
            modifier = Modifier.weight(1f),
        )

        MemoComposerBottomBar(
            modifier = Modifier,
            draftAttachments = draftAttachments,
            uploadingUris = uploadingUris,
            location = location,
            isPosting = isPosting,
            visibility = visibility,
            mode = mode,
            componentWidth = componentWidth,
            pickerLauncher = pickerLauncher,
            token = token,
            hostUrl = hostUrl,
            contentStateText = contentState.text,
            isUploadingCount = isUploadingCount,
            onRemoveLocation = { location = null },
            onLocationClick = { showLocationEditDialog = true },
            onVisibilityChange = { visibility = it },
            onRemoveAttachment = { index ->
                if (index in draftAttachments.indices) {
                    val updated = draftAttachments.toMutableList().apply { removeAt(index) }
                    draftAttachments = updated
                }
            },
            onRecordingFinished = { uri, attachment ->
                // Add immediately for display if attachment is null (just Uri)
                // Or update if attachment is ready
                draftAttachments = if (attachment == null) {
                    draftAttachments + (uri to null)
                } else {
                    draftAttachments.map { (u, a) ->
                        if (u == uri && a == null) uri to attachment else u to a
                    }
                }
            },
            onLocationFounded = { loc ->
                location = loc
            },
            onPublishClick = {
                scope.launch {
                    val pendingUploads = draftAttachments.withIndex().filter { indexedValue ->
                        val pair = indexedValue.value
                        // Case 1: New local file (Uri is not EMPTY, Attachment is null)
                        (pair.second == null && pair.first != Uri.EMPTY) ||
                                // Case 2: Restored draft (Uri is EMPTY, Attachment has content but no name on server)
                                (pair.second != null && pair.second!!.name == null && pair.second!!.content != null)
                    }
                    val uploadedAttachments = mutableListOf<Attachment>()

                    for (indexedValue in pendingUploads) {
                        val index = indexedValue.index
                        val (uri, attachment) = indexedValue.value
                        isUploadingCount++

                        // If it's a restored draft, we first need to convert it to a temp Uri
                        val uploadUri = if (uri == Uri.EMPTY && attachment != null) {
                            base64ToTempUri(
                                attachment.content ?: "",
                                attachment.filename,
                                attachment.type,
                                context
                            )
                        } else {
                            uri
                        }

                        if (uploadUri != null) {
                            uploadingUris = uploadingUris + uploadUri
                            val uploaded = onUploadFile(uploadUri, context)
                            uploadingUris = uploadingUris - uploadUri

                            if (uploaded != null) {
                                uploadedAttachments.add(uploaded)
                                // Update local draft state immediately as we upload
                                // We match by the index to replace it safely
                                draftAttachments = draftAttachments.mapIndexed { i, item ->
                                    if (i == index) uploadUri to uploaded else item
                                }
                            }
                        }
                        isUploadingCount--
                    }

                    // Now collect all valid attachments:
                    // 1. Items that were already valid (non-null attachment with name)
                    // 2. Newly uploaded items are already swapped into draftAttachments by the loop above,
                    //    OR added to uploadedAttachments if we want to be safe, but the loop updates draftAttachments.
                    // Let's just grab everything from draftAttachments that has a valid server-side attachment (name != null)
                    val finalAttachments =
                        draftAttachments.mapNotNull { it.second }.filter { it.name != null }

                    onPublish(
                        contentState.text, visibility, finalAttachments, location
                    )
                }
            })
    }

    if (showLocationEditDialog && location != null) {
        var tempPlaceholder by remember { mutableStateOf(location?.placeholder ?: "") }
        var tempLatitude by remember { mutableStateOf(location?.latitude?.toString() ?: "") }
        var tempLongitude by remember { mutableStateOf(location?.longitude?.toString() ?: "") }

        AlertDialog(
            onDismissRequest = { showLocationEditDialog = false },
            title = { Text(stringResource(R.string.memo_composer_edit_location)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = tempPlaceholder,
                        onValueChange = { tempPlaceholder = it },
                        label = { Text(stringResource(R.string.memo_composer_location_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tempLatitude,
                        onValueChange = { tempLatitude = it },
                        label = { Text(stringResource(R.string.memo_composer_location_latitude)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tempLongitude,
                        onValueChange = { tempLongitude = it },
                        label = { Text(stringResource(R.string.memo_composer_location_longitude)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    location = location?.copy(
                        placeholder = tempPlaceholder,
                        latitude = tempLatitude.toDoubleOrNull(),
                        longitude = tempLongitude.toDoubleOrNull()
                    )
                    showLocationEditDialog = false
                }) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocationEditDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            })
    }
}
