package org.example.memosm.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import org.example.memosm.R
import org.example.memosm.ui.theme.MemosMTheme
import org.example.memosm.viewmodel.MemosViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.androidx.compose.koinViewModel

class MemoSearchBarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun expandedSearchAllowsAdjacentPaneInteractionAndPreservesQuery() {
        val expanded = mutableStateOf(false)
        var detailActions = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MemosMTheme {
                Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        MemoSearchBar(
                            viewModel = koinViewModel<MemosViewModel>(),
                            placeholder = "Search left pane",
                            localMemos = emptyList(),
                            onMemoClick = {},
                            onExpandedChange = { expanded.value = it }
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Button(onClick = { detailActions++ }) {
                            Text("Detail action")
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Search left pane").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Keep this query")
        // A physical tap verifies that expanded search does not intercept the other pane.
        compose.onNodeWithText("Detail action").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, detailActions)
            assertTrue(expanded.value)
        }
        compose.onNode(hasSetTextAction()).assertTextEquals("Keep this query")

        compose.onNodeWithContentDescription(context.getString(R.string.memo_detail_back))
            .performClick()
        compose.runOnIdle { assertFalse(expanded.value) }
        compose.onNode(hasSetTextAction()).assertTextEquals("Keep this query")
    }
}
