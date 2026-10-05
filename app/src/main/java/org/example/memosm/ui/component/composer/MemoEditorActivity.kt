package org.example.memosm.ui.component.composer

import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.example.memosm.R
import org.example.memosm.ui.profile.ProfileActivity
import org.example.memosm.viewmodel.MemosViewModel

private const val EXTRA_SESSION = "editor_session"
private const val EXTRA_ACCOUNT = "editor_account"

class MemoEditorActivity : ProfileActivity() {
    private val editor: MemoEditorViewModel by viewModels()
    override val requiredAccountId: String? get() = intent.getStringExtra(EXTRA_ACCOUNT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Return the session id even for system Back, so the caller can await the final save.
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_SESSION, intent.getStringExtra(EXTRA_SESSION)))
        editor.load(intent.getStringExtra(EXTRA_SESSION))
    }

    @Composable
    override fun Destination(viewModel: MemosViewModel, onBack: () -> Unit) {
        val uiState by viewModel.uiState.collectAsState()
        val session = editor.session
        if (editor.failed) {
            Text(stringResource(R.string.common_operation_failed))
            return
        }
        if (session == null) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
            return
        }
        LaunchedEffect(session) {
            if (session.request.mode == ComposerMode.PUBLISH) {
                viewModel.draftDelegate.setCurrentEditingDraft(session.request.draftId)
            }
            snapshotFlow { session.state.snapshot() }.collect { session.changed() }
        }
        MemoComposerScreen(viewModel = viewModel, hostUrl = uiState.session.hostUrl,
            title = stringResource(session.request.titleRes),
            initialMemo = session.request.memo, parentMemo = session.request.parentMemo,
            mode = session.request.mode, editorSession = session,
            onDismiss = onBack,
            onSubmitted = {
                session.submitted()
                setResult(RESULT_OK, Intent().putExtra(EXTRA_SESSION, session.id))
                finish()
            })
    }

    companion object {
        suspend fun createIntent(context: Context, request: EditorRequest): Intent =
            Intent(context, MemoEditorActivity::class.java)
                .putExtra(EXTRA_SESSION, EditorSessionStore.create(request))
                .putExtra(EXTRA_ACCOUNT, request.accountId)
    }

    override fun onPause() {
        // Activity results can reach the caller before onStop, so start the final save now.
        editor.session?.flush()
        super.onPause()
    }

    override fun onStop() {
        editor.session?.flush(remove = isFinishing)
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) editor.session?.close()
        super.onDestroy()
    }
}

class MemoEditorViewModel : ViewModel() {
    var session by mutableStateOf<EditorSession?>(null)
        private set
    var failed by mutableStateOf(false)
        private set
    private var loading = false
    fun load(id: String?) {
        if (session != null || loading) return
        loading = true
        viewModelScope.launch {
            try {
                requireNotNull(id)
                session = EditorSession(id, EditorSessionStore.read(id))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
            }
        }
    }
}

/** Capture a launch snapshot once; parent repositories refresh only after the final save. */
@Composable
fun rememberMemoEditorLauncher(viewModel: MemosViewModel): (EditorRequest) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var launched by androidx.compose.runtime.remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        launched = false
        scope.launch {
            EditorSessionStore.awaitWrites(result.data?.getStringExtra(EXTRA_SESSION))
            viewModel.refreshAfterEditor()
        }
    }
    return open@{ request ->
        if (launched) return@open
        launched = true
        scope.launch {
            try {
                launcher.launch(MemoEditorActivity.createIntent(context, request))
            } catch (error: CancellationException) {
                launched = false
                throw error
            } catch (error: Exception) {
                launched = false
                Log.e("MemoEditor", "Could not open editor session", error)
                Toast.makeText(context, R.string.common_operation_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
