package org.example.memosm.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.example.memosm.api.MemosApi
import org.example.memosm.model.Account
import org.example.memosm.model.User
import org.example.memosm.model.UserStats
import org.example.memosm.viewmodel.SessionState

data class SessionControls(
    val state: SessionState,
    val accounts: List<Account>,
    val fetchCurrentUser: () -> Unit
)

@Composable
fun rememberSessionState(api: MemosApi?, accounts: List<Account>): SessionControls {
    val scope = rememberCoroutineScope()
    val stateFlow = remember { MutableStateFlow(SessionState()) }
    val state by stateFlow.collectAsState()

    val activeAccount = accounts.find { it.isActive }

    LaunchedEffect(activeAccount) {
        if (activeAccount != null) {
            stateFlow.value = stateFlow.value.copy(
                token = activeAccount.accessToken,
                hostUrl = activeAccount.hostUrl,
                currUser = activeAccount.user
            )
        }
    }

    val fetchCurrentUser = {
        scope.launch {
            if (api != null && activeAccount != null) {
                try {
                    val userPath = activeAccount.user?.name ?: "users/me"
                    val user = api.getUser(userPath)
                    stateFlow.value = stateFlow.value.copy(currUser = user)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    LaunchedEffect(api, activeAccount) {
        if (api != null && activeAccount != null) {
            fetchCurrentUser()
        }
    }

    return SessionControls(
        state = state,
        accounts = accounts,
        fetchCurrentUser = { fetchCurrentUser() }
    )
}
