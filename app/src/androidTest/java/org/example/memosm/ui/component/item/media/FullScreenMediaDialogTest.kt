package org.example.memosm.ui.component.item.media

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.espresso.Espresso.pressBack
import org.example.memosm.model.Attachment
import org.example.memosm.ui.theme.MemosMTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FullScreenMediaDialogTest {
    @get:Rule val compose = createComposeRule()
    private var dismissals = 0
    private lateinit var dispatcher: OnBackPressedDispatcher

    private fun showViewer(zoomable: Boolean = false) {
        compose.setContent {
            MemosMTheme {
                var showing by remember { mutableStateOf(true) }
                if (showing) {
                    FullScreenMediaDialog(
                        onDismiss = { dismissals++; showing = false },
                        infoContent = { Text("Attachment details") }
                    ) {
                        val owner = LocalOnBackPressedDispatcherOwner.current!!
                        SideEffect { dispatcher = owner.onBackPressedDispatcher }
                        Box(Modifier.fillMaxSize().testTag("media").zoomable(zoomable, doubleTapZoom = true)) {
                            Text("Viewer content")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun downwardSwipeDismissesOnce() {
        showViewer()
        compose.onNodeWithTag("attachment_viewer").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, dismissals) }
        compose.onNodeWithText("Viewer content").assertDoesNotExist()
    }

    @Test
    fun upwardSwipeRevealsDetailsAndShortDragRestoresViewer() {
        showViewer()
        compose.onNodeWithTag("attachment_viewer").performTouchInput {
            swipe(start = center, end = center + Offset(0f, 25f))
        }
        compose.runOnIdle { assertEquals(0, dismissals) }
        compose.onNodeWithTag("attachment_viewer").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(0, dismissals) }
        compose.onNodeWithText("Attachment details").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").assertDoesNotExist()
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithTag("attachment_viewer").performTouchInput { swipeDown() }
        compose.onNodeWithText("Attachment details").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, dismissals) }
    }

    @Test
    fun cancelledTouchDragDoesNotNavigate() {
        showViewer()
        compose.onNodeWithTag("attachment_viewer").performTouchInput {
            down(center)
            moveTo(center + Offset(0f, height * 0.4f))
            cancel()
        }
        compose.runOnIdle { assertEquals(0, dismissals) }
        compose.onNodeWithText("Viewer content").assertIsDisplayed()
    }

    @Test
    fun zoomedPanningDoesNotDismissOrOpenInfo() {
        showViewer(zoomable = true)
        compose.onNodeWithTag("media").performTouchInput { doubleClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("attachment_viewer").performTouchInput { swipeDown() }
        compose.onNodeWithTag("attachment_viewer").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(0, dismissals) }
        compose.onNodeWithText("Attachment details").assertDoesNotExist()
        compose.onNodeWithTag("media").performTouchInput { doubleClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("attachment_viewer").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun pinchDoesNotBecomeViewerSwipe() {
        showViewer(zoomable = true)
        compose.onNodeWithTag("media").performTouchInput {
            down(0, center - Offset(40f, 0f))
            down(1, center + Offset(40f, 0f))
            moveTo(0, center - Offset(120f, 0f))
            moveTo(1, center + Offset(120f, 0f))
            up(0)
            up(1)
        }
        compose.runOnIdle { assertEquals(0, dismissals) }
    }

    @Test
    fun predictiveBackCancellationRestoresViewerAndCompletionDismissesOnce() {
        showViewer()
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 200f, 0.6f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, dismissals) }
        compose.onNodeWithText("Viewer content").assertIsDisplayed()
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_RIGHT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 200f, 0.8f, BackEventCompat.EDGE_RIGHT))
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun pagingUpdatesInfoAndBackClosesInfoBeforeReturningToCurrentThumbnail() {
        var returnedAttachment: Attachment? = null
        compose.setContent {
            MemosMTheme {
                FullScreenAttachmentViewer(
                    attachments = listOf(
                        Attachment(name = "attachments/first", filename = "first.pdf", type = "application/pdf"),
                        Attachment(name = "attachments/second", filename = "second.pdf", type = "application/pdf")
                    ),
                    initialIndex = 0, token = null, hostUrl = "https://example.com",
                    onDismiss = { dismissals++ },
                    originBounds = { attachment ->
                        returnedAttachment = attachment
                        Rect(20f, 40f, 120f, 140f)
                    }
                )
            }
        }
        compose.onNodeWithTag("attachment_viewer").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("attachment_viewer").performTouchInput { swipeUp() }
        compose.onNodeWithText("attachments/second").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("ID").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, dismissals) }
        compose.onNodeWithTag("attachment_viewer").performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals("attachments/second", returnedAttachment?.name)
        }
    }
}
