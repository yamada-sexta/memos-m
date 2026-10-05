package org.example.memosm.ui.nav

import org.example.memosm.model.Attachment
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.time.Instant

class AttachmentTimelineTest {
    private val utc = ZoneId.of("UTC")
    private fun attachment(id: String, time: String? = "2026-10-04T12:00:00Z") = Attachment(
        name = "attachments/$id", filename = "$id.jpg", type = "image/jpeg",
        createTime = time?.let(Instant::parse)
    )

    @Test
    fun `days sort newest first and unknown timestamps last without changing the source list`() {
        val input = listOf(attachment("unknown", null), attachment("older", "2025-12-31T12:00:00Z"), attachment("newest"))
        val days = attachmentDays(input, utc)
        assertEquals(listOf(LocalDate.of(2026, 10, 4), LocalDate.of(2025, 12, 31), null), days.map { it.date })
        assertEquals(listOf("newest.jpg", "older.jpg", "unknown.jpg"), days.flatMap { it.attachments }.map { it.attachment.filename })
        assertEquals("unknown.jpg", input.first().filename)
        assertEquals(listOf(0, 1, 2), days.flatMap { it.attachments }.map { it.index })
    }

    @Test
    fun `local date accounts for midnight and daylight saving transitions`() {
        val zone = ZoneId.of("America/Chicago")
        val days = attachmentDays(listOf(
            attachment("midnight", "2026-01-01T01:00:00Z"),
            attachment("beforeDST", "2026-03-08T07:30:00Z"),
            attachment("afterDST", "2026-03-08T08:30:00Z")
        ), zone)
        assertEquals(listOf(LocalDate.of(2026, 3, 8), LocalDate.of(2025, 12, 31)), days.map { it.date })
        assertEquals(2, days.first().attachments.size)
    }

    @Test
    fun `month headings distinguish the same month in different years`() {
        val entries = attachmentTimelineEntries(attachmentDays(listOf(
            attachment("new", "2026-10-04T12:00:00Z"),
            attachment("nextDay", "2026-10-03T12:00:00Z"),
            attachment("old", "2025-10-04T12:00:00Z"),
            attachment("unknown", null)
        ), utc), emptyMap(), 400f, 120f)
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2025, 10)),
            entries.filterIsInstance<AttachmentTimelineEntry.Month>().map { it.month })
        assertEquals(4, entries.filterIsInstance<AttachmentTimelineEntry.Day>().size)
        assertNull(entries.filterIsInstance<AttachmentTimelineEntry.Day>().last().date)
    }

    @Test
    fun `merged pages share one date header and preserve viewer indices`() {
        val pageOne = (0..6).map { attachment("$it") }
        val merged = attachmentDays(pageOne + (7..13).map { attachment("$it") }, utc)
        val entries = attachmentTimelineEntries(merged, emptyMap(), 400f, 120f)
        assertEquals(1, entries.filterIsInstance<AttachmentTimelineEntry.Day>().size)
        val items = entries.filterIsInstance<AttachmentTimelineEntry.Row>().flatMap { it.attachments }
        assertEquals((0..13).toList(), items.map { it.index })
        assertEquals(merged.flatMap { it.attachments }, items)
        assertEquals(entries.size, entries.map { it.key }.distinct().size)
    }

    @Test
    fun `equal timestamps preserve input order and repeated filenames have unique keys`() {
        val a = attachment("a").copy(name = null, filename = "same.jpg")
        val b = a.copy(externalLink = "https://example.com/other.jpg")
        val items = attachmentDays(listOf(a, b, a), utc).single().attachments
        assertEquals(listOf(a, b, a), items.map { it.attachment })
        assertEquals(3, items.map { it.key }.distinct().size)
    }

    @Test
    fun `mixed aspect ratios preserve proportions and completed rows fill the available width`() {
        val ratios = listOf(2f, 0.5f, 1f, 1.5f, 0.75f, 3f, 1f, 0.5f)
        val rows = justifiedRows(ratios, 400f, 120f)
        assertEquals(ratios.indices.toList(), rows.flatMap { it.indices.toList() })
        rows.forEach { row ->
            row.indices.forEachIndexed { column, index ->
                assertEquals(ratios[index], row.widths[column] / row.height, 0.0001f)
            }
            assertTrue(row.height.isFinite() && row.height > 0f)
            assertTrue(row.widths.all { it.isFinite() && it > 0f })
        }
        rows.dropLast(1).forEach {
            assertEquals(400f, it.widths.sum() + 2f * (it.widths.size - 1), 0.001f)
        }
    }

    @Test
    fun `break is chosen on the closer side of target height`() {
        val rows = justifiedRows(List(6) { 1f }, 400f, 150f)
        assertEquals(0..2, rows.first().indices)
        assertEquals(132f, rows.first().height, 0.001f)
    }

    @Test
    fun `incomplete last row stays at target height and aligned to the start`() {
        val row = justifiedRows(listOf(1f, 0.5f), 400f, 100f).single()
        assertEquals(100f, row.height, 0f)
        assertEquals(listOf(100f, 50f), row.widths)
    }

    @Test
    fun `single wide image shrinks to fit and single portrait is not enlarged`() {
        val wide = justifiedRows(listOf(4f), 300f, 120f).single()
        assertEquals(300f, wide.widths.single(), 0f)
        assertEquals(75f, wide.height, 0f)
        val portrait = justifiedRows(listOf(0.5f), 300f, 120f).single()
        assertEquals(60f, portrait.widths.single(), 0f)
        assertEquals(120f, portrait.height, 0f)
    }

    @Test
    fun `invalid ratios become squares and narrow viewports remain valid`() {
        val rows = justifiedRows(listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f), 1f, 120f)
        assertEquals(4, rows.size)
        rows.forEach {
            assertEquals(1f, it.height, 0f)
            assertEquals(1f, it.widths.single(), 0f)
        }
        assertTrue(justifiedRows(listOf(1f), 0f, 120f).isEmpty())
        assertTrue(justifiedRows(listOf(1f), Float.NaN, 120f).isEmpty())
        assertTrue(justifiedRows(listOf(1f), 400f, 0f).isEmpty())
        assertTrue(justifiedRows(emptyList(), 400f, 120f).isEmpty())
    }

    @Test
    fun `zoom and width changes regroup rows and retain attachment anchor mapping`() {
        val days = attachmentDays((0..19).map { attachment("$it") }, utc)
        val dense = attachmentTimelineEntries(days, emptyMap(), 400f, 100f)
        val zoomed = attachmentTimelineEntries(days, emptyMap(), 400f, 300f)
        val resized = attachmentTimelineEntries(days, emptyMap(), 800f, 100f)
        assertTrue(zoomed.size > dense.size)
        assertTrue(resized.size < dense.size)
        val anchor = days.single().attachments[8].key
        for (entries in listOf(dense, zoomed, resized)) {
            val row = entries[timelineRowIndex(entries, anchor)!!] as AttachmentTimelineEntry.Row
            assertTrue(row.attachments.any { it.key == anchor })
        }
        assertNull(timelineRowIndex(dense, "missing"))
    }

    @Test
    fun `loaded dimensions replace square placeholders without changing viewer order or keys`() {
        val days = attachmentDays((0..7).map { attachment("$it") }, utc)
        val original = attachmentTimelineEntries(days, emptyMap(), 400f, 120f)
        val dimensions = days.single().attachments.associate { it.attachment.identityKey to 2f }
        val loaded = attachmentTimelineEntries(days, dimensions, 400f, 120f)
        fun items(entries: List<AttachmentTimelineEntry>) = entries.filterIsInstance<AttachmentTimelineEntry.Row>().flatMap { it.attachments }
        assertEquals(items(original), items(loaded))
        assertNotEquals(original.size, loaded.size)
        loaded.filterIsInstance<AttachmentTimelineEntry.Row>().forEach { row ->
            row.geometry.widths.forEach { width -> assertEquals(2f, width / row.geometry.height, 0.0001f) }
        }
    }

    @Test
    fun `audio and documents stay in the thumbnail grid with chronological viewer indices`() {
        val files = (0..4).map { attachment("$it") }
        val input = listOf(files[0], files[1].copy(type = "audio/mpeg"), files[2],
            files[3].copy(mimeType = "AUDIO/MP4"), files[4].copy(type = "application/pdf"))
        val days = attachmentDays(input, utc)
        val entries = attachmentTimelineEntries(days, emptyMap(), 400f, 120f)
        val rows = entries.filterIsInstance<AttachmentTimelineEntry.Row>()
        assertEquals(listOf(0, 1, 2, 3, 4), rows.flatMap { row -> row.attachments.map { it.index } })
        assertEquals(2, rows.size)
        assertEquals(1, entries.filterIsInstance<AttachmentTimelineEntry.Day>().size)
        val key = days.single().attachments[1].key
        assertTrue((entries[timelineRowIndex(entries, key)!!] as AttachmentTimelineEntry.Row).attachments.size > 1)
        rows.forEach { row ->
            row.geometry.widths.forEach { width -> assertEquals(row.geometry.height, width, 0.001f) }
        }
    }

    @Test
    fun `audio uses the same grid density and zoom as other files without stretching incomplete rows`() {
        val input = (0..6).map { attachment("$it").copy(type = "audio/flac") }
        val days = attachmentDays(input, utc)
        val small = attachmentTimelineEntries(days, emptyMap(), 400f, 100f)
        val large = attachmentTimelineEntries(days, emptyMap(), 400f, 600f)
        assertTrue(small.size < large.size)
        val smallRows = small.filterIsInstance<AttachmentTimelineEntry.Row>()
        assertTrue(smallRows.first().attachments.size > 1)
        assertTrue(smallRows.last().geometry.widths.sum() + 2f * (smallRows.last().attachments.size - 1) < 400f)
        assertEquals((0..6).toList(), smallRows.flatMap { row -> row.attachments.map { it.index } })
        assertTrue(shouldLoadTimelinePage(smallRows.last().lastAttachmentIndex(), 7, false, "next"))
    }

    @Test
    fun `pagination uses attachment range and respects loading and exhausted tokens`() {
        assertFalse(shouldLoadTimelinePage(14, 20, false, "next"))
        assertTrue(shouldLoadTimelinePage(15, 20, false, "next"))
        assertTrue(shouldLoadTimelinePage(0, 3, false, "next"))
        assertFalse(shouldLoadTimelinePage(19, 20, true, "next"))
        assertFalse(shouldLoadTimelinePage(19, 20, false, null))
        assertFalse(shouldLoadTimelinePage(19, 20, false, " "))
        assertFalse(shouldLoadTimelinePage(null, 20, false, "next"))
        assertFalse(shouldLoadTimelinePage(null, 0, false, "next"))
        // Appending a page moves the loading threshold past the same visible attachment.
        assertFalse(shouldLoadTimelinePage(19, 40, false, "next-page"))
    }
}
