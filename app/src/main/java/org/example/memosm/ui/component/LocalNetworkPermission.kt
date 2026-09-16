package org.example.memosm.ui.component

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CompletableDeferred
import org.example.memosm.R
import org.example.memosm.network.hasLocalNetworkPermission
import org.example.memosm.network.serverNeedsLocalNetworkPermission
import org.example.memosm.widget.stats.UserStatsWidget

val LocalNetworkPermission = staticCompositionLocalOf<LocalNetworkPermissionState> {
    error("Local network permission host is missing")
}

class LocalNetworkPermissionState(
    private val checkPermission: () -> Boolean,
    private val openAppSettings: () -> Unit,
    private val needsPermission: suspend (String) -> Boolean
) {
    var granted by mutableStateOf(checkPermission())
        private set
    var showDeniedDialog by mutableStateOf(false)
    internal var launchRequest: () -> Unit = {}
    private var pending: CompletableDeferred<Boolean>? = null

    fun refresh() {
        granted = checkPermission()
        if (granted) showDeniedDialog = false
    }

    /** Called before login, restoring a saved account, or switching servers. */
    suspend fun ensureAccess(serverUrl: String): Boolean {
        refresh()
        if (granted || !needsPermission(serverUrl)) return true
        return requestAccess()
    }

    suspend fun requestAccess(): Boolean {
        refresh()
        if (granted) return true
        pending?.let { return it.await() }
        val result = CompletableDeferred<Boolean>()
        pending = result
        try {
            launchRequest()
            return result.await()
        } finally {
            if (pending === result) pending = null
        }
    }

    internal fun onResult(allowed: Boolean) {
        refresh()
        showDeniedDialog = !allowed
        pending?.complete(allowed)
    }

    internal fun dispose() {
        pending?.cancel()
        pending = null
    }

    fun openSettings() {
        showDeniedDialog = false
        openAppSettings()
    }
}

@Composable
fun rememberLocalNetworkPermission(): LocalNetworkPermissionState {
    val context = LocalContext.current
    val state = remember(context) {
        LocalNetworkPermissionState(
            checkPermission = { hasLocalNetworkPermission(context) },
            openAppSettings = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                })
            },
            needsPermission = { serverNeedsLocalNetworkPermission(context, it) }
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        state.onResult(it)
    }
    SideEffect {
        state.launchRequest = {
            if (Build.VERSION.SDK_INT >= 37) launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            else state.onResult(true)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.refresh()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.dispose()
        }
    }
    var previouslyGranted by remember { mutableStateOf(state.granted) }
    LaunchedEffect(state.granted) {
        if (state.granted && !previouslyGranted) UserStatsWidget().updateAll(context)
        previouslyGranted = state.granted
    }
    if (state.showDeniedDialog) {
        AlertDialog(
            onDismissRequest = { state.showDeniedDialog = false },
            title = { Text(stringResource(R.string.local_network_permission_title)) },
            text = { Text(stringResource(R.string.local_network_permission_denied)) },
            confirmButton = {
                TextButton(onClick = state::openSettings) {
                    Text(stringResource(R.string.local_network_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { state.showDeniedDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
    return state
}
