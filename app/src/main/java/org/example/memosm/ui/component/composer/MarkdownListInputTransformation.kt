package org.example.memosm.ui.component.composer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.forEachChange
import androidx.compose.foundation.text.input.insert

@OptIn(ExperimentalFoundationApi::class)
class MarkdownListInputTransformation : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount == 0) return

        changes.forEachChange { range, originalRange ->
            if (range.length > originalRange.length) {
                val text = asCharSequence()
                val insertedContent = text.subSequence(range.min, range.max)
                if (insertedContent.contains('\n')) {
                    val newlineIndex = range.min + insertedContent.indexOf('\n')

                    // Check logic: if cursor is exactly after the newline, we trigger.
                    // This handles standard typing behavior.
                    if (selection.start == newlineIndex + 1) {
                        handleNewLine(newlineIndex)
                    }
                }
            }
        }
    }

    private fun TextFieldBuffer.handleNewLine(newlineIndex: Int) {
        val text = asCharSequence()

        // Find start of the previous line
        var lineStartIndex = newlineIndex - 1
        while (lineStartIndex >= 0 && text[lineStartIndex] != '\n') {
            lineStartIndex--
        }
        lineStartIndex++

        if (lineStartIndex < newlineIndex) {
            val previousLine = text.subSequence(lineStartIndex, newlineIndex).toString()

            val matchResult = getMarkdownPrefix(previousLine)
            if (matchResult != null) {
                insert(selection.start, matchResult)
            }
        }
    }

    private fun getMarkdownPrefix(line: String): String? {
        // Bullet points: - * +
        // Task lists: - [ ] or - [x]
        // Numbered: 1.
        // Indentation is preserved.

        // Match bullet: ^(\s*)([-*+])\s+(.*)
        // Match task: ^(\s*)([-*+])\s+\[([ xX])\]\s+(.*)
        // Match numbered: ^(\s*)(\d+)\.\s+(.*)
        // Match regex for brackets/parens if simple list item style: ^(\s*)([\[\(].*[\]\)])\s+(.*) -- user asked for [] and () support specifically.
        // User example: "[]" and "()"

        // Let's use specific regexes.

        // 1. Task List: "- [ ] " or "- [x] "
        // If it was "- [x] ", we probably want to continue with "- [ ] " (unchecked)
        val taskRegex = Regex("^(\\s*[-*+]\\s+\\[)[ xX]?(\\]\\s+)")
        val taskMatch = taskRegex.find(line)
        if (taskMatch != null) {
            val (prefixStart, prefixEnd) = taskMatch.destructured
            // Return "- [ ] " always for continuation
            return "$prefixStart $prefixEnd"
        }

        // 2. Bullet List: "- " or "* " or "+ "
        val bulletRegex = Regex("^(\\s*[-*+]\\s+)")
        val bulletMatch = bulletRegex.find(line)
        if (bulletMatch != null) {
            return bulletMatch.groupValues[1]
        }

        // 3. Numbered List: "1. "
        // We will try to just repeat it for now, or maybe increment?
        // Implementation plan said "repeat". 
        // Logic for incrementing is nice but parsing the number is needed.
        val numberedRegex = Regex("^(\\s*)(\\d+)(\\.\\s+)")
        val numberedMatch = numberedRegex.find(line)
        if (numberedMatch != null) {
            val (indent, numberStr, suffix) = numberedMatch.destructured
            try {
                val number = numberStr.toInt()
                return "$indent${number + 1}$suffix"
            } catch (e: NumberFormatException) {
                return matchResultToText(numberedMatch) // Fallback
            }
        }

        // 4. Blockquote: "> "
        val quoteRegex = Regex("^(\\s*>\\s+)")
        val quoteMatch = quoteRegex.find(line)
        if (quoteMatch != null) {
            return quoteMatch.groupValues[1]
        }

        // 5. Brackets [] and Parens ()
        // User asked for "[]" and "()". Assumed to be used as list markers like "[] item".
        val bracketRegex = Regex("^(\\s*\\[\\]\\s+)")
        if (bracketRegex.containsMatchIn(line)) {
            return bracketRegex.find(line)!!.groupValues[1]
        }

        val parenRegex = Regex("^(\\s*\\(\\)\\s+)")
        if (parenRegex.containsMatchIn(line)) {
            return parenRegex.find(line)!!.groupValues[1]
        }

        return null
    }

    private fun matchResultToText(matchResult: MatchResult): String {
        return matchResult.groupValues[0]
    }
}
