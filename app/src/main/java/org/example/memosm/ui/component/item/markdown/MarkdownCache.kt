package org.example.memosm.ui.component.item.markdown

import androidx.collection.LruCache
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import java.util.Locale

internal data class ParsedMarkdown(val tree: ASTNode, val references: Map<String, String>)

/** Application-lifetime cache, bounded by total source characters rather than memo count. */
internal object MarkdownCache {
    private val cache = object : LruCache<String, ParsedMarkdown>(1_000_000) {
        override fun sizeOf(key: String, value: ParsedMarkdown): Int = key.length.coerceAtLeast(1)
    }

    fun cached(content: String): ParsedMarkdown? = cache[content]

    /** Call on a worker thread. Each miss owns its parser; only read-only results are shared. */
    fun parse(content: String): ParsedMarkdown = cached(content) ?: run {
        val tree = MarkdownParser(GFMFlavourDescriptor(), cancellationToken = CancellationToken.NonCancellable).buildMarkdownTreeFromString(content as CharSequence)
        val references = buildMap {
            tree.children.filter { it.type == MarkdownElementTypes.LINK_DEFINITION }.forEach { definition ->
                val label = definition.findChildOfType(MarkdownElementTypes.LINK_LABEL)
                val destination = definition.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                if (label != null && destination != null) {
                    put(label.getTextInNode(content).toString().lowercase(Locale.ROOT),
                        destination.getTextInNode(content).toString())
                }
            }
        }
        ParsedMarkdown(tree, references).also { cache.put(content, it) }
    }
}
