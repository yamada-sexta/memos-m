package org.example.memosm.ui.component

import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.example.memosm.R
import org.example.memosm.api.MemosApiFactory
import org.example.memosm.api.SsoFailure
import org.example.memosm.api.SsoTransaction
import org.example.memosm.api.loginAndCreateToken
import org.example.memosm.api.normalizeLoginServerUrl
import org.example.memosm.model.Account
import org.example.memosm.model.IdentityProvider
import org.example.memosm.ui.setup.SsoLoginActivity

enum class LoginMode {
    PASSWORD, TOKEN, SSO
}

@Composable
fun LoginScreen(
    onLoginSuccess: (String, String) -> Unit, modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            LoginContent(
                onLoginSuccess = onLoginSuccess,
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 64.dp, bottom = 24.dp)
            )
            val backupContext = androidx.compose.ui.platform.LocalContext.current
            TextButton(onClick = { backupContext.startActivity(org.example.memosm.ui.backup.BackupTransferActivity.importIntent(backupContext)) }) {
                Text(stringResource(R.string.backup_restore_before_login))
            }
            }
        }
    }
}

/**
 * Login dialog that can also be used for editing existing account credentials.
 *
 * @param onLoginSuccess Callback with (baseUrl, token) on successful login/save
 * @param onDismiss Callback when dialog is dismissed
 * @param editAccount Optional - if provided, the dialog is in "edit mode" with pre-filled values
 */
@Composable
fun LoginDialog(
    onLoginSuccess: (String, String) -> Unit,
    onDismiss: () -> Unit,
    editAccount: Account? = null
) {
    Dialog(
        onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .padding(16.dp)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Box {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_close))
                    }

                    LoginContent(
                        onLoginSuccess = onLoginSuccess,
                        modifier = Modifier.padding(24.dp),
                        editAccount = editAccount
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginContent(
    onLoginSuccess: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    editAccount: Account? = null,
    showTitle: Boolean = true
) {
    // If editing, default to token mode and pre-fill values
    val isEditMode = editAccount != null
    var loginMode by rememberSaveable(editAccount?.id) { mutableStateOf(if (isEditMode) LoginMode.TOKEN else LoginMode.PASSWORD) }
    var hostUrl by rememberSaveable(editAccount?.id) { mutableStateOf(editAccount?.hostUrl ?: "") }
    var token by rememberSaveable(editAccount?.id) { mutableStateOf(editAccount?.accessToken ?: "") }
    var username by rememberSaveable(editAccount?.id) { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var ssoInProgress by rememberSaveable { mutableStateOf(false) }
    var providers by remember(hostUrl) { mutableStateOf<List<IdentityProvider>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val networkPermission = LocalNetworkPermission.current
    val localNetworkDenied = stringResource(R.string.local_network_permission_denied)

    val errorEmptyHost = stringResource(R.string.login_error_empty_host)
    val errorFailed = stringResource(R.string.login_error_failed)
    val errorInvalidUrl = stringResource(R.string.login_error_invalid_url)
    val errorInvalidInstance = stringResource(R.string.login_error_invalid_instance)
    val errorEmptyToken = stringResource(R.string.login_error_empty_token)
    val errorVerificationFailed = stringResource(R.string.login_error_verification_failed)
    val errorEmptyCredentials = stringResource(R.string.login_error_empty_credentials)
    val errorInvalidCredentials = stringResource(R.string.login_error_invalid_credentials)

    val context = LocalContext.current
    val ssoErrorMessages = mapOf(
        SsoFailure.INVALID_PROVIDER to stringResource(R.string.login_sso_invalid_provider),
        SsoFailure.INVALID_CALLBACK to stringResource(R.string.login_sso_invalid_callback),
        SsoFailure.EXPIRED to stringResource(R.string.login_sso_expired),
        SsoFailure.DENIED to stringResource(R.string.login_sso_denied),
        SsoFailure.BROWSER_UNAVAILABLE to stringResource(R.string.login_sso_no_browser),
        SsoFailure.SIGN_IN to stringResource(R.string.login_sso_failed),
        SsoFailure.TOKEN to stringResource(R.string.login_sso_token_failed)
    )
    val discoveryFailed = stringResource(R.string.login_sso_discovery_failed)
    val ssoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        ssoInProgress = false
        val credentials = SsoLoginActivity.readResult(result.resultCode, result.data)
        if (credentials.baseUrl != null && credentials.token != null) {
            onLoginSuccess(credentials.baseUrl, credentials.token)
        } else {
            errorMessage = credentials.error?.let { ssoErrorMessages[it] }
        }
    }
    val busy = isLoading || ssoInProgress

    val findProviders = {
        scope.launch {
            if (isLoading || ssoInProgress) return@launch
            errorMessage = null
            providers = null
            val baseUrl = normalizeLoginServerUrl(hostUrl)
            if (baseUrl == null) {
                errorMessage = if (hostUrl.isBlank()) errorEmptyHost else errorInvalidUrl
            } else {
                isLoading = true
                try {
                    if (!networkPermission.ensureAccess(baseUrl)) {
                        errorMessage = localNetworkDenied
                    } else {
                        val api = MemosApiFactory.create(baseUrl, OkHttpClient())
                        api.getInstanceProfile()
                        providers = api.listIdentityProviders().identityProviders.orEmpty().filter { provider ->
                            try {
                                SsoTransaction.create(baseUrl, provider, SsoLoginActivity.redirectUri(context))
                                true
                            } catch (_: Exception) { false }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    errorMessage = discoveryFailed
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val performLogin = {
        scope.launch {
            if (isLoading || ssoInProgress) return@launch
            isLoading = true
            errorMessage = null
            val baseUrl = normalizeLoginServerUrl(hostUrl)
            if (baseUrl == null) {
                errorMessage = if (hostUrl.isBlank()) errorEmptyHost else errorInvalidUrl
                isLoading = false
                return@launch
            }

            try {
                if (!networkPermission.ensureAccess(baseUrl)) {
                    errorMessage = localNetworkDenied
                    return@launch
                }
                val logging = HttpLoggingInterceptor { message ->
                    Log.d("MemosApi", message)
                }.apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }

                val client = OkHttpClient.Builder()
                    .addInterceptor(logging)
                    .build()

                val api = MemosApiFactory.create(baseUrl, client)

                // Check if instance is valid by fetching instance profile
                try {
                    api.getInstanceProfile()
                } catch (e: Exception) {
                    errorMessage = errorInvalidInstance
                    isLoading = false
                    return@launch
                }

                if (loginMode == LoginMode.TOKEN) {
                    val trimmedToken = token.trim()
                    if (trimmedToken.isBlank()) {
                        errorMessage = errorEmptyToken
                        isLoading = false
                        return@launch
                    }

                    // Verify token validity by fetching current user
                    val authClient =
                        OkHttpClient.Builder().addInterceptor(logging).addInterceptor { chain ->
                            val request = chain.request().newBuilder()
                                .addHeader("Authorization", "Bearer $trimmedToken").build()
                            chain.proceed(request)
                        }.build()

                    val authApi = MemosApiFactory.create(baseUrl, authClient)

                    try {
                        authApi.getCurrentSession()
                        onLoginSuccess(baseUrl, trimmedToken)
                    } catch (e: Exception) {
                        Log.e("MemosLogin", "Token verification failed", e)
                        errorMessage = errorVerificationFailed
                    }
                } else {
                    if (username.isBlank() || password.isBlank()) {
                        errorMessage = errorEmptyCredentials
                        isLoading = false
                        return@launch
                    }

                    try {
                        val token = loginAndCreateToken(
                            api, baseUrl, username.trim(), password
                        )
                        onLoginSuccess(baseUrl, token)

                    } catch (e: Exception) {
                        Log.e("MemosLogin", "Login failed", e)
                        errorMessage = errorInvalidCredentials
                    }
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MemosLogin", "Login failed", e)
                errorMessage = errorFailed
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        if (showTitle) Text(
            text = if (isEditMode) stringResource(R.string.profile_edit_credentials) else stringResource(
                R.string.login_title
            ),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        if (Build.VERSION.SDK_INT >= 37 && !networkPermission.granted) {
            TextButton(onClick = { scope.launch { networkPermission.requestAccess() } }) {
                Text(stringResource(R.string.local_network_permission_title))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
        ) {
            ToggleButton(
                checked = loginMode == LoginMode.PASSWORD,
                onCheckedChange = { loginMode = LoginMode.PASSWORD; errorMessage = null },
                modifier = Modifier.weight(1f).semantics {
                    role = Role.RadioButton
                    selected = loginMode == LoginMode.PASSWORD
                },
                enabled = !busy,
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes()
            ) {
                Text(stringResource(R.string.login_password))
            }
            ToggleButton(
                checked = loginMode == LoginMode.TOKEN,
                onCheckedChange = { loginMode = LoginMode.TOKEN; errorMessage = null },
                modifier = Modifier.weight(1f).semantics {
                    role = Role.RadioButton
                    selected = loginMode == LoginMode.TOKEN
                },
                enabled = !busy,
                shapes = ButtonGroupDefaults.connectedMiddleButtonShapes()
            ) {
                Text(stringResource(R.string.login_token))
            }
            ToggleButton(
                checked = loginMode == LoginMode.SSO,
                onCheckedChange = { loginMode = LoginMode.SSO; errorMessage = null },
                modifier = Modifier.weight(1f).semantics {
                    role = Role.RadioButton
                    selected = loginMode == LoginMode.SSO
                },
                enabled = !busy,
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes()
            ) {
                Text(stringResource(R.string.login_sso))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = hostUrl,
            onValueChange = { hostUrl = it; errorMessage = null },
            label = { Text(stringResource(R.string.login_host_url)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.login_host_url_placeholder)) },
            enabled = !busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )

        Spacer(modifier = Modifier.height(8.dp))

        when (loginMode) {
            LoginMode.SSO -> {
                Text(stringResource(R.string.login_sso_description), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                providers?.let { available ->
                    if (available.isEmpty()) {
                        Text(stringResource(R.string.login_sso_no_providers))
                    }
                    available.forEach { provider ->
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    if (isLoading || ssoInProgress) return@launch
                                    errorMessage = null
                                    val baseUrl = normalizeLoginServerUrl(hostUrl)
                                    if (baseUrl == null) {
                                        errorMessage = errorInvalidUrl
                                        return@launch
                                    }
                                    isLoading = true
                                    try {
                                        if (!networkPermission.ensureAccess(baseUrl)) {
                                            errorMessage = localNetworkDenied
                                        } else {
                                            ssoInProgress = true
                                            ssoLauncher.launch(SsoLoginActivity.createIntent(context, baseUrl, provider))
                                        }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        ssoInProgress = false
                                        errorMessage = ssoErrorMessages[SsoFailure.SIGN_IN]
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }
                        ) {
                            Text(stringResource(R.string.login_sso_continue_with, provider.title))
                        }
                    }
                }
            }
            LoginMode.TOKEN -> {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.login_token)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { performLogin() })
                )
            }

            LoginMode.PASSWORD -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.login_username)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.login_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { performLogin() })
                    )
                }
            }
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { if (loginMode == LoginMode.SSO) findProviders() else performLogin() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && hostUrl.isNotBlank()
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Text(when {
                    loginMode == LoginMode.SSO -> stringResource(R.string.login_sso_find_providers)
                    isEditMode -> stringResource(R.string.common_save)
                    else -> stringResource(R.string.login_button)
                })
            }
        }
    }
}
