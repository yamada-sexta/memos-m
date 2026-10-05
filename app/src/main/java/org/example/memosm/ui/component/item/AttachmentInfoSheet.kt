package org.example.memosm.ui.component.item

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.memosm.R
import org.example.memosm.model.Attachment
import org.example.memosm.ui.component.ActionSheet

internal data class AttachmentInfo(
    val filename: String,
    val type: String,
    val size: String?,
    val created: String?,
    val id: String?
)

@Composable
internal fun rememberAttachmentInfo(attachment: Attachment?, uri: Uri = Uri.EMPTY): AttachmentInfo {
    val context = LocalContext.current
    val unknownFilename = stringResource(R.string.attachments_unknown_filename)
    val type by produceState(attachment?.displayType.orEmpty(), uri, attachment?.displayType) {
        value = withContext(Dispatchers.IO) {
            if (uri != Uri.EMPTY) {
                context.contentResolver.getType(uri)
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
                    ).orEmpty()
            } else attachment?.displayType.orEmpty()
        }
    }
    return remember(attachment, uri, type, unknownFilename, context) {
        AttachmentInfo(
            filename = attachment?.filename ?: uri.lastPathSegment ?: unknownFilename,
            type = type,
            size = attachment?.size?.takeIf { it.isNotBlank() }?.let {
                it.toLongOrNull()?.let { bytes -> Formatter.formatFileSize(context, bytes) } ?: it
            },
            created = attachment?.createTime?.let {
                DateUtils.formatDateTime(
                    context, it.toEpochMilliseconds(),
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                        DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH
                )
            },
            id = attachment?.name?.takeIf { it.isNotBlank() }
        )
    }
}

@Composable
internal fun AttachmentInfoSheet(info: AttachmentInfo, onDismiss: () -> Unit) {
    ActionSheet(onDismissRequest = onDismiss) {
        AttachmentInfoContent(info)
    }
}

@Composable
internal fun AttachmentInfoContent(info: AttachmentInfo) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.attachments_info_title), style = MaterialTheme.typography.titleLarge)
        AttachmentInfoRow(stringResource(R.string.attachments_info_filename), info.filename)
        if (info.type.isNotBlank()) AttachmentInfoRow(stringResource(R.string.attachments_info_type), info.type)
        info.size?.let { AttachmentInfoRow(stringResource(R.string.attachments_info_size), it) }
        info.created?.let { AttachmentInfoRow(stringResource(R.string.attachments_info_created), it) }
        info.id?.let { AttachmentInfoRow(stringResource(R.string.attachments_info_id), it) }
    }
}

@Composable
fun AttachmentInfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
