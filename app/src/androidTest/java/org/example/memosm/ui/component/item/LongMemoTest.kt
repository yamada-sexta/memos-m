package org.example.memosm.ui.component.item

import android.graphics.Bitmap
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.example.memosm.model.Memo
import org.example.memosm.ui.component.item.markdown.NativeComposeMarkdown
import org.example.memosm.ui.theme.MemosMTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class LongMemoTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longBlockquotePreviewDoesNotCrash() {
        val content = "> **Quote start**\n" + "> x\n".repeat(2_000) + "> Quote end"
        checkTimeline(content, "Quote start")
    }

    @Test
    fun longParagraphPreviewDoesNotCrash() {
        val content = "**Paragraph start** " + "word ".repeat(1_600) + "Paragraph end"
        checkTimeline(content, "Paragraph start")
    }

    @Test
    fun manyShortLinesPreviewDoesNotCrash() {
        val content = "**Lines start**\n" + "x\n".repeat(4_000) + "Lines end"
        checkTimeline(content, "Lines start")
    }

    @Test
    fun longCodeFencePreviewDoesNotCrash() {
        val content = "```\nCode start\n" + "x\n".repeat(4_000) + "Code end\n```"
        checkTimeline(content, "Code start")
    }

    @Test
    fun longListPreviewDoesNotCrash() {
        val content = "- **List start**\n" + "- x\n".repeat(2_000) + "- List end"
        checkTimeline(content, "List start")
    }

    @Test
    fun longTableCellPreviewDoesNotCrash() {
        // Reproduced a 262190-pixel width overflow with the original renderer.
        val content = "| **Table start** |\n| --- |\n| " + "W".repeat(8_000) + " |"
        checkTimeline(content, "Table start", Density(3f, fontScale = 1f))
    }

    @Test
    fun longBlockquoteOnHighDensityDisplayDoesNotCrash() {
        // The original intrinsic-height layout requested 336331 pixels here.
        val content = "> **Quote start**\n" + "> x\n".repeat(2_000) + "> Quote end"
        checkTimeline(content, "Quote start", Density(4f, fontScale = 2f))
    }

    @Test
    fun longTableCellRetainsFullContentInDetail() {
        val word = "W".repeat(8_000)
        val content = "| **Table start** |\n| --- |\n| $word |"
        compose.setContent {
            MemoTestTimeline(content, isDetailView = true)
        }
        awaitRenderedStart("Table start")
        val cell = tableCell(word)
        val layouts = mutableListOf<TextLayoutResult>()
        cell.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(word, layouts.single().layoutInput.text.text.trim())
        assertTrue("Long cells should wrap without losing content", layouts.single().lineCount > 1)
        val scroll = horizontalTable().fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("Long cells should still allow sideways scrolling", scroll.maxValue() > 0)
    }

    @Test
    fun wideTableCanScrollToLastColumn() {
        val content = """
            | First long column | Second long column | Third long column | Last long column |
            | --- | --- | --- | --- |
            | one | two | three | four |
        """.trimIndent()
        compose.setContent {
            MemosMTheme(dynamicColor = false) {
                NativeComposeMarkdown(
                    modifier = Modifier.width(220.dp).testTag("table"),
                    content = content,
                    headerScale = 1f
                )
            }
        }
        awaitRenderedStart("First long column")
        tableCell("Last long column").assertIsNotDisplayed()
        repeat(3) { horizontalTable().performTouchInput { swipeLeft() } }
        tableCell("Last long column").assertIsDisplayed()
        val bitmap = compose.onNodeWithTag("table").captureToImage().asAndroidBitmap()
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(cacheDir, "long-memo-wide-table.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun shortTableDoesNotAcquireUnnecessaryScrolling() {
        compose.setContent {
            MemosMTheme(dynamicColor = false) {
                NativeComposeMarkdown(
                    modifier = Modifier.width(240.dp),
                    content = "| **Small** | B |\n| --- | --- |\n| one | two |",
                    headerScale = 1f
                )
            }
        }
        awaitRenderedStart("Small")
        val scroll = horizontalTable().fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals(0f, scroll.maxValue(), 0f)
        tableCell("two").assertIsDisplayed()
    }

    private fun horizontalTable() = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
    )

    // The sizing pass also composes cell text, but those nodes are never placed.
    private val isPlaced = SemanticsMatcher("Placed cell") { it.layoutInfo.isPlaced }

    private fun tableCell(text: String) = compose.onNode(hasText(text, substring = true) and isPlaced)

    private fun awaitRenderedStart(renderedStart: String) {
        // The raw placeholder has Markdown delimiters before the start text.
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(renderedStart, substring = true)
                .fetchSemanticsNodes().any { node ->
                    node.layoutInfo.isPlaced && node.config[SemanticsProperties.Text].any {
                        it.text.trimStart().startsWith(renderedStart)
                    }
                }
        }
    }

    private fun checkTimeline(
        content: String,
        renderedStart: String,
        density: Density = Density(3f, fontScale = 2f)
    ) {
        assertTrue(content.toByteArray(Charsets.UTF_8).size <= 8192)

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                MemoTestTimeline(content)
            }
        }

        compose.checkMemoWhileScrolling {
            awaitRenderedStart(renderedStart)
            tableCell(renderedStart).assertIsDisplayed()
        }
    }
}

@Composable
internal fun MemoTestTimeline(content: String, isDetailView: Boolean = false) {
    MemosMTheme(dynamicColor = false) {
        LazyColumn(Modifier.width(240.dp).testTag("timeline")) {
            item {
                MemoItem(
                    modifier = Modifier.testTag("long_memo"),
                    memo = Memo(content = content),
                    token = "",
                    headerScale = 1f,
                    maxHeight = if (isDetailView) Dp.Unspecified else 400.dp,
                    isDetailView = isDetailView
                )
            }
            items((1..30).toList()) { index ->
                Text(
                    text = "Neighbor $index",
                    modifier = Modifier.height(64.dp).testTag("neighbor_$index")
                )
            }
        }
    }
}

internal fun ComposeTestRule.checkMemoWhileScrolling(assertRendered: () -> Unit) {
    repeat(3) {
        onNodeWithTag("timeline").performScrollToIndex(0)
        assertRendered()
        onNodeWithTag("timeline").performScrollToIndex(30)
        onNodeWithTag("neighbor_30").assertIsDisplayed()
    }
}
