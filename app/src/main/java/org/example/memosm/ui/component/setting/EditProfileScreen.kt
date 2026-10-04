package org.example.memosm.ui.component.setting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.example.memosm.R
import org.example.memosm.model.Account
import org.example.memosm.ui.component.ProfileBackButton
import org.example.memosm.ui.component.resolveResourceUrl

data class UserProfileUpdate(
    val username: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val description: String? = null,
    val password: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    account: Account,
    onBack: () -> Unit,
    onSave: (UserProfileUpdate) -> Unit,
    isSaving: Boolean = false,
    canSave: Boolean = true,
    errorMessage: String? = null
) {
    var username by rememberSaveable(account.id) { mutableStateOf(account.name ?: "") }
    var email by rememberSaveable(account.id) { mutableStateOf(account.email ?: "") }
    var displayName by rememberSaveable(account.id) { mutableStateOf(account.displayName ?: "") }
    var avatarUrl by rememberSaveable(account.id) { mutableStateOf(account.avatarUrl ?: "") }
    var description by rememberSaveable(account.id) { mutableStateOf(account.description ?: "") }
    var password by remember { mutableStateOf("") }

    // Idle Back is owned by Android so it can preview the profile activity underneath.
    BackHandler(enabled = isSaving) {}

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_edit_account)) },
                navigationIcon = {
                    ProfileBackButton(onClick = { if (!isSaving) onBack() })
                },
                actions = {
                    TextButton(
                        onClick = {
                            onSave(
                                UserProfileUpdate(
                                    username = username.takeIf { it != (account.name ?: "") },
                                    email = email.takeIf { it != (account.email ?: "") },
                                    displayName = displayName.takeIf { it != (account.displayName ?: "") },
                                    avatarUrl = avatarUrl.takeIf { it != (account.avatarUrl ?: "") },
                                    description = description.takeIf { it != (account.description ?: "") },
                                    password = password.takeIf { it.isNotBlank() }
                                )
                            )
                        },
                        enabled = !isSaving && canSave
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    )
                }

                // Avatar preview
                AsyncImage(
                    model = resolveResourceUrl(account.hostUrl, avatarUrl.ifBlank { account.avatarUrl }),
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "@$username",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.profile_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.profile_display_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.profile_email)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = avatarUrl,
                    onValueChange = { avatarUrl = it },
                    label = { Text(stringResource(R.string.profile_avatar_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.profile_description)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.profile_new_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving,
                    supportingText = {
                        Text(stringResource(R.string.profile_password_hint))
                    }
                )
            }
        }
    }
}
