package org.example.memosm.ui.nav

import org.example.memosm.model.Attachment
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.min

internal data class TimelineAttachment(val attachment: Attachment, val key: String, val index: Int)

internal data class AttachmentDay(val date: LocalDate?, val attachments: List<TimelineAttachment>)

internal fun attachmentDays(attachments: List<Attachment>, zone: ZoneId): List<AttachmentDay> {
    // Stable sorting preserves the order of attachments with equal or absent timestamps.
    val ordered = attachments.sortedWith(compareByDescending { it.createTime })
    val occurrences = mutableMapOf<String, Int>()
    return ordered.mapIndexed { index, attachment ->
        val identity = attachment.identityKey
        val occurrence = occurrences.getOrDefault(identity, 0)
        occurrences[identity] = occurrence + 1
        TimelineAttachment(attachment, "$identity#$occurrence", index)
    }.groupBy {
        it.attachment.createTime?.let { time ->
            java.time.Instant.ofEpochMilli(time.toEpochMilliseconds()).atZone(zone).toLocalDate()
        }
    }.map { (date, items) -> AttachmentDay(date, items) }
}

internal data class JustifiedRow(val indices: IntRange, val height: Float, val widths: List<Float>)

/** Row geometry in dp. Completed rows fill the viewport without cropping image proportions. */
internal fun justifiedRows(
    ratios: List<Float>, width: Float, targetHeight: Float, spacing: Float = 2f
): List<JustifiedRow> {
    if (ratios.isEmpty() || !width.isFinite() || width <= 0f ||
        !targetHeight.isFinite() || targetHeight <= 0f) return emptyList()
    require(spacing.isFinite() && spacing >= 0f)
    val safeRatios = ratios.map { if (it.isFinite() && it > 0f) it.toDouble() else 1.0 }
    val rows = mutableListOf<JustifiedRow>()
    var start = 0
    while (start < ratios.size) {
        var end = start
        var sum = safeRatios[start]
        fun fittedHeight(count: Int, ratioSum: Double) =
            (width.toDouble() - spacing * (count - 1)) / ratioSum

        while (end < ratios.lastIndex && fittedHeight(end - start + 1, sum) > targetHeight) {
            val nextSum = sum + safeRatios[end + 1]
            val nextHeight = fittedHeight(end - start + 2, nextSum)
            // Never add a tile if the gaps alone would exhaust the row width.
            if (nextHeight <= 0.0) break
            val currentHeight = fittedHeight(end - start + 1, sum)
            if (nextHeight < targetHeight &&
                abs(currentHeight - targetHeight) <= abs(nextHeight - targetHeight)) break
            end++
            sum = nextSum
        }
        val fit = fittedHeight(end - start + 1, sum)
        val height = if (end == ratios.lastIndex) min(targetHeight.toDouble(), fit) else fit
        rows += JustifiedRow(start..end, height.toFloat(), (start..end).map {
            (safeRatios[it] * height).toFloat()
        })
        start = end + 1
    }
    return rows
}

internal sealed interface AttachmentTimelineEntry {
    val key: String

    data class Month(val month: YearMonth) : AttachmentTimelineEntry {
        override val key = "month:$month"
    }

    data class Day(val date: LocalDate?) : AttachmentTimelineEntry {
        override val key = "day:${date ?: "unknown"}"
    }

    data class Row(val attachments: List<TimelineAttachment>, val geometry: JustifiedRow) : AttachmentTimelineEntry {
        override val key = "row:${attachments.first().key}"
    }
}

internal fun attachmentTimelineEntries(
    days: List<AttachmentDay>, ratios: Map<String, Float>, width: Float, targetHeight: Float
): List<AttachmentTimelineEntry> = buildList {
    var previousMonth: YearMonth? = null
    for (day in days) {
        val month = day.date?.let(YearMonth::from)
        if (month != null && month != previousMonth) add(AttachmentTimelineEntry.Month(month))
        previousMonth = month
        add(AttachmentTimelineEntry.Day(day.date))
        val rows = justifiedRows(day.attachments.map {
            val type = it.attachment.displayType
            if (type.contains("image", ignoreCase = true) || type.contains("video", ignoreCase = true)) {
                ratios[it.attachment.identityKey] ?: 1f
            } else 2f
        }, width, targetHeight)
        for (row in rows) add(AttachmentTimelineEntry.Row(day.attachments.slice(row.indices), row))
    }
}

internal fun shouldLoadTimelinePage(lastVisibleAttachment: Int?, attachmentCount: Int, loading: Boolean, token: String?): Boolean =
    lastVisibleAttachment != null && attachmentCount > 0 && !loading && !token.isNullOrBlank() &&
        lastVisibleAttachment >= attachmentCount - 5

internal fun timelineRowIndex(entries: List<AttachmentTimelineEntry>, attachmentKey: String): Int? =
    entries.indexOfFirst {
        when (it) {
            is AttachmentTimelineEntry.Row -> it.attachments.any { item -> item.key == attachmentKey }
            else -> false
        }
    }
        .takeIf { it >= 0 }

internal fun AttachmentTimelineEntry.lastAttachmentIndex(): Int? = when (this) {
    is AttachmentTimelineEntry.Row -> attachments.lastOrNull()?.index
    else -> null
}
