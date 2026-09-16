package org.example.memosm.widget.stats

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.example.memosm.data.DataStoreManager
import org.example.memosm.R
import androidx.compose.ui.res.stringResource
import org.example.memosm.model.Account
import org.example.memosm.ui.component.resolveResourceUrl
import org.koin.android.ext.android.inject

class UserStatsWidgetConfigActivity : ComponentActivity() {

    private val dataStoreManager: DataStoreManager by inject()
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Find the widget ID from the intent.
        val intent = intent
        val extras = intent.extras
        if (extras != null) {
            appWidgetId = extras.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
            )
        }

        // If this activity was started with an intent without an app widget ID, finish with an error.
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text(stringResource(R.string.widget_stats_select_account)) })
                    }) { padding ->
                    val accounts = produceState<List<Account>>(initialValue = emptyList()) {
                        value = dataStoreManager.getAccounts()
                    }

                    LazyColumn(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize()
                    ) {
                        items(accounts.value) { account ->
                            AccountItem(account = account, onClick = {
                                selectAccount(account)
                            })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    private fun selectAccount(account: Account) {
        lifecycleScope.launch {
            val context = this@UserStatsWidgetConfigActivity

            // Save the account ID to the widget state
            val widget = UserStatsWidget()
            val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)

            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[stringPreferencesKey("account_id")] = account.id
            }
            widget.update(context, glanceId)

            val resultValue = Intent()
            resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, resultValue)
            finish()
        }
    }
}

@Composable
fun AccountItem(account: Account, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val avatarUrl =
            resolveResourceUrl(account.hostUrl, account.avatarUrl ?: account.user?.avatarUrl)

        AsyncImage(
            model = avatarUrl,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(
                text = account.displayName ?: account.name ?: stringResource(R.string.memo_unknown_user),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = account.hostUrl,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
