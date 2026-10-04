package org.example.memosm.ui.component.item.markdown

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import java.util.Locale

/** Only link-only paragraphs with the URL as their label qualify, matching the web client. */
fun standalonePreviewUrl(node: ASTNode, content: String, references: Map<String, String>): String? {
    if (node.type != MarkdownElementTypes.PARAGRAPH) return null
    val link = node.children.filter { it.getTextInNode(content).isNotBlank() }.singleOrNull() ?: return null
    val url = when (link.type) {
        GFMTokenTypes.GFM_AUTOLINK -> link.getTextInNode(content).toString()
        MarkdownElementTypes.AUTOLINK -> link.getTextInNode(content).toString().removeSurrounding("<", ">")
        MarkdownElementTypes.INLINE_LINK,
        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
            val textNode = link.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                ?: link.findChildOfType(MarkdownElementTypes.LINK_LABEL) ?: return null
            if (textNode.children.any { it.children.isNotEmpty() }) return null
            val label = textNode.getTextInNode(content).toString().removeSurrounding("[", "]")
            val destination = markdownLinkDestination(link, content, references)
            destination?.takeIf { it == label }
        }
        else -> null
    }
    return url?.takeIf { it.toHttpUrlOrNull() != null }
}

private fun markdownLinkDestination(node: ASTNode, content: String, references: Map<String, String>): String? {
    val destination = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
        ?: node.findChildOfType(MarkdownElementTypes.AUTOLINK)
    if (destination != null) return destination.getTextInNode(content).toString().removeSurrounding("<", ">")
    val label = node.findChildOfType(MarkdownElementTypes.LINK_LABEL)
        ?.getTextInNode(content)?.toString()?.takeUnless { it == "[]" }
        ?: node.findChildOfType(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content)?.toString()
    return label?.let { references[it.lowercase(Locale.ROOT)] }?.removeSurrounding("<", ">")
}
