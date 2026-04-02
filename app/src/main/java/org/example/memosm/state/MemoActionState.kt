package org.example.memosm.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.example.memosm.api.MemosApi
import android.content.Context
import android.net.Uri
import org.example.memosm.model.Attachment
import org.example.memosm.model.Memo
import org.example.memosm.model.Reaction
import org.example.memosm.model.UpsertMemoReactionRequest

data class MemoActionControls(
    val postMemo: (Memo) -> Unit,
    val deleteMemo: (String) -> Unit,
    val updateMemo: (Memo, Memo) -> Unit,
    val createComment: (Memo, String) -> Unit,
    val uploadAttachment: (Uri, Context) -> Unit,
    val updateMemoPinned: (Memo, Boolean) -> Unit,
    val upsertMemoReaction: (Memo, String) -> Unit,
    val deleteMemoReaction: (Memo, Reaction) -> Unit
)

@Composable
fun rememberMemoActionState(
    api: MemosApi?,
    onMemoCreated: (Memo) -> Unit,
    onMemoUpdated: (Memo) -> Unit,
    onMemoDeleted: (String) -> Unit
): MemoActionControls {
    val scope = rememberCoroutineScope()

    return MemoActionControls(
        postMemo = { memo ->
             scope.launch {
                 if (api != null) {
                     try {
                         val response = api.createMemo(memo)
                         onMemoCreated(response)
                     } catch (e: Exception) {
                         e.printStackTrace()
                     }
                 }
             }
        },
        createComment = { parentMemo, content ->
             scope.launch {
                 if (api != null && parentMemo.name != null) {
                     try {
                         val response = api.createMemoComment(parentMemo.name, Memo(content = content))
                         // TODO: Need comment callback if they are displayed immediately
                     } catch (e: Exception) {
                         e.printStackTrace()
                     }
                 }
             }
        },
        uploadAttachment = { uri, context ->
            // Stubbed for complex file upload logic (content resolver, streams, API post) which
            // was in the original AttachmentManager.
        },
        updateMemoPinned = { memo, pinned ->
            scope.launch {
                 if (api != null && memo.name != null) {
                    try {
                        val response = api.updateMemo(memo.name, memo.copy(pinned = pinned), "pinned")
                        onMemoUpdated(response)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        },
        upsertMemoReaction = { memo, emoji ->
            scope.launch {
                 if (api != null && memo.name != null) {
                    try {
                        val reaction = api.upsertMemoReaction(memo.name, UpsertMemoReactionRequest(name = memo.name, reaction = Reaction(contentId = memo.name, reactionType = emoji)))
                        // Local update mock
                        val currentReactions = memo.reactions?.toMutableList() ?: mutableListOf()
                        currentReactions.add(reaction)
                        onMemoUpdated(memo.copy(reactions = currentReactions))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        },
        deleteMemoReaction = { memo, reaction ->
            scope.launch {
                 if (api != null && memo.name != null && reaction.name != null) {
                    try {
                        api.deleteMemoReaction(reaction.name)
                        val currentReactions = memo.reactions?.filterNot { it.name == reaction.name }
                        onMemoUpdated(memo.copy(reactions = currentReactions))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        },
        deleteMemo = { name ->
            scope.launch {
                if (api != null) {
                    try {
                        api.deleteMemo(name)
                        onMemoDeleted(name)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        },
        updateMemo = { memo, request ->
            scope.launch {
                 if (api != null) {
                    try {
                        val response = api.updateMemo(memo.name!!, request, "content,visibility,location")
                        onMemoUpdated(response)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    )
}
