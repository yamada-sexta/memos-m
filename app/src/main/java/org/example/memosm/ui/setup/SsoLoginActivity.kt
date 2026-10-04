package org.example.memosm.ui.setup

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import org.example.memosm.R
import org.example.memosm.api.GsonProvider
import org.example.memosm.api.SsoException
import org.example.memosm.api.SsoFailure
import org.example.memosm.api.SsoTransaction
import org.example.memosm.data.DataStoreManager
import org.example.memosm.model.IdentityProvider
import org.example.memosm.ui.theme.SavedMemosMTheme
import org.koin.android.ext.android.inject

/** Internal activity owns the browser round trip and returns verified credentials to its caller. */
class SsoLoginActivity : ComponentActivity() {
    private val settings: DataStoreManager by inject()
    private lateinit var model: SsoLoginViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[SsoLoginViewModel::class.java]
        if (model.transaction == null && model.outcome.value == null && !model.exchanging) {
            if (savedInstanceState != null || intent.data != null) {
                model.fail(SsoFailure.EXPIRED)
            } else {
                try {
                    val provider = GsonProvider.gson.fromJson(intent.getStringExtra(PROVIDER), IdentityProvider::class.java)
                    model.begin(SsoTransaction.create(
                        intent.getStringExtra(BASE_URL).orEmpty(), provider, redirectUri(this)
                    ))
                } catch (error: SsoException) {
                    model.fail(error.failure)
                } catch (_: Exception) {
                    model.fail(SsoFailure.INVALID_PROVIDER)
                }
            }
        }
        setContent {
            SavedMemosMTheme(settings) {
                val outcome by model.outcome.collectAsState()
                LaunchedEffect(outcome) {
                    when (val result = outcome) {
                        is SsoOutcome.Success -> {
                            setResult(RESULT_OK, Intent().putExtra(BASE_URL, result.baseUrl).putExtra(TOKEN, result.token))
                            finish()
                        }
                        is SsoOutcome.Failure -> {
                            setResult(RESULT_CANCELED, Intent().putExtra(ERROR, result.reason.name))
                            finish()
                        }
                        SsoOutcome.Cancelled -> finish()
                        null -> Unit
                    }
                }
                BackHandler { model.cancel() }
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.login_sso_waiting))
                        TextButton(onClick = model::cancel) { Text(stringResource(R.string.common_cancel)) }
                    }
                }
            }
        }
        if (!model.browserLaunched && model.outcome.value == null) {
            model.transaction?.let { pending ->
                model.browserLaunched = true
                try {
                    CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(pending.authorizationUrl))
                } catch (_: ActivityNotFoundException) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.authorizationUrl)))
                    } catch (_: ActivityNotFoundException) {
                        model.fail(SsoFailure.BROWSER_UNAVAILABLE)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { model.callback(it.toString()) }
    }

    override fun onPause() {
        super.onPause()
        if (model.browserLaunched && !isChangingConfigurations) model.browserWasPaused = true
    }

    override fun onResume() {
        super.onResume()
        // Returning without a callback means the browser was closed or the user pressed Back.
        if (model.browserWasPaused && !model.exchanging && model.outcome.value == null) model.cancel()
    }

    companion object {
        private const val PROVIDER = "sso_provider"
        private const val BASE_URL = "sso_base_url"
        private const val TOKEN = "sso_token"
        private const val ERROR = "sso_error"

        fun redirectUri(context: Context): String = "${context.packageName}://oauth/callback"

        fun createIntent(context: Context, baseUrl: String, provider: IdentityProvider): Intent {
            // Older servers return the client secret; never copy it into the Android intent.
            val publicProvider = GsonProvider.gson.toJsonTree(provider).asJsonObject
            publicProvider.getAsJsonObject("config")?.getAsJsonObject("oauth2Config")?.remove("clientSecret")
            return Intent(context, SsoLoginActivity::class.java)
                .putExtra(BASE_URL, baseUrl)
                .putExtra(PROVIDER, publicProvider.toString())
        }

        data class Result(val baseUrl: String? = null, val token: String? = null, val error: SsoFailure? = null)

        fun readResult(resultCode: Int, intent: Intent?): Result {
            if (resultCode == Activity.RESULT_OK) {
                val baseUrl = intent?.getStringExtra(BASE_URL)
                val token = intent?.getStringExtra(TOKEN)
                return if (!baseUrl.isNullOrBlank() && !token.isNullOrBlank()) Result(baseUrl, token)
                    else Result(error = SsoFailure.TOKEN)
            }
            val error = intent?.getStringExtra(ERROR)?.let { value -> SsoFailure.entries.find { it.name == value } }
            return Result(error = error)
        }
    }
}

/** Only forwards the exact callback URI. Authentication always requires an active transaction. */
class SsoCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callback = intent.data
        if (callback != null && SsoTransaction.matchesRedirect(callback.toString(), SsoLoginActivity.redirectUri(this))) {
            startActivity(Intent(this, SsoLoginActivity::class.java)
                .setData(callback)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}
