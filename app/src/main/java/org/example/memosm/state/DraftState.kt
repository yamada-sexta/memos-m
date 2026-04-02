package org.example.memosm.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.example.memosm.data.DraftManager
import org.example.memosm.model.Draft
import org.example.memosm.viewmodel.DraftState

data class DraftControls(
    val state: DraftState,
    val saveDraft: (Draft) -> Unit,
    val deleteDraft: (String) -> Unit
)

@Composable
fun rememberDraftState(draftManager: DraftManager, accountId: Long?): DraftControls {
    val scope = rememberCoroutineScope()
    val stateFlow = remember { MutableStateFlow(DraftState()) }
    val state by stateFlow.collectAsState()

    LaunchedEffect(accountId) {
        if (accountId != null) {
            scope.launch {
                val drafts = draftManager.getDrafts(accountId.toString())
                stateFlow.value = stateFlow.value.copy(drafts = drafts, isDraftLoaded = true)
            }
        } else {
            stateFlow.value = DraftState()
        }
    }

    return DraftControls(
        state = state,
        saveDraft = { draft ->
            scope.launch {
                if (accountId != null) {
                    draftManager.saveDraft(accountId.toString(), draft)
                    val updatedDrafts = draftManager.getDrafts(accountId.toString())
                    stateFlow.value = stateFlow.value.copy(drafts = updatedDrafts)
                }
            }
        },
        deleteDraft = { id ->
             scope.launch {
                 if (accountId != null) {
                     draftManager.deleteDraft(accountId.toString(), id)
                     val updatedDrafts = draftManager.getDrafts(accountId.toString())
                     stateFlow.value = stateFlow.value.copy(drafts = updatedDrafts)
                 }
             }
        }
    )
}
