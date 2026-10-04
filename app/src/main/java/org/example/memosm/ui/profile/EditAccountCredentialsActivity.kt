package org.example.memosm.ui.profile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.example.memosm.R
import org.example.memosm.data.DataStoreManager
import org.example.memosm.ui.component.LocalNetworkPermission
import org.example.memosm.ui.component.LoginContent
import org.example.memosm.ui.component.ProfileBackButton
import org.example.memosm.ui.component.rememberLocalNetworkPermission
import org.example.memosm.ui.theme.ProfileTheme
import org.example.memosm.ui.theme.SavedMemosMTheme
import org.koin.android.ext.android.inject

/** Android owns Back so it can preview the account switcher underneath this page. */
class EditAccountCredentialsActivity : ComponentActivity() {
    private val dataStore: DataStoreManager by inject()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        if (accountId == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            SavedMemosMTheme(dataStore) {
                ProfileTheme {
                    val accounts by dataStore.accounts.collectAsStateWithLifecycle(initialValue = null)
                    val account = accounts?.firstOrNull { it.id == accountId }
                    val permission = rememberLocalNetworkPermission()

                    LaunchedEffect(accounts) {
                        if (accounts != null && account == null) finish()
                    }

                    CompositionLocalProvider(LocalNetworkPermission provides permission) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize().imePadding(),
                            topBar = {
                                TopAppBar(
                                    title = { Text(stringResource(R.string.profile_edit_credentials)) },
                                    navigationIcon = {
                                        ProfileBackButton(onClick = { onBackPressedDispatcher.onBackPressed() })
                                    }
                                )
                            }
                        ) { padding ->
                            Box(
                                modifier = Modifier.fillMaxSize().padding(padding),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                if (account == null) {
                                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                                } else {
                                    LoginContent(
                                        editAccount = account,
                                        showTitle = false,
                                        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(24.dp),
                                        onLoginSuccess = { baseUrl, token ->
                                            setResult(Activity.RESULT_OK, Intent()
                                                .putExtra(EXTRA_ACCOUNT_ID, accountId)
                                                .putExtra(EXTRA_HOST_URL, baseUrl)
                                                .putExtra(EXTRA_TOKEN, token))
                                            finish()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    data class VerifiedCredentials(val accountId: String, val hostUrl: String, val token: String)

    companion object {
        private const val EXTRA_ACCOUNT_ID = "account_id"
        private const val EXTRA_HOST_URL = "host_url"
        private const val EXTRA_TOKEN = "access_token"

        fun createIntent(context: Context, accountId: String): Intent =
            Intent(context, EditAccountCredentialsActivity::class.java).putExtra(EXTRA_ACCOUNT_ID, accountId)

        fun readResult(resultCode: Int, data: Intent?): VerifiedCredentials? {
            if (resultCode != Activity.RESULT_OK) return null
            return VerifiedCredentials(
                accountId = data?.getStringExtra(EXTRA_ACCOUNT_ID) ?: return null,
                hostUrl = data.getStringExtra(EXTRA_HOST_URL) ?: return null,
                token = data.getStringExtra(EXTRA_TOKEN) ?: return null
            )
        }
    }
}
