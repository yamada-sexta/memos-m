package org.example.memosm.ui.component.item.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class FileThumbnailMode {
    WIDE, NORMAL, COMPACT
}

enum class FileType {
    PDF, IMAGE, VIDEO, AUDIO, DOCUMENT, SPREADSHEET, PRESENTATION, ARCHIVE, APK, GENERIC;

    companion object {
        fun identify(displayType: String, filename: String): FileType {
            val extension = filename.substringAfterLast(".", "").lowercase()
            val mime = displayType.lowercase()

            return when {
                // PDF
                mime.contains("pdf") || extension == "pdf" -> PDF

                // APK
                mime.contains("android.package-archive") || extension == "apk" -> APK

                // Images
                mime.startsWith("image/") || extension in listOf(
                    "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg"
                ) -> IMAGE

                // Videos
                mime.startsWith("video/") || extension in listOf(
                    "mp4", "mkv", "mov", "avi", "webm", "3gp"
                ) -> VIDEO

                // Audio
                mime.startsWith("audio/") || extension in listOf(
                    "mp3", "wav", "flac", "ogg", "m4a", "aac"
                ) -> AUDIO

                // Spreadsheets
                mime.contains("spreadsheet") || mime.contains("excel") || mime.contains("csv") || extension in listOf(
                    "xls",
                    "xlsx",
                    "csv",
                    "ods"
                ) -> SPREADSHEET

                // Presentations
                mime.contains("presentation") || mime.contains("powerpoint") || extension in listOf(
                    "ppt",
                    "pptx",
                    "odp"
                ) -> PRESENTATION

                // Archives
                mime.contains("zip") || mime.contains("archive") || mime.contains("compressed") || mime.contains(
                    "tar"
                ) || extension in listOf("zip", "rar", "7z", "tar", "gz") -> ARCHIVE

                // Documents
                mime.contains("text") || mime.contains("markdown") || mime.contains("word") || extension in listOf(
                    "txt",
                    "md",
                    "doc",
                    "docx",
                    "odt"
                ) -> DOCUMENT

                else -> GENERIC
            }
        }
    }

    val icon: ImageVector
        get() = when (this) {
            PDF -> Icons.Outlined.PictureAsPdf
            IMAGE -> Icons.Outlined.Image
            VIDEO -> Icons.Outlined.VideoFile
            AUDIO -> Icons.Outlined.AudioFile
            SPREADSHEET -> Icons.Outlined.TableChart
            PRESENTATION -> Icons.Outlined.Slideshow
            DOCUMENT -> Icons.Outlined.Description
            ARCHIVE -> Icons.Outlined.FolderZip
            APK -> Icons.Outlined.Android // Note: Requires material-icons-extended
            GENERIC -> Icons.AutoMirrored.Outlined.InsertDriveFile
        }
}

@Composable
fun FileThumbnail(
    displayType: String,
    filename: String,
    mode: FileThumbnailMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    details: String? = null
) {
    // Better identification by looking at both the type and the name
    val fileType = remember(displayType, filename) {
        FileType.identify(displayType, filename)
    }

    when (mode) {
        FileThumbnailMode.WIDE -> {
            Row(
                modifier = modifier
                    .fillMaxSize()
                    .clickable { onClick() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                Icon(
                    imageVector = fileType.icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = filename,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        else -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .clickable { onClick() }
                    .padding(if (details != null) 8.dp else 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(
                    imageVector = fileType.icon,
                    contentDescription = null,
                    modifier = Modifier.size(if (details != null) 24.dp else 32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                if (mode == FileThumbnailMode.NORMAL) {
                    Spacer(modifier = Modifier.height(if (details != null) 4.dp else 8.dp))
                    Text(
                        text = filename,
                        style = if (details != null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
                details?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
