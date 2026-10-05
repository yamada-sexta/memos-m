package org.example.memosm.ui.component.composer

import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.example.memosm.MemosApplication
import org.example.memosm.api.GsonProvider
import org.example.memosm.data.DraftManager
import org.example.memosm.model.Attachment
import org.example.memosm.model.Draft
import org.example.memosm.model.Location
import org.example.memosm.model.Memo
import org.example.memosm.model.Visibility
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Only the session id crosses Binder; memo and attachment snapshots stay in private storage. */
data class EditorRequest(
    val accountId: String,
    val mode: ComposerMode = ComposerMode.PUBLISH,
    val titleRes: Int,
    val draft: Draft? = null,
    val memo: Memo? = null,
    val parentMemo: Memo? = null,
    val content: String = "",
    val uris: List<String> = emptyList(),
    val visibility: Visibility = Visibility.PRIVATE,
    val location: Location? = null,
    val draftId: String = draft?.id ?: UUID.randomUUID().toString(),
    val createdAt: Long = draft?.createdAt ?: System.currentTimeMillis()
)

data class EditorAttachment(val uri: String, val attachment: Attachment?)
data class EditorSnapshot(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val visibility: Visibility,
    val location: Location?,
    val attachments: List<EditorAttachment>
)
data class StoredEditorSession(val request: EditorRequest, val snapshot: EditorSnapshot)

/** The IME's full value is retained, including selection and composing range. */
class ComposerState(snapshot: EditorSnapshot) {
    val content = mutableStateOf(TextFieldValue(snapshot.text, TextRange(
        snapshot.selectionStart.coerceIn(0, snapshot.text.length),
        snapshot.selectionEnd.coerceIn(0, snapshot.text.length)
    )))
    val visibility = mutableStateOf(snapshot.visibility)
    val location = mutableStateOf(snapshot.location)
    val attachments = mutableStateOf(snapshot.attachments.map { Uri.parse(it.uri) to it.attachment })

    fun snapshot() = EditorSnapshot(content.value.text, content.value.selection.start,
        content.value.selection.end, visibility.value, location.value,
        attachments.value.map { EditorAttachment(it.first.toString(), it.second) })
}

/** Saves outlive the activity, so system Back can finish without intercepting its gesture. */
object EditorSessionStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = ConcurrentHashMap<String, Job>()
    private val mutex = Mutex()
    private fun file(id: String): AtomicFile {
        require(UUID.fromString(id).toString() == id)
        val directory = File(MemosApplication.instance.filesDir, "editor_sessions")
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
        return AtomicFile(File(directory, "$id.json"))
    }

    suspend fun create(request: EditorRequest): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val text = request.memo?.content ?: request.draft?.content ?: request.content
        val attachments = (request.memo?.attachments ?: request.draft?.attachments).orEmpty()
            .map { EditorAttachment("", it) } + request.uris.map { EditorAttachment(it, null) }
        write(id, StoredEditorSession(request, EditorSnapshot(text, text.length, text.length,
            request.memo?.visibility ?: request.draft?.visibility ?: request.parentMemo?.visibility ?: request.visibility,
            request.memo?.location ?: request.draft?.location ?: request.location, attachments)))
        id
    }

    suspend fun read(id: String): StoredEditorSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            file(id).openRead().bufferedReader().use {
                GsonProvider.gson.fromJson(it, StoredEditorSession::class.java)
            }
        }
    }

    private fun write(id: String, session: StoredEditorSession) {
        val target = file(id)
        val output = target.startWrite()
        try {
            val writer = output.writer()
            GsonProvider.gson.toJson(session, writer)
            writer.flush()
            target.finishWrite(output)
        } catch (error: Throwable) {
            target.failWrite(output)
            throw error
        }
    }

    fun save(session: EditorSession, snapshot: EditorSnapshot, debounce: Boolean, remove: Boolean = false): Job {
        return scope.launch {
            if (debounce) delay(500)
            try {
                mutex.withLock {
                    // Once a write starts, finish it before a newer snapshot or submission.
                    withContext(NonCancellable) {
                        if (!session.completed) {
                            write(session.id, StoredEditorSession(session.request, snapshot))
                            if (session.request.mode == ComposerMode.PUBLISH && !session.submitting) {
                                val draft = Draft(id = session.request.draftId, content = snapshot.text,
                                    visibility = snapshot.visibility, location = snapshot.location,
                                    attachments = snapshot.attachments.mapNotNull { it.attachment },
                                    createdAt = session.createdAt)
                                if (draft.hasContent()) GlobalContext.get().get<DraftManager>()
                                    .saveDraft(session.request.accountId, draft, strict = true)
                            }
                        }
                        if (remove || session.completed) file(session.id).delete()
                        session.saveFailed = false
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                session.saveFailed = true
                Log.e("EditorSession", "Could not save editor session", error)
            }
        }.also { job ->
            writes[session.id] = job
            if (remove) job.invokeOnCompletion { writes.remove(session.id, job) }
        }
    }

    suspend fun awaitWrites(id: String?) {
        if (id == null) return
        while (true) {
            val write = writes[id] ?: return
            write.join()
            if (writes.remove(id, write)) return
        }
    }
}

class EditorSession(val id: String, stored: StoredEditorSession) {
    val request = stored.request
    val state = ComposerState(stored.snapshot)
    val createdAt = request.createdAt
    @Volatile var completed = false
        private set
    @Volatile var submitting = false
        private set
    var saveFailed by mutableStateOf(false)
        internal set
    var submitFailed by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    private var closing = false
    private var pendingSave: Job? = null

    fun changed() {
        if (completed || busy || closing) return
        pendingSave?.cancel()
        pendingSave = EditorSessionStore.save(this, state.snapshot(), debounce = true)
    }

    fun flush(remove: Boolean = false): Job {
        if (remove) closing = true
        pendingSave?.cancel()
        return EditorSessionStore.save(this, state.snapshot(), debounce = false, remove = remove)
            .also { pendingSave = it }
    }

    fun beginSubmission(): Boolean {
        if (busy || completed || closing) return false
        busy = true
        submitFailed = false
        return true
    }

    suspend fun prepareSubmission(): Boolean {
        flush().join()
        if (saveFailed) return false
        submitting = true
        return true
    }

    fun submissionFailed() {
        submitFailed = true
        busy = false
        submitting = false
        changed()
    }

    fun close() {
        if (!closing) flush(remove = true)
    }

    fun submitted() {
        completed = true
        pendingSave?.cancel()
        flush(remove = true)
    }
}
