package org.example.memosm.ui.component.item.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import org.example.memosm.data.linkpreview.LinkPreviewRepository
import org.example.memosm.model.LinkMetadata
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class LinkPreviewTest {
    @get:Rule val compose = createComposeRule()
    private val url = "https://example.com"

    private fun awaitTitle(title: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun cardOpensOriginalLinkAndToggleRestoresText() {
        val enabled = mutableStateOf(true)
        var calls = 0
        var opened: String? = null
        compose.setContent {
            val scope = rememberCoroutineScope()
            val repository = remember {
                LinkPreviewRepository(scope, fetch = { calls++; LinkMetadata(url, "Example title", "Example description") })
            }
            MaterialTheme {
                CompositionLocalProvider(
                    LocalLinkPreviews provides if (enabled.value) LinkPreviewEnvironment(repository, true) else null,
                    LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened = uri } }
                ) { NativeComposeMarkdown(content = url, headerScale = 1f, selectable = true) }
            }
        }
        awaitTitle("Example title")
        compose.onNodeWithText("Example title").performClick()
        compose.runOnIdle { assertEquals(url, opened); enabled.value = false }
        compose.onNodeWithText("Example title").assertDoesNotExist()
        compose.onNodeWithText(url).assertIsDisplayed()
        compose.runOnIdle { enabled.value = true }
        awaitTitle("Example title")
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun disabledPreviewsNeverFetch() {
        val enabled = mutableStateOf(false)
        var calls = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            val repository = remember { LinkPreviewRepository(scope, fetch = { calls++; LinkMetadata(url, "Title") }) }
            MaterialTheme {
                CompositionLocalProvider(LocalLinkPreviews provides if (enabled.value) LinkPreviewEnvironment(repository, true) else null) {
                    NativeComposeMarkdown(content = url, headerScale = 1f)
                }
            }
        }
        compose.onNodeWithText(url).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.runOnIdle { enabled.value = true }
        awaitTitle("Title")
    }

    @Test
    fun failedMetadataKeepsClickableLink() {
        var calls = 0
        var opened: String? = null
        compose.setContent {
            val scope = rememberCoroutineScope()
            val repository = remember { LinkPreviewRepository(scope, fetch = { calls++; throw IOException() }) }
            MaterialTheme {
                CompositionLocalProvider(
                    LocalLinkPreviews provides LinkPreviewEnvironment(repository, true),
                    LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened = uri } }
                ) { NativeComposeMarkdown(content = url, headerScale = 1f) }
            }
        }
        compose.waitUntil(5_000) { calls == 1 }
        compose.onNodeWithText(url).performClick()
        compose.runOnIdle { assertEquals(url, opened) }
    }

    @Test
    fun imageOnlyMetadataKeepsPlainLink() {
        var calls = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            val repository = remember {
                LinkPreviewRepository(scope, fetch = { calls++; LinkMetadata(url, image = "https://example.com/image.png") })
            }
            MaterialTheme {
                CompositionLocalProvider(LocalLinkPreviews provides LinkPreviewEnvironment(repository, true)) {
                    NativeComposeMarkdown(content = url, headerScale = 1f)
                }
            }
        }
        compose.waitUntil(5_000) { calls == 1 }
        compose.onNodeWithText(url).assertIsDisplayed()
    }

    @Test
    fun failedThumbnailKeepsTextCard() {
        compose.setContent {
            MaterialTheme {
                LinkPreviewCard(url, LinkMetadata(url, "Example title", image = "https://127.0.0.1:1/image.png"))
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Example title").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("link_preview_thumbnail").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Example title").assertIsDisplayed()
    }
}
