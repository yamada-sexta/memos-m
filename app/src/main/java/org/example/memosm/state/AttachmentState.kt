package org.example.memosm.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import org.example.memosm.api.MemosApi
import org.example.memosm.model.Attachment
import org.example.memosm.viewmodel.AttachmentListState
import org.example.memosm.viewmodel.PaginatedListState

data class AttachmentControls(
    val state: AttachmentListState,
    val fetch: (Boolean) -> Unit,
    val loadMore: () -> Unit,
    val updateCellWidth: (Float) -> Unit,
    val deleteAttachment: (String) -> Unit
)

@Composable
fun rememberAttachmentState(api: MemosApi?, initialCellWidth: Float = 240f): AttachmentControls {
    val scope = rememberCoroutineScope()

    val holder = remember(api) {
        ListStateHolder<Attachment>(
            scope = scope,
            fetcher = { token ->
                if (api == null) return@ListStateHolder emptyList<Attachment>() to null
                val response = api.listAttachments(pageToken = token)
                (response.attachments ?: emptyList()) to response.nextPageToken
            },
            cacheSaver = { },
            cacheLoader = { emptyList() }
        )
    }

    LaunchedEffect(holder) { holder.fetch() }

    val paginatedState by holder.state.collectAsState()

    // Simplification for cell width state for refactor
    val cellWidth = initialCellWidth

    return AttachmentControls(
        state = AttachmentListState(list = paginatedState, cellWidth = cellWidth),
        fetch = { refresh -> holder.fetch(refresh = refresh) },
        loadMore = { holder.loadMore() },
        updateCellWidth = { },
        deleteAttachment = { name -> holder.removeItem { it.name == name } }
    )
}
