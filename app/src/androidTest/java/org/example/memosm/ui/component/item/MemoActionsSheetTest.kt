package org.example.memosm.ui.component.item

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoState
import org.example.memosm.ui.theme.MemosMTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class MemoActionsSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun normalMemoShowsAvailableActionsAndEditDismissesSheet() {
        var edited = false
        compose.setContent {
            MemosMTheme {
                MemoItem(
                    memo = Memo(name = "memos/test", content = "A memo", state = MemoState.NORMAL),
                    token = "", hostUrl = "https://example.com", headerScale = 1f,
                    onEdit = { edited = true }, onPin = {}, onArchive = {}, onUnarchive = {},
                    onDelete = {}, onUpsertReaction = {}
                )
            }
        }
        compose.onNodeWithContentDescription("More").performClick()
        listOf("Open on Web", "Show Raw Text", "Add Reaction", "Edit", "Pin", "Archive", "Delete").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        File(instrumentation.targetContext.cacheDir, "memo-actions-sheet.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Unarchive").assertDoesNotExist()
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(edited) }
        compose.onNodeWithText("Show Raw Text").assertDoesNotExist()
    }

    @Test
    fun archivedMemoOnlyOffersUnarchiveAndInvokesIt() {
        var restored = false
        compose.setContent {
            MemosMTheme {
                MemoItem(
                    memo = Memo(name = "memos/test", content = "Archived memo", state = MemoState.ARCHIVED),
                    token = "", headerScale = 1f, onPin = {}, onArchive = {},
                    onUnarchive = { restored = true }, onDelete = {}
                )
            }
        }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Archive").assertDoesNotExist()
        compose.onNodeWithText("Pin").assertDoesNotExist()
        compose.onNodeWithText("Open on Web").assertDoesNotExist()
        compose.onNodeWithText("Unarchive").performClick()
        compose.runOnIdle { assertTrue(restored) }
        compose.onNodeWithText("Show Raw Text").assertDoesNotExist()
    }

    @Test
    fun rawTextActionOpensDialogAndSheetCanBeReopened() {
        compose.setContent {
            MemosMTheme {
                MemoItem(
                    memo = Memo(name = "memos/test", content = "**Memo content**", state = MemoState.NORMAL),
                    token = "", headerScale = 1f
                )
            }
        }
        compose.onNodeWithContentDescription("More").performClick()
        pressBack()
        compose.onNodeWithText("Show Raw Text").assertDoesNotExist()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.onNodeWithText("Show Raw Text").performClick()
        compose.onNodeWithText("Raw Text").assertIsDisplayed()
        compose.onNodeWithText("**Memo content**").assertIsDisplayed()
        compose.onNodeWithText("Show Raw Text").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Show Raw Text").assertIsDisplayed()
    }
}
