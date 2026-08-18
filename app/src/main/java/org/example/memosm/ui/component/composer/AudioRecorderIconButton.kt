package org.example.memosm.ui.component.composer

import android.Manifest
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.example.memosm.R
import org.example.memosm.data.uriToBase64Attachment
import org.example.memosm.model.Attachment
import java.io.File

class AudioRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onRecordingFinished: (Uri, Attachment?) -> Unit,
) {
    var mediaRecorder by mutableStateOf<MediaRecorder?>(null)
    var isRecording by mutableStateOf(false)
    var currentRecordFile by mutableStateOf<File?>(null)

    fun startRecording() {
        try {
            val file = File(context.cacheDir, "record_${System.currentTimeMillis()}.aac")
            currentRecordFile = file

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            isRecording = true
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Failed to start recording", e)
            Toast.makeText(
                context,
                context.getString(R.string.common_operation_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false

            currentRecordFile?.let { file ->
                val uri = file.toUri()
                // Let the caller handle the attachment creation
                onRecordingFinished(uri, null)

                // Convert to base64 in background
                scope.launch {
                    val attachment = uriToBase64Attachment(uri, context)
                    if (attachment != null) {
                        onRecordingFinished(uri, attachment)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error stopping recording", e)
            Toast.makeText(
                context,
                context.getString(R.string.memo_composer_error_stop_recording),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun release() {
        if (isRecording) {
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
            } catch (_: Exception) {
            }
        }
    }
}

@Composable
fun rememberAudioRecorder(
    onRecordingFinished: (Uri, Attachment?) -> Unit
): Pair<AudioRecorder, ManagedActivityResultLauncher<String, Boolean>> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val recorder = remember {
        AudioRecorder(context, scope, onRecordingFinished)
    }

    val toastText = stringResource(R.string.memo_composer_error_microphone_permission)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            recorder.startRecording()
        } else {
            Toast.makeText(
                context,
                toastText,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.release()
        }
    }

    return recorder to permissionLauncher
}

@Composable
fun AudioRecorderIconButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: androidx.compose.ui.unit.Dp = 24.dp,
    onRecordingFinished: (Uri, Attachment?) -> Unit
) {
    val (recorder, permissionLauncher) = rememberAudioRecorder(onRecordingFinished)
    val isRecording = recorder.isRecording

    androidx.compose.material3.IconButton(
        onClick = {
            if (isRecording) {
                recorder.stopRecording()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        enabled = enabled,
        modifier = modifier
    ) {
        androidx.compose.material3.Icon(
            imageVector = if (isRecording) androidx.compose.material.icons.Icons.Default.Mic else androidx.compose.material.icons.Icons.Outlined.MicNone,
            contentDescription = androidx.compose.ui.res.stringResource(
                if (isRecording) R.string.memo_composer_stop_recording
                else R.string.memo_composer_start_recording
            ),
            tint = if (isRecording) androidx.compose.material3.MaterialTheme.colorScheme.error else androidx.compose.material3.LocalContentColor.current,
            modifier = Modifier.size(iconSize)
        )
    }
}
