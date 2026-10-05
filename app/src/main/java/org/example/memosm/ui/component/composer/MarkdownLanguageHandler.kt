package org.example.memosm.ui.component.composer

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

class MarkdownLanguageHandler(
    private val colorScheme: androidx.compose.material3.ColorScheme,
    private val typography: androidx.compose.material3.Typography
) : OutputTransformation {

    private val flavour = GFMFlavourDescriptor()
    private val parser = MarkdownParser(flavour, cancellationToken = CancellationToken.NonCancellable)

    override fun TextFieldBuffer.transformOutput() {
        highlight(toString()).spanStyles.forEach { range ->
            addStyle(range.item, range.start, range.end)
        }
    }

    fun highlight(markdownText: String): AnnotatedString {
        val rootNode = parser.buildMarkdownTreeFromString(markdownText as CharSequence)
        return buildAnnotatedString {
            append(markdownText)
            applyMarkdownStyles(rootNode, markdownText)
        }
    }

    private fun AnnotatedString.Builder.applyMarkdownStyles(node: ASTNode, text: String) {
        val range = node.startOffset..node.endOffset

        when (node.type) {
            MarkdownElementTypes.ATX_1 -> {
                addStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary
                    ), range.first, range.last
                )
            }

            MarkdownElementTypes.ATX_2 -> {
                addStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary
                    ), range.first, range.last
                )
            }

            MarkdownElementTypes.ATX_3 -> {
                addStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.secondary
                    ), range.first, range.last
                )
            }

            MarkdownElementTypes.STRONG -> {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), range.first, range.last)
            }

            MarkdownElementTypes.EMPH -> {
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), range.first, range.last)
            }

            MarkdownElementTypes.CODE_SPAN -> {
                addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = colorScheme.surfaceContainerHighest,
                        color = colorScheme.onSurfaceVariant
                    ), range.first, range.last
                )
            }

            MarkdownElementTypes.CODE_BLOCK, MarkdownElementTypes.CODE_FENCE -> {
                addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                        color = colorScheme.onSurface
                    ), range.first, range.last
                )
            }

            MarkdownElementTypes.LINK_TEXT -> {
                addStyle(
                    SpanStyle(
                        color = colorScheme.primary,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                    ), range.first, range.last
                )
            }

            MarkdownElementTypes.BLOCK_QUOTE -> {
                addStyle(
                    SpanStyle(
                        color = colorScheme.tertiary // Custom text color for block quotes
                    ), range.first, range.last
                )
            }

            // Syntax Highlighting for specific tokens
            org.intellij.markdown.flavours.gfm.GFMTokenTypes.CHECK_BOX,
            org.intellij.markdown.MarkdownTokenTypes.ATX_HEADER,
            org.intellij.markdown.MarkdownTokenTypes.LIST_BULLET,
            org.intellij.markdown.MarkdownTokenTypes.BLOCK_QUOTE,
            org.intellij.markdown.MarkdownTokenTypes.CODE_FENCE_START,
            org.intellij.markdown.MarkdownTokenTypes.CODE_FENCE_END,
            org.intellij.markdown.MarkdownTokenTypes.LPAREN,
            org.intellij.markdown.MarkdownTokenTypes.RPAREN,
            org.intellij.markdown.MarkdownTokenTypes.LBRACKET,
            org.intellij.markdown.MarkdownTokenTypes.RBRACKET -> {
                addStyle(
                    SpanStyle(
                        color = colorScheme.tertiary.copy(alpha = 0.8f) // Highlight syntax markers
                    ), range.first, range.last
                )
            }

            org.intellij.markdown.MarkdownTokenTypes.TEXT -> {
                // Highlight Hashtags
                val textContent = text.substring(range.first, range.last)
                val hashtagRegex = Regex("#[^\\s#]+")
                hashtagRegex.findAll(textContent).forEach { match ->
                    val start = range.first + match.range.first
                    val end = range.first + match.range.last + 1
                    addStyle(
                        SpanStyle(color = colorScheme.primary),
                        start,
                        end
                    )
                }
            }
        }

        for (child in node.children) {
            applyMarkdownStyles(child, text)
        }
    }

    // --- Input Processing (Auto-complete) ---

    fun processInput(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue {
        // Only trigger on single character insertion (likely newline)
        if (newValue.text.length > oldValue.text.length && newValue.selection.start == oldValue.selection.start + 1) {
            val insertedChar = newValue.text[newValue.selection.start - 1]
            if (insertedChar == '\n') {
                return handleNewline(newValue)
            }
        }
        return newValue
    }

    private fun handleNewline(newValue: TextFieldValue): TextFieldValue {
        val caretIndex = newValue.selection.start
        val text = newValue.text

        // The parser expects the full text. 
        // We are interested in the structure *before* the newline to decide what to continue.
        // However, we just inserted a newline.

        // Let's parse the text. Parsing is fast enough for this size.
        val rootNode = parser.buildMarkdownTreeFromString(text as CharSequence)

        // We want to find the node at the position just before the newline.
        val checkIndex = (caretIndex - 2).coerceAtLeast(0)
        val leafNode = findLeafNode(rootNode, checkIndex) ?: return newValue

        // Check if we are in a list item or block quote
        val (prefix, shouldContinue) = determineContinuation(leafNode, text)

        if (shouldContinue && prefix != null) {
            val newText = text.substring(0, caretIndex) + prefix + text.substring(caretIndex)
            val newSelection = TextRange(caretIndex + prefix.length)
            return newValue.copy(text = newText, selection = newSelection)
        }

        return newValue
    }

    // DFS to find leaf node at offset
    private fun findLeafNode(node: ASTNode, offset: Int): ASTNode? {
        if (offset < node.startOffset || offset >= node.endOffset) return null

        for (child in node.children) {
            val found = findLeafNode(child, offset)
            if (found != null) return found
        }
        return node
    }

    private fun determineContinuation(node: ASTNode, text: String): Pair<String?, Boolean> {
        var current: ASTNode? = node
        while (current != null) {
            when (current.type) {
                // List Items
                MarkdownElementTypes.LIST_ITEM -> {
                    // Extract the list marker
                    val nodeText = text.substring(current.startOffset, current.endOffset)

                    // Regex is still useful here to extract the exact marker from the node content
                    // (e.g. "- [ ] " vs "- ")

                    // Task list
                    val taskRegex = Regex("^(\\s*[-*+]\\s+\\[)[ xX]?(\\]\\s+)")
                    val taskMatch = taskRegex.find(nodeText)
                    if (taskMatch != null) {
                        val (prefixStart, prefixEnd) = taskMatch.destructured
                        return "$prefixStart $prefixEnd" to true
                    }

                    // Bullet list
                    val bulletRegex = Regex("^(\\s*[-*+]\\s+)")
                    val bulletMatch = bulletRegex.find(nodeText)
                    if (bulletMatch != null) {
                        return bulletMatch.groupValues[1] to true
                    }

                    // Numbered list
                    val numberedRegex = Regex("^(\\s*)(\\d+)(\\.\\s+)")
                    val numberedMatch = numberedRegex.find(nodeText)
                    if (numberedMatch != null) {
                        val (indent, numberStr, suffix) = numberedMatch.destructured
                        return try {
                            val number = numberStr.toInt()
                            "$indent${number + 1}$suffix" to true
                        } catch (e: NumberFormatException) {
                            numberedMatch.groupValues[0] to true
                        }
                    }
                }

                MarkdownElementTypes.BLOCK_QUOTE -> {
                    // "> "
                    return "> " to true
                }
            }
            current = current.parent
        }
        return null to false
    }
}
