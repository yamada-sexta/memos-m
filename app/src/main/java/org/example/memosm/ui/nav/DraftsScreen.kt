package org.example.memosm.ui.nav

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.model.Draft
import org.example.memosm.ui.component.composer.EditorRequest
import org.example.memosm.ui.component.composer.rememberMemoEditorLauncher
import org.example.memosm.ui.component.ProfileBackButton
import org.example.memosm.ui.component.item.MemoItem
import org.example.memosm.viewmodel.MemosViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftsScreen(
    viewModel: MemosViewModel, onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val drafts = uiState.draft.drafts

    val openEditor = rememberMemoEditorLauncher(viewModel)
    val editDraft: (Draft) -> Unit = { draft ->
        uiState.accounts.firstOrNull { it.isActive }?.let { account ->
            openEditor(EditorRequest(account.id, titleRes = R.string.drafts_action_edit, draft = draft))
        }
    }
    var draftToDelete by remember { mutableStateOf<Draft?>(null) }

    Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.drafts_title)) }, navigationIcon = {
            ProfileBackButton(onClick = onBack)
        })
    }) { padding ->
        if (drafts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.drafts_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(drafts, key = { it.id }) { draft ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        MemoItem(
                            memo = draft.toMemo(),
                            token = uiState.session.token,
                            hostUrl = uiState.session.hostUrl,
                            onClick = { editDraft(draft) },
                            onEdit = { editDraft(draft) },
                            onDelete = { draftToDelete = draft },
                            maxHeight = 400.dp,
                            modifier = Modifier.widthIn(max = 800.dp),
                            headerScale = uiState.appSettings.headerScale
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (draftToDelete != null) {
        AlertDialog(
            onDismissRequest = { draftToDelete = null },
            title = { Text(stringResource(R.string.drafts_delete_confirm_title)) },
            text = { Text(stringResource(R.string.drafts_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.draftDelegate.deleteDraft(draftToDelete!!.id)
                        draftToDelete = null
                    }, colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { draftToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            })
    }
}
