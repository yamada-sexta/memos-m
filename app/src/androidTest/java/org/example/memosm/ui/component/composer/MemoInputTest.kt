package org.example.memosm.ui.component.composer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.example.memosm.ui.theme.MemosMTheme
import org.junit.Rule
import org.junit.Test

class MemoInputTest {
    @get:Rule val compose = createComposeRule()

    private fun showComposer() {
        compose.setContent {
            MemosMTheme {
                var content by remember { mutableStateOf(TextFieldValue("- item", TextRange(6))) }
                Column(Modifier.fillMaxSize()) {
                    MemoInput(
                        modifier = Modifier.weight(1f), contentState = content,
                        onContentChange = { content = it }, availableTags = emptyMap()
                    )
                    Button(onClick = { content = TextFieldValue("replacement", TextRange(3)) }) {
                        Text("Load draft")
                    }
                }
            }
        }
    }

    @Test
    fun typingNewlineContinuesListAndKeepsEditingAfterPrefix() {
        showComposer()
        val input = compose.onNode(hasSetTextAction())
        input.performClick().performTextInput("\n")
        input.performTextInput("next")
        input.assertTextEquals("- item\n- next")
    }

    @Test
    fun loadingDraftUpdatesTextAndInsertionPosition() {
        showComposer()
        compose.onNodeWithText("Load draft").performClick()
        val input = compose.onNode(hasSetTextAction())
        input.assertTextEquals("replacement")
        input.performTextInput("X")
        input.assertTextEquals("repXlacement")
    }
}
