package org.example.memosm.ui.component.item

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import org.example.memosm.R
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@OptIn(DelicateCoilApi::class)
class QuotedMarkdownImageTest {
    private val compose = createComposeRule()
    private val imageUrl = "https://example.invalid/file/attachments/quoted/quote.png"
    private val imageReady = CompletableDeferred<Unit>()
    private val requestsStarted = AtomicInteger()
    private val requestsCompleted = AtomicInteger()
    private var failImage = false
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val imageLoaderRule = object : ExternalResource() {
        private lateinit var originalLoader: ImageLoader
        private lateinit var testLoader: ImageLoader

        override fun before() {
            originalLoader = SingletonImageLoader.get(context)
            val image = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).asImage()
            testLoader = ImageLoader.Builder(context)
                .components {
                    add(Interceptor { chain ->
                        check(chain.request.data == imageUrl)
                        requestsStarted.incrementAndGet()
                        imageReady.await()
                        if (failImage) {
                            ErrorResult(null, chain.request, IOException("Test image unavailable"))
                        } else {
                            SuccessResult(image, chain.request)
                        }
                    })
                }
                .eventListener(object : EventListener() {
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                        requestsCompleted.incrementAndGet()
                    }

                    override fun onError(request: ImageRequest, result: ErrorResult) {
                        requestsCompleted.incrementAndGet()
                    }
                })
                .build()
            SingletonImageLoader.setUnsafe(testLoader)
        }

        override fun after() {
            // The outer rule restores the singleton after Compose disposes its images.
            SingletonImageLoader.setUnsafe(originalLoader)
            testLoader.shutdown()
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(imageLoaderRule).around(compose)

    @Test
    fun imageOnlyQuoteSurvivesScrollingWhileLoadingAndAfterSuccess() {
        showMemo("> ![]($imageUrl)")
        compose.checkMemoWhileScrolling {
            awaitImage()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .assertIsDisplayed()
        }
        imageReady.complete(Unit)
        checkLoadedImageWhileScrolling()
    }

    @Test
    fun nestedQuotedImageSurvivesScrolling() {
        imageReady.complete(Unit)
        showMemo("> > ![]($imageUrl)")
        checkLoadedImageWhileScrolling()
    }

    @Test
    fun referenceStyleQuotedImageSurvivesScrolling() {
        imageReady.complete(Unit)
        showMemo("> ![Quoted image][photo]\n\n[photo]: $imageUrl")
        checkLoadedImageWhileScrolling()
    }

    @Test
    fun quotedImagePreservesSurroundingText() {
        imageReady.complete(Unit)
        showMemo("> Before\n>\n> ![]($imageUrl)\n>\n> After")
        checkLoadedImageWhileScrolling {
            compose.onNodeWithText("Before", substring = true).assertIsDisplayed()
            compose.onNodeWithText("After", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun failedQuotedImageShowsFallbackAndSurvivesScrolling() {
        failImage = true
        imageReady.complete(Unit)
        showMemo("> ![]($imageUrl)")
        compose.checkMemoWhileScrolling {
            awaitImage()
            compose.waitUntil(5_000) { requestsCompleted.get() > 0 }
            compose.onNodeWithContentDescription(context.getString(R.string.attachments_error))
                .assertIsDisplayed()
        }
    }

    @Test
    fun quotedImageInSelectableDetailOpensViewer() {
        imageReady.complete(Unit)
        showMemo("> ![]($imageUrl)", isDetailView = true)
        awaitImage()
        compose.waitUntil(5_000) { requestsCompleted.get() > 0 }
        imageNode().performClick()
        compose.waitUntil(5_000) { requestsCompleted.get() >= 2 }
        compose.onNodeWithTag("attachment_viewer").assertIsDisplayed()
    }

    private fun showMemo(content: String, isDetailView: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f, fontScale = 2f)) {
                MemoTestTimeline(content, isDetailView)
            }
        }
    }

    private fun imageNode() = compose.onNodeWithContentDescription(imageUrl, useUnmergedTree = true)

    private fun awaitImage() {
        // Neither the raw Markdown placeholder nor an unresolved reference can satisfy this.
        compose.waitUntil(5_000) {
            requestsStarted.get() > 0 && compose.onAllNodesWithContentDescription(
                imageUrl, useUnmergedTree = true
            ).fetchSemanticsNodes().any { it.layoutInfo.isPlaced }
        }
        imageNode().assertIsDisplayed()
    }

    private fun checkLoadedImageWhileScrolling(assertContent: () -> Unit = {}) {
        compose.checkMemoWhileScrolling {
            awaitImage()
            compose.waitUntil(5_000) { requestsCompleted.get() > 0 }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.attachments_error))
                .assertDoesNotExist()
            assertContent()
        }
    }
}
