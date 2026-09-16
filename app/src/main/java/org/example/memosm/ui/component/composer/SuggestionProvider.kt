package org.example.memosm.ui.component.composer

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Title
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import org.example.memosm.R
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

data class SuggestionItem(
    val label: String,
    val content: String,
    val icon: ImageVector,
    val type: SuggestionType
)

data class SuggestionResult(
    val suggestions: List<SuggestionItem>,
    val startIndex: Int,
    val replacementPrefix: String = "",
    val type: SuggestionType
)


enum class SuggestionType(val isAutoShown: Boolean) {
    HASHTAG(true),
    MARKDOWN(false),
    CODE_LANGUAGE(false),
    FORMATTING(true), // For selection-based
    AUTO_MARKDOWN(true)
}

object SuggestionProvider {
    private val flavour = GFMFlavourDescriptor()
    private val parser = MarkdownParser(flavour)

    private val CODE_LANGUAGES = listOf(
        "java", "kotlin", "js", "python", "bash", "go", "rust", "cpp", "c",
        "html", "css", "sql", "json", "yaml", "xml", "swift", "dart", "php", "ruby", "lua"
    )

    fun getSuggestions(
        text: String,
        selection: TextRange,
        availableTags: Map<String, Int>,
        resources: Resources
    ): SuggestionResult? {
        // 1. Selection Logic (Formatting)
        if (!selection.collapsed) {
            return getFormattingSuggestions(selection, resources)
        }

        val cursorIndex = selection.start
        if (cursorIndex < 0 || cursorIndex > text.length) return null

        // Parse AST
        val rootNode = parser.buildMarkdownTreeFromString(text)
        val nodeAtCursor = findNodeAt(rootNode, (cursorIndex - 1).coerceAtLeast(0))

        // 2. Context Checks (Guard against suggestions inside fenced code)
        if (isInCodeBlock(nodeAtCursor, cursorIndex)) {
            // Check if we are in the language declaration part of a fence
            if (isLanguagePart(nodeAtCursor, cursorIndex, text)) {
                return getLanguageSuggestions(text, cursorIndex, resources = resources)
            }
            return null
        }

        val textBeforeCursor = text.take(cursorIndex)
        val lastNewlineIndex = textBeforeCursor.lastIndexOf('\n')
        val lineStartIndex = lastNewlineIndex + 1
        val currentLinePrefix = textBeforeCursor.substring(lineStartIndex)

        // 3. Hashtag Check (Highest Priority if active matches)
        val lastHashIndex = textBeforeCursor.lastIndexOf('#')
        if (lastHashIndex != -1) {
            val potentialTag = textBeforeCursor.substring(lastHashIndex + 1)
            // Ensure no spaces or newlines in the tag being typed (simple heuristic still useful)
            if (!potentialTag.contains(' ') && !potentialTag.contains('\n')) {
                // Double check we are not inside a link or something else via AST?
                // For now, the isInCodeBlock guard handles the main exclusion.

                val filteredTags = if (potentialTag.isEmpty()) {
                    availableTags.toList()
                } else {
                    availableTags.filterKeys { it.contains(potentialTag, ignoreCase = true) }.toList()
                }.sortedByDescending { it.second }

                if (filteredTags.isNotEmpty()) {
                    val suggestionItems = filteredTags.map { (tag, _) ->
                        SuggestionItem(
                            label = tag,
                            content = tag, // No # prefix in content, caller handles prefix? Or we handle replacement logic
                            icon = Icons.Outlined.Tag,
                            type = SuggestionType.HASHTAG
                        )
                    }

                    return SuggestionResult(
                        suggestions = suggestionItems,
                        startIndex = lastHashIndex,
                        replacementPrefix = "#",
                        type = SuggestionType.HASHTAG
                    )
                } else {
                    // Start of Line Fallback:
                    // If we are at the start of the line (ignoring the hash we are typing),
                    // we should still show the block suggestions (Icon).
                    // Example: "#unknown" -> No tags, but valid block position.
                    // Check if everything before lastHashIndex on this line is whitespace
                    val prefixBeforeHash = textBeforeCursor.substring(lineStartIndex, lastHashIndex)
                    if (prefixBeforeHash.trim().isEmpty()) {
                        return getBlockSuggestions(lineStartIndex, resources)
                    }
                }
            }
        }

        // 4. Start of Line / Block Context
        if (currentLinePrefix.trim().isEmpty()) {
            return getBlockSuggestions(lineStartIndex, resources)
        }

        // Header suggestion while typing '#'
        if (currentLinePrefix.all { it == '#' }) {
            // User is typing headers
            // We can suggest changing level
            val currentLevel = currentLinePrefix.length
            // Provide suggestions for headers
            return getHeaderSuggestions(lineStartIndex, currentLevel, resources)
        }

        // List suggestion while typing '-'
        if (currentLinePrefix == "-") {
            return getListSuggestions(lineStartIndex, resources)
        }

        // code fence start
        if (currentLinePrefix == "```") {
            return getLanguageSuggestions(text, cursorIndex, resources = resources)
        }
        if (currentLinePrefix.startsWith("```")) {
            val typedLang = currentLinePrefix.substring(3)
            if (!typedLang.contains(' ')) {
                return getLanguageSuggestions(text, cursorIndex, resources = resources, filter = typedLang)
            }
        }

        return null
    }

    private fun getFormattingSuggestions(selection: TextRange, resources: Resources): SuggestionResult {
        val items = listOf(
            SuggestionItem(resources.getString(R.string.memo_input_format_bold), "**", Icons.Outlined.FormatBold, SuggestionType.FORMATTING),
            SuggestionItem(resources.getString(R.string.memo_input_format_italic), "_", Icons.Outlined.FormatItalic, SuggestionType.FORMATTING),
            SuggestionItem(
                resources.getString(R.string.memo_input_format_strikethrough),
                "~~",
                Icons.Outlined.FormatStrikethrough,
                SuggestionType.FORMATTING
            ),
            SuggestionItem(resources.getString(R.string.memo_input_format_code), "`", Icons.Outlined.Code, SuggestionType.FORMATTING)
        )
        return SuggestionResult(items, selection.start, "", SuggestionType.FORMATTING)
    }

    private fun getBlockSuggestions(startIndex: Int, resources: Resources): SuggestionResult {
        val items = listOf(
            SuggestionItem(resources.getString(R.string.memo_input_heading, 1), "# ", Icons.Outlined.Title, SuggestionType.MARKDOWN),
            SuggestionItem(resources.getString(R.string.memo_input_heading, 2), "## ", Icons.Outlined.Title, SuggestionType.MARKDOWN),
            SuggestionItem(resources.getString(R.string.memo_input_heading, 3), "### ", Icons.Outlined.Title, SuggestionType.MARKDOWN),
            SuggestionItem(
                resources.getString(R.string.memo_input_bullet_list),
                "- ",
                Icons.AutoMirrored.Outlined.List,
                SuggestionType.MARKDOWN
            ),
            SuggestionItem(resources.getString(R.string.memo_input_task_list), "- [ ] ", Icons.Outlined.Check, SuggestionType.MARKDOWN),
            SuggestionItem(resources.getString(R.string.memo_input_quote), "> ", Icons.Outlined.FormatQuote, SuggestionType.MARKDOWN),
            SuggestionItem(resources.getString(R.string.memo_input_code_block), "```\n```", Icons.Outlined.Code, SuggestionType.MARKDOWN)
        )
        return SuggestionResult(items, startIndex, "", SuggestionType.MARKDOWN)
    }

    private fun getHeaderSuggestions(startIndex: Int, currentLevel: Int, resources: Resources): SuggestionResult {
        // Provide explicit header options
        val items = (1..6).map { level ->
            val hashes = "#".repeat(level) + " "
            SuggestionItem(
                resources.getString(R.string.memo_input_heading, level),
                hashes,
                Icons.Outlined.FormatSize,
                SuggestionType.AUTO_MARKDOWN
            )
        }

        return SuggestionResult(items, startIndex, "", SuggestionType.AUTO_MARKDOWN)
    }

    private fun getListSuggestions(startIndex: Int, resources: Resources): SuggestionResult {
        val items = listOf(
            SuggestionItem(
                resources.getString(R.string.memo_input_bullet_list),
                "- ",
                Icons.AutoMirrored.Outlined.List,
                SuggestionType.AUTO_MARKDOWN
            ),
            SuggestionItem(
                resources.getString(R.string.memo_input_task_list),
                "- [ ] ",
                Icons.Outlined.Check,
                SuggestionType.AUTO_MARKDOWN
            )
        )
        return SuggestionResult(items, startIndex, "", SuggestionType.AUTO_MARKDOWN)
    }

    private fun getLanguageSuggestions(
        text: String,
        cursorIndex: Int,
        resources: Resources,
        filter: String = ""
    ): SuggestionResult {
        // Determine start index for replacement.
        // If we are ````kotlin`, replacement starts after ```
        // We need to find where ``` is on this line.
        val lastNewline = text.lastIndexOf('\n', cursorIndex - 1)
        val lineStart = lastNewline + 1
        val fenceStart = text.indexOf("```", lineStart)
        val replaceStart = if (fenceStart != -1) fenceStart + 3 else cursorIndex

        val filtered = if (filter.isEmpty()) CODE_LANGUAGES else CODE_LANGUAGES.filter {
            it.startsWith(
                filter,
                ignoreCase = true
            )
        }

        val items = filtered.map {
            SuggestionItem(it, it, Icons.Outlined.Code, SuggestionType.CODE_LANGUAGE)
        }
        return SuggestionResult(items, replaceStart, "", SuggestionType.CODE_LANGUAGE)
    }

    private fun findNodeAt(node: ASTNode, offset: Int): ASTNode {
        var current = node
        while (true) {
            val child = current.children.find { offset in it.startOffset until it.endOffset }
            if (child != null) {
                current = child
            } else {
                return current
            }
        }
    }

    private fun isInCodeBlock(node: ASTNode, offset: Int): Boolean {
        var current: ASTNode? = node
        while (current != null) {
            if (current.type == MarkdownElementTypes.CODE_BLOCK ||
                current.type == MarkdownElementTypes.CODE_FENCE
            ) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun isLanguagePart(node: ASTNode, offset: Int, text: String): Boolean {
        // In a CODE_FENCE, the language is usually matched as text or Fence Lang
        // Simplest check: Are we on the first line of the fence?
        var current: ASTNode? = node
        while (current != null && current.type != MarkdownElementTypes.CODE_FENCE) {
            current = current.parent
        }
        if (current == null) return false // Not in fence

        // Check if we are on the opening line
        val fenceStart = current.startOffset
        val fenceContent = text.substring(current.startOffset, current.endOffset)
        val firstNewline = fenceContent.indexOf('\n')

        if (firstNewline == -1) return true // Still typing first line

        val absoluteNewline = fenceStart + firstNewline
        return offset <= absoluteNewline
    }
}
