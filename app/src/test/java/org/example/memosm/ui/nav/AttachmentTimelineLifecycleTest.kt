package org.example.memosm.ui.nav

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.Recomposer
import kotlinx.coroutines.test.runTest
import org.example.memosm.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AttachmentTimelineLifecycleTest {
    @Test fun refreshReplacesRowsInTheSameCompositionWithoutWaitingForEffects() =
        runTest {
            val recomposer = Recomposer(coroutineContext)
            val composition =
                ControlledComposition(
                    object : AbstractApplier<Unit>(Unit) {
                        override fun insertTopDown(
                            index: Int,
                            instance: Unit,
                        ) {}

                        override fun insertBottomUp(
                            index: Int,
                            instance: Unit,
                        ) {}

                        override fun remove(
                            index: Int,
                            count: Int,
                        ) {}

                        override fun move(
                            from: Int,
                            to: Int,
                            count: Int,
                        ) {}

                        override fun onClear() {}
                    },
                    recomposer,
                )
            val listState = LazyListState()
            val cached = (0..59).map { Attachment(name = "attachments/$it", filename = "$it.jpg", type = "image/jpeg") }
            var days = attachmentDays(cached, ZoneId.of("UTC"))
            var entries = emptyList<AttachmentTimelineEntry>()

            fun shown() = entries.filterIsInstance<AttachmentTimelineEntry.Row>().flatMap { row -> row.attachments.map { it.attachment } }
            try {
                composition.composeContent {
                    entries = rememberTimelineEntries(listState, days, emptyMap(), 400f, 120f)
                }
                composition.applyChanges()
                assertEquals(cached, shown())

                // Remote reconciliation may shrink or clear a cached gallery. No old row may
                // survive even for the composition before LaunchedEffect runs.
                for (replacement in listOf(listOf(cached.last().copy(filename = "updated.jpg")), emptyList())) {
                    days = attachmentDays(replacement, ZoneId.of("UTC"))
                    composition.invalidateAll()
                    assertTrue(composition.recompose())
                    composition.applyChanges()
                    assertEquals(replacement, shown())
                }
            } finally {
                composition.dispose()
                recomposer.cancel()
            }
        }
}
