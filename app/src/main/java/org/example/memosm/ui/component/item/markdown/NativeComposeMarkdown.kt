package org.example.memosm.ui.component.item.markdown

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalToken = compositionLocalOf { "" }
val LocalHostUrl = compositionLocalOf { "" }
val LocalMarkdownReferences = compositionLocalOf { emptyMap<String, String>() }

/**
 * Renders [content] as markdown.
 *
 * Parsing runs on [Dispatchers.Default] (never the main thread): memo lists
 * can contain hundreds of items, and a synchronous
 * `buildMarkdownTreeFromString` per item on first composition caused visible
 * scroll jank on slower devices. While the tree is being parsed (a few ms)
 * the raw text is shown, then the rendered markdown swaps in.
 */
@Composable
fun NativeComposeMarkdown(
    modifier: Modifier = Modifier,
    content: String,
    token: String = "",
    hostUrl: String = "",
    selectable: Boolean = false,
    headerScale: Float,
    onContentChange: ((String) -> Unit)? = null,
    onHashtagClick: ((String) -> Unit)? = null
) {
    val parsed = key(content) {
        val cached = remember { MarkdownCache.cached(content) }
        produceState(initialValue = cached, content) {
            if (value == null) value = withContext(Dispatchers.Default) { MarkdownCache.parse(content) }
        }
    }.value

    if (parsed == null) {
        // Parsing in progress on the background thread: show plain text now,
        // the rendered markdown replaces it as soon as the parse completes.
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier
        )
        return
    }

    CompositionLocalProvider(
        LocalToken provides token,
        LocalHostUrl provides hostUrl,
        LocalMarkdownReferences provides parsed.references
    ) {
        if (selectable) {
            SelectionContainer {
                NativeMarkdownNode(
                    node = parsed.tree,
                    content = content,
                    modifier = modifier,
                    headerScale = headerScale,
                    onContentChange = onContentChange,
                    onHashtagClick = onHashtagClick
                )
            }
        } else {
            NativeMarkdownNode(
                node = parsed.tree,
                content = content,
                modifier = modifier,
                headerScale = headerScale,
                onContentChange = onContentChange,
                onHashtagClick = onHashtagClick
            )
        }
    }
}
