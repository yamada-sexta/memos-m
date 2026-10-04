package org.example.memosm.ui.profile

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.example.memosm.R
import org.example.memosm.ui.component.setting.EditProfileScreen
import org.example.memosm.ui.component.setting.UserProfileUpdate
import org.example.memosm.viewmodel.MemosViewModel

/** A separate activity lets Android animate predictive Back to the profile page. */
class EditProfileActivity : ProfileActivity() {
    private val saveState: EditProfileSaveState by viewModels()

    @Composable
    override fun Destination(viewModel: MemosViewModel, onBack: () -> Unit) {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        val account = uiState.accounts.firstOrNull { it.isActive } ?: return

        LaunchedEffect(saveState.saved) {
            if (saveState.saved) {
                setResult(RESULT_OK)
                finish()
            }
        }

        EditProfileScreen(
            account = account,
            onBack = onBack,
            onSave = { update -> saveState.save(viewModel, update) },
            isSaving = saveState.isSaving,
            canSave = uiState.session.currUser != null,
            errorMessage = if (saveState.failed) stringResource(R.string.common_operation_failed) else null
        )
    }
}

/** Keep the pending save and its result through activity recreation. */
class EditProfileSaveState : ViewModel() {
    var isSaving by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun save(viewModel: MemosViewModel, update: UserProfileUpdate) {
        if (isSaving || saved) return
        isSaving = true
        failed = false
        viewModel.userDelegate.updateUserProfile(
            username = update.username,
            email = update.email,
            displayName = update.displayName,
            avatarUrl = update.avatarUrl,
            description = update.description,
            password = update.password
        ) { success ->
            isSaving = false
            saved = success
            failed = !success
        }
    }
}
