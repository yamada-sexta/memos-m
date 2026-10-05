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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.espresso.Espresso.pressBack
import org.example.memosm.model.Attachment
import org.example.memosm.ui.theme.MemosMTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                        Box(Modifier.fillMaxSize().testTag("media").zoomable(zoomable)) {
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
    fun downwardSwipeWithHorizontalDriftStillDismisses() {
        showViewer()
        compose.onNodeWithTag("attachment_viewer").performTouchInput {
            swipe(start = center - Offset(0f, height * 0.3f), end = center + Offset(width * 0.1f, height * 0.3f))
        }
        compose.runOnIdle { assertEquals(1, dismissals) }
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
    fun twoFingerZoomOutReturnsToFitWithoutNavigatingUntilNextGesture() {
        showViewer(zoomable = true)
        compose.onNodeWithTag("media").performTouchInput { doubleClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("media").performTouchInput {
            val start = center
            down(0, start - Offset(120f, 0f))
            down(1, start + Offset(120f, 0f))
            moveTo(0, start - Offset(43f, 0f))
            moveTo(1, start + Offset(43f, 0f))
            up(1)
            // A remaining finger must not turn the pinch into swipe-to-dismiss.
            moveTo(0, start + Offset(-43f, height * 0.3f))
            up(0)
        }
        compose.runOnIdle { assertEquals(0, dismissals) }
        compose.onNodeWithText("Attachment details").assertDoesNotExist()
        compose.onNodeWithTag("attachment_viewer").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun pinchingInPastFittedViewDismissesOnce() {
        showViewer(zoomable = true)
        compose.onNodeWithTag("media").performTouchInput {
            down(0, center - Offset(120f, 0f))
            down(1, center + Offset(120f, 0f))
            moveTo(0, center - Offset(60f, 0f))
            moveTo(1, center + Offset(60f, 0f))
            up(0)
            up(1)
        }
        compose.runOnIdle { assertEquals(1, dismissals) }
        compose.onNodeWithText("Viewer content").assertDoesNotExist()
    }

    @Test
    fun zoomedPanGlidesAfterReleaseAndNextTouchStopsMomentum() {
        showViewer(zoomable = true)
        val media = compose.onNodeWithTag("media")
        media.performTouchInput { doubleClick() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            media.performTouchInput {
                swipe(center - Offset(50f, 0f), center + Offset(50f, 0f), durationMillis = 150)
            }
            compose.mainClock.advanceTimeByFrame()
            val released = media.fetchSemanticsNode().positionInRoot.x
            compose.mainClock.advanceTimeBy(48)
            val gliding = media.fetchSemanticsNode().positionInRoot.x
            assertTrue("Pan should continue after finger release", gliding > released)

            media.performTouchInput { down(center) }
            compose.mainClock.advanceTimeByFrame()
            val stopped = media.fetchSemanticsNode().positionInRoot.x
            compose.mainClock.advanceTimeBy(160)
            assertEquals(stopped, media.fetchSemanticsNode().positionInRoot.x, 0.1f)
            media.performTouchInput { up() }
            compose.runOnIdle { assertEquals(0, dismissals) }
            compose.onNodeWithText("Attachment details").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun galleryImagesSupportDoubleTapZoomAndReturnToFit() {
        compose.setContent {
            MemosMTheme {
                FullScreenAttachmentViewer(
                    attachments = listOf(Attachment(
                        filename = "tiny.png", type = "image/png",
                        content = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jCz8AAAAASUVORK5CYII="
                    )),
                    initialIndex = 0, token = null, hostUrl = "",
                    onDismiss = { dismissals++ }
                )
            }
        }
        val image = compose.onNodeWithContentDescription("tiny.png")
        image.performTouchInput { doubleClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("attachment_viewer").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(0, dismissals) }
        image.performTouchInput { doubleClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("attachment_viewer").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, dismissals) }
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

    @Test
    fun horizontalPagingStaysHorizontalWhenFingerLaterMovesDownward() {
        var currentPage = 0
        compose.setContent {
            MemosMTheme {
                FullScreenAttachmentViewer(
                    attachments = listOf(
                        Attachment(filename = "first.pdf", type = "application/pdf"),
                        Attachment(filename = "second.pdf", type = "application/pdf")
                    ),
                    initialIndex = 0, token = null, hostUrl = "",
                    onDismiss = { dismissals++ }, onPageChanged = { currentPage = it }
                )
            }
        }
        compose.onNodeWithTag("attachment_viewer").performTouchInput {
            down(Offset(width * 0.8f, height * 0.4f))
            moveTo(Offset(width * 0.55f, height * 0.45f))
            moveTo(Offset(width * 0.2f, height * 0.8f))
            up()
        }
        compose.runOnIdle { assertEquals(0, dismissals); assertEquals(1, currentPage) }
        compose.onNodeWithText("ID").assertDoesNotExist()
        compose.onNodeWithText("Attachment Info").assertDoesNotExist()
    }

    @Test
    fun fullScreenOverflowOffersSharedActionsAndInfoRevealsInlineDetails() {
        compose.setContent {
            MemosMTheme {
                FullScreenAttachmentViewer(
                    attachments = listOf(Attachment(name = "attachments/menu", filename = "menu.pdf", type = "application/pdf")),
                    initialIndex = 0, token = null, hostUrl = "https://example.com",
                    onDismiss = { dismissals++ }
                )
            }
        }
        compose.onNodeWithContentDescription("More").performClick()
        listOf("Attachment Info", "Download", "Open on Web", "Share").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Attachment Info").performScrollTo().performClick()
        compose.onNodeWithText("attachments/menu").assertIsDisplayed()
        compose.onNodeWithText("Download").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close").assertDoesNotExist()
        pressBack()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Download").performClick()
        compose.onNodeWithText("Download File").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, dismissals) }
    }
}
