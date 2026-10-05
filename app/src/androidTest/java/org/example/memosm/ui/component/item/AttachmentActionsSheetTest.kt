package org.example.memosm.ui.component.item

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import org.example.memosm.model.Attachment
import org.example.memosm.ui.theme.MemosMTheme
import org.junit.Rule
import org.junit.Test

class AttachmentActionsSheetTest {
    @get:Rule val compose = createComposeRule()

    private fun showCard(
        attachment: Attachment? = null, showActions: Boolean = true,
        gallery: Boolean = false, onClick: (() -> Unit)? = null
    ) {
        compose.setContent {
            MemosMTheme {
                AttachmentCard(
                    attachment = attachment, token = null, hostUrl = "https://example.com",
                    modifier = Modifier.size(240.dp), showActions = showActions,
                    gallery = gallery, onClick = onClick
                )
            }
        }
    }

    @Test
    fun infoAndDownloadReplaceSheetAndActionsCanBeReopened() {
        showCard(Attachment(name = "attachments/test", filename = "test.pdf", type = "application/pdf", size = "1234"))
        compose.onNodeWithContentDescription("More").performClick()
        listOf("Attachment Info", "Download", "Open on Web", "Share").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Attachment Info").performScrollTo().performClick()
        compose.onNodeWithText("Filename").assertIsDisplayed()
        compose.onNodeWithText("attachments/test").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open on Web").assertDoesNotExist()
        compose.onNodeWithText("Close").assertDoesNotExist()
        pressBack()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Download").performClick()
        compose.onNodeWithText("Attachment Info").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        pressBack()
        compose.onNodeWithText("Attachment Info").assertDoesNotExist()
    }

    @Test
    fun missingUrlOmitsWebAndShareActionsAndMissingMetadataIsOmitted() {
        showCard()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Open on Web").assertDoesNotExist()
        compose.onNodeWithText("Share").assertDoesNotExist()
        compose.onNodeWithText("Attachment Info").performClick()
        compose.onNodeWithText("Filename").assertIsDisplayed()
        listOf("Type", "Size", "Created", "ID").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test
    fun hiddenActionsHaveNoOverflowButton() {
        showCard(showActions = false)
        compose.onNodeWithContentDescription("More").assertDoesNotExist()
    }

    @Test
    fun galleryTapOpensAttachmentAndLongPressReusesTheActionsSheet() {
        var opened = 0
        showCard(
            Attachment(name = "attachments/test", filename = "test.pdf", type = "application/pdf"),
            gallery = true, onClick = { opened++ }
        )
        compose.onNodeWithContentDescription("test.pdf").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, opened) }
        compose.onNodeWithContentDescription("test.pdf").performTouchInput { longClick() }
        compose.runOnIdle { org.junit.Assert.assertEquals(1, opened) }
        compose.onNodeWithText("Attachment Info").performClick()
        compose.onNodeWithText("Filename").assertIsDisplayed()
        pressBack()
        compose.onNodeWithContentDescription("test.pdf").performTouchInput { longClick() }
        compose.onNodeWithText("Download").performClick()
        compose.onNodeWithText("Cancel").performClick()
    }

}
