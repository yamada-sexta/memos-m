package org.example.memosm.ui.component.item.markdown

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

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
    val flavour = remember { GFMFlavourDescriptor() }
    val parser = remember(flavour) { MarkdownParser(flavour) }
    val treeState = produceState<ASTNode?>(initialValue = null, content) {
        value = withContext(Dispatchers.Default) {
            parser.buildMarkdownTreeFromString(content)
        }
    }
    val tree: ASTNode? = treeState.value

    if (tree == null) {
        // Parsing in progress on the background thread: show plain text now,
        // the rendered markdown replaces it as soon as the parse completes.
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier
        )
        return
    }

    val references = remember(tree, content) {
        val refs = mutableMapOf<String, String>()
        tree.children.forEach { child ->
            if (child.type == MarkdownElementTypes.LINK_DEFINITION) {
                val labelNode =
                    child.findChildOfType(MarkdownElementTypes.LINK_LABEL)
                val destNode =
                    child.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                if (labelNode != null && destNode != null) {
                    val label = labelNode.getTextInNode(content).toString().lowercase()
                    val dest = destNode.getTextInNode(content).toString()
                    refs[label] = dest
                }
            }
        }
        refs
    }

    CompositionLocalProvider(
        LocalToken provides token,
        LocalHostUrl provides hostUrl,
        LocalMarkdownReferences provides references
    ) {
        if (selectable) {
            SelectionContainer {
                NativeMarkdownNode(
                    node = tree,
                    content = content,
                    modifier = modifier,
                    headerScale = headerScale,
                    onContentChange = onContentChange,
                    onHashtagClick = onHashtagClick
                )
            }
        } else {
            NativeMarkdownNode(
                node = tree,
                content = content,
                modifier = modifier,
                headerScale = headerScale,
                onContentChange = onContentChange,
                onHashtagClick = onHashtagClick
            )
        }
    }
}
