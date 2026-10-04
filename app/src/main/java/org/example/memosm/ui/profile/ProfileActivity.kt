package org.example.memosm.ui.profile

import android.os.Bundle
import android.content.Intent
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.example.memosm.data.DataStoreManager
import org.example.memosm.ui.component.ArchivedMemosScreen
import org.example.memosm.ui.component.ConflictDialog
import org.example.memosm.ui.component.LocalNetworkPermission
import org.example.memosm.ui.component.NotificationsScreen
import org.example.memosm.ui.component.item.markdown.LinkPreviewEnvironment
import org.example.memosm.ui.component.item.markdown.LocalLinkPreviews
import org.example.memosm.ui.component.item.media.AccountMediaIdentity
import org.example.memosm.ui.component.item.media.LocalAccountMediaIdentity
import org.example.memosm.ui.component.rememberLocalNetworkPermission
import org.example.memosm.ui.nav.SettingsScreen
import org.example.memosm.ui.nav.SettingsSection
import org.example.memosm.ui.theme.SavedMemosMTheme
import org.example.memosm.ui.theme.ProfileTheme
import org.example.memosm.viewmodel.MemosViewModel
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/** Each destination owns its UI lifetime; persisted repositories carry changes between activities. */
abstract class ProfileActivity : ComponentActivity() {
    private val dataStore: DataStoreManager by inject()
    private val viewModel: MemosViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SavedMemosMTheme(dataStore) {
                ProfileTheme {
                    val permission = rememberLocalNetworkPermission()
                    val accounts by dataStore.accounts.collectAsStateWithLifecycle(initialValue = null)
                    val active = accounts?.firstOrNull { it.isActive }
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    val sessionReady by viewModel.sessionReady.collectAsStateWithLifecycle()
                    val previews by viewModel.linkPreviews.collectAsStateWithLifecycle()
                    val previewEnvironment = remember(previews, uiState.appSettings.linkPreviewEnabled, uiState.isOnline) {
                        previews?.takeIf { uiState.appSettings.linkPreviewEnabled }
                            ?.let { LinkPreviewEnvironment(it, uiState.isOnline) }
                    }

                    LaunchedEffect(active?.id, active?.hostUrl, active?.accessToken) {
                        if (accounts != null) {
                            if (active == null) finish()
                            else {
                                permission.ensureAccess(active.hostUrl)
                                viewModel.userDelegate.updateCurrentAccountInList()
                            }
                        }
                    }
                    var previouslyGranted by remember { mutableStateOf(permission.granted) }
                    LaunchedEffect(permission.granted) {
                        if (permission.granted && !previouslyGranted) viewModel.retryAccountConnection()
                        previouslyGranted = permission.granted
                    }
                    val boundAccount = uiState.accounts.firstOrNull { it.isActive }
                    val accountMatches = active != null && boundAccount?.id == active.id &&
                        uiState.session.hostUrl == active.hostUrl && uiState.session.token == active.accessToken

                    CompositionLocalProvider(
                        LocalNetworkPermission provides permission,
                        LocalAccountMediaIdentity provides active?.let {
                            AccountMediaIdentity(it.id, uiState.accountGeneration)
                        },
                        LocalLinkPreviews provides previewEnvironment
                    ) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            if (!sessionReady || !accountMatches) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                key(uiState.accountGeneration) {
                                    Destination(viewModel) { onBackPressedDispatcher.onBackPressed() }
                                    uiState.conflict?.let { conflict ->
                                        ConflictDialog(
                                            conflict = conflict,
                                            onResolve = { resolution, merged -> viewModel.resolveConflict(resolution, merged) },
                                            onDismiss = viewModel::dismissConflict
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onForeground()
    }

    @Composable
    protected abstract fun Destination(viewModel: MemosViewModel, onBack: () -> Unit)
}

class NotificationsActivity : ProfileActivity() {
    @Composable
    override fun Destination(viewModel: MemosViewModel, onBack: () -> Unit) {
        NotificationsScreen(viewModel = viewModel, onBack = onBack)
    }
}

class ArchivedMemosActivity : ProfileActivity() {
    @Composable
    override fun Destination(viewModel: MemosViewModel, onBack: () -> Unit) {
        ArchivedMemosScreen(viewModel = viewModel, onBack = onBack)
    }
}

class SettingsActivity : ProfileActivity() {
    @Composable
    override fun Destination(viewModel: MemosViewModel, onBack: () -> Unit) {
        val section = SettingsSection.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SECTION) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshAfterProfileDetails()
        }
        SettingsScreen(
            viewModel = viewModel,
            onBack = onBack,
            section = section,
            onOpenLicenses = {
                launcher.launch(Intent(this, OpenSourceLicensesActivity::class.java))
            },
            onOpenSection = { destination ->
                launcher.launch(createIntent(this, destination))
            }
        )
    }

    companion object {
        private const val EXTRA_SECTION = "settings_section"

        fun createIntent(context: Context, section: SettingsSection): Intent {
            val activity = if (section == SettingsSection.AUDIT) SyncLogActivity::class.java
                else SettingsActivity::class.java
            return Intent(context, activity).putExtra(EXTRA_SECTION, section.name)
        }
    }
}

class OpenSourceLicensesActivity : ProfileActivity() {
    @Composable
    override fun Destination(viewModel: MemosViewModel, onBack: () -> Unit) {
        org.example.memosm.ui.nav.OpenSourceLicensesScreen(onBack)
    }
}

class SyncLogActivity : ProfileActivity() {
    @Composable
    override fun Destination(viewModel: MemosViewModel, onBack: () -> Unit) {
        org.example.memosm.ui.nav.SyncLogScreen(onBack)
    }
}
