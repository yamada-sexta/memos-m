package org.example.memosm.ui.setup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.ui.backup.BackupTransferActivity
import org.example.memosm.ui.theme.SavedMemosMTheme
import org.koin.android.ext.android.inject

/** Separate activities let Android preview the welcome screen during predictive Back. */
class SetupActivity : ComponentActivity() {
    private val settings: DataStoreManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val fromSettings = intent.getBooleanExtra(FROM_SETTINGS, false)
        setContent {
            SavedMemosMTheme(settings) {
                val ready by BackupCoordinator.startupReady.collectAsState()
                var childOpen by rememberSaveable { mutableStateOf(false) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    childOpen = false
                    when (it.resultCode) {
                        RESULT_OK -> {
                            setResult(RESULT_OK)
                            finish()
                        }
                        SetupLoginActivity.RESULT_CLOSE_SETUP -> finish()
                    }
                }
                if (!ready) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    SetupScreen(
                        onLogin = {
                            if (!childOpen) {
                                childOpen = true
                                launcher.launch(SetupLoginActivity.createIntent(this, fromSettings))
                            }
                        },
                        onImport = {
                            if (!childOpen) {
                                childOpen = true
                                launcher.launch(BackupTransferActivity.importIntent(this))
                            }
                        },
                        onClose = if (fromSettings) ::finish else null
                    )
                }
            }
        }
    }

    companion object {
        private const val FROM_SETTINGS = "from_settings"
        fun createIntent(context: Context, fromSettings: Boolean = false): Intent =
            Intent(context, SetupActivity::class.java).putExtra(FROM_SETTINGS, fromSettings)
    }
}
