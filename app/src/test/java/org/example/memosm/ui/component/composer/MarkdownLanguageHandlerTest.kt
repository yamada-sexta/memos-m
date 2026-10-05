package org.example.memosm.ui.component.composer

import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLanguageHandlerTest {
    private val handler = MarkdownLanguageHandler(lightColorScheme(), Typography())

    @Test
    fun highlightingPreservesTextAndStylesMarkdown() {
        val source = "# Heading\n\n**bold** and `code`"
        val result = handler.highlight(source)

        assertEquals(source, result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(result.spanStyles.all { it.start >= 0 && it.end <= source.length })
        assertEquals("", handler.highlight("").text)
    }

    @Test
    fun newlinesContinueBulletAndNumberedLists() {
        assertContinuation("- item", "- ")
        assertContinuation("3. item", "4. ")
    }

    @Test
    fun newlinesContinueTasksAndBlockQuotes() {
        assertContinuation("- [x] done", "- [ ] ")
        assertContinuation("> quoted", "> ")
    }

    @Test
    fun ordinaryTypingPreservesSelectionAndImeComposition() {
        val old = TextFieldValue("typ", TextRange(3), TextRange(0, 3))
        val typed = TextFieldValue("type", TextRange(4), TextRange(0, 4))

        assertEquals(typed, handler.processInput(old, typed))
    }

    private fun assertContinuation(source: String, prefix: String) {
        val old = TextFieldValue(source, TextRange(source.length))
        val typed = TextFieldValue("$source\n", TextRange(source.length + 1))
        val expected = "$source\n$prefix"

        val result = handler.processInput(old, typed)

        assertEquals(expected, result.text)
        assertEquals(TextRange(expected.length), result.selection)
    }
}
