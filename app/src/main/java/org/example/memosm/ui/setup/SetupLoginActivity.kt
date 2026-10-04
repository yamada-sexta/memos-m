package org.example.memosm.ui.setup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.example.memosm.R
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.ui.component.LocalNetworkPermission
import org.example.memosm.ui.component.LoginContent
import org.example.memosm.ui.component.rememberLocalNetworkPermission
import org.example.memosm.ui.theme.SavedMemosMTheme
import org.koin.android.ext.android.inject

class SetupLoginActivity : ComponentActivity() {
    private val settings: DataStoreManager by inject()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val fromSettings = intent.getBooleanExtra(FROM_SETTINGS, false)
        setContent {
            SavedMemosMTheme(settings) {
                val ready by BackupCoordinator.startupReady.collectAsState()
                val permission = rememberLocalNetworkPermission()
                val scope = rememberCoroutineScope()
                val snackbar = remember { SnackbarHostState() }
                val saveError = stringResource(R.string.login_error_failed)
                var saving by remember { mutableStateOf(false) }
                BackHandler(enabled = saving) {}
                CompositionLocalProvider(LocalNetworkPermission provides permission) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {},
                                navigationIcon = {
                                    IconButton(enabled = !saving, onClick = { onBackPressedDispatcher.onBackPressed() }) {
                                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.backup_back))
                                    }
                                },
                                actions = {
                                    if (fromSettings) IconButton(enabled = !saving, onClick = {
                                        setResult(RESULT_CLOSE_SETUP)
                                        finish()
                                    }) {
                                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_close))
                                    }
                                }
                            )
                        },
                        snackbarHost = { SnackbarHost(snackbar) }
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                            if (!ready || saving) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            } else {
                                LoginContent(
                                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(24.dp),
                                    onLoginSuccess = { baseUrl, token ->
                                        scope.launch {
                                            saving = true
                                            try {
                                                settings.addAccount(baseUrl, token)
                                                setResult(RESULT_OK)
                                                finish()
                                            } catch (error: CancellationException) {
                                                throw error
                                            } catch (_: Exception) {
                                                saving = false
                                                snackbar.showSnackbar(saveError)
                                            } finally {
                                                saving = false
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val FROM_SETTINGS = "from_settings"
        const val RESULT_CLOSE_SETUP = RESULT_FIRST_USER
        fun createIntent(context: Context, fromSettings: Boolean = false): Intent =
            Intent(context, SetupLoginActivity::class.java).putExtra(FROM_SETTINGS, fromSettings)
    }
}
