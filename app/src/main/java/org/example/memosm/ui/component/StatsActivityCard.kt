package org.example.memosm.ui.component

import android.text.format.DateFormat
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.memosm.model.UserStats
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.TextStyle

/** Compact activity calendar; summary counts live in the profile header. */
@Composable
fun StatsActivityCard(
    modifier: Modifier = Modifier, userStats: UserStats?, weekStartDayOffset: Int = 0
) {
    val timestamps = userStats?.memoDisplayTimestamps ?: emptyList()

    val activityData = remember(timestamps) {
        calculateActivityDataFromTimestamps(timestamps)
    }

    val displayMonth = remember(activityData) {
        if (activityData.isEmpty()) {
            YearMonth.now()
        } else {
            val latestDate = activityData.keys.maxOrNull() ?: LocalDate.now()
            YearMonth.from(latestDate)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        CalendarMonthView(
            yearMonth = displayMonth,
            activityData = activityData,
            weekStartDayOffset = weekStartDayOffset,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun CalendarMonthView(
    modifier: Modifier = Modifier,
    yearMonth: YearMonth,
    activityData: Map<LocalDate, Int>,
    weekStartDayOffset: Int = 0,
) {
    val firstDayOfMonth = yearMonth.atDay(1)
    val lastDayOfMonth = yearMonth.atEndOfMonth()
    val today = LocalDate.now()

    val maxCount = activityData.values.maxOrNull()?.coerceAtLeast(1) ?: 1

    val primaryColor = MaterialTheme.colorScheme.primary
    val emptyDayColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f)
    val activeDayColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val onSurface = MaterialTheme.colorScheme.onSurface
    val cellShape = CircleShape
    val cellHeight = with(LocalDensity.current) {
        MaterialTheme.typography.labelSmall.lineHeight.toDp() + 12.dp
    }.coerceAtLeast(28.dp)
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val locale = LocalConfiguration.current.locales[0]
    val monthYearText = remember(yearMonth, locale) {
        val pattern = DateFormat.getBestDateTimePattern(locale, "MMMM yyyy")
        val formatter = DateTimeFormatter.ofPattern(pattern, locale)
        yearMonth.format(formatter)
    }

    Column(modifier = modifier) {
        // Month and year header
        Text(
            text = monthYearText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // Weekday headers - rotate based on weekStartDayOffset
        // weekStartDayOffset: 0=Sunday, 1=Monday, ..., 6=Saturday
        val baseDaysOfWeek = listOf(
            DayOfWeek.SUNDAY,
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY
        )
        val safeOffset = weekStartDayOffset.coerceIn(0, 6)
        val daysOfWeek = baseDaysOfWeek.drop(safeOffset) + baseDaysOfWeek.take(safeOffset)

        Row(
            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            for (day in daysOfWeek) {
                Text(
                    text = day.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Calendar grid
        // Calculate offset: how many blank cells before day 1
        // DayOfWeek.value: Monday=1 … Sunday=7; convert to Sun=0-based index
        val dayOfWeekSundayBased = firstDayOfMonth.dayOfWeek.value % 7 // Sun=0, Mon=1, ..., Sat=6
        val firstDayOfWeek = (dayOfWeekSundayBased - safeOffset + 7) % 7
        val daysInMonth = lastDayOfMonth.dayOfMonth
        val totalCells = ((firstDayOfWeek + daysInMonth + 6) / 7) * 7
        val numWeeks = totalCells / 7

        for (week in 0 until numWeeks) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (dayOfWeek in 0..6) {
                    val cellIndex = week * 7 + dayOfWeek
                    val dayOfMonth = cellIndex - firstDayOfWeek + 1

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (dayOfMonth in 1..daysInMonth) {
                            val date = yearMonth.atDay(dayOfMonth)
                            val count = activityData[date] ?: 0
                            val isToday = date == today

                            val intensity = calculateIntensity(count, maxCount)

                            val cellColor = when {
                                count > 0 -> lerp(activeDayColor, primaryColor, intensity * 0.25f)
                                else -> emptyDayColor
                            }

                            val textColor = when {
                                isToday -> primaryColor
                                count > 0 -> onSurface
                                else -> onSurfaceVariant
                            }

                            Box(
                                modifier = Modifier
                                    .size(cellHeight)
                                    .clip(cellShape)
                                    .background(cellColor)
                                    .then(
                                        if (isToday) {
                                            Modifier.border(1.dp, primaryColor, cellShape)
                                        } else Modifier
                                    ), contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = dayOfMonth.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isToday || count > 0) FontWeight.Bold else FontWeight.Normal,
                                    color = textColor,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun calculateIntensity(count: Int, maxCount: Int): Float {
    if (count == 0) return 0f
    if (maxCount <= 1) return 1f

    val logCount = kotlin.math.ln((count + 1).toDouble())
    val logMax = kotlin.math.ln((maxCount + 1).toDouble())

    return (logCount / logMax).toFloat().coerceIn(0.4f, 1f)
}

private fun calculateActivityDataFromTimestamps(timestamps: List<String>): Map<LocalDate, Int> {
    val activityCounts = mutableMapOf<LocalDate, Int>()

    timestamps.forEach { timestamp ->
        val date = parseActivityDate(timestamp)
        if (date != null) {
            activityCounts[date] = (activityCounts[date] ?: 0) + 1
        } else {
            Log.w("StatsActivityCard", "Failed to parse timestamp: $timestamp")
        }
    }

    return activityCounts
}

private fun parseActivityDate(timestamp: String): LocalDate? {
    return try {
        ZonedDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME).toLocalDate()
    } catch (_: DateTimeParseException) {
        try {
            OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME).toLocalDate()
        } catch (_: DateTimeParseException) {
            try {
                Instant.parse(timestamp).atOffset(java.time.ZoneOffset.UTC).toLocalDate()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME).toLocalDate()
                } catch (_: DateTimeParseException) {
                    try {
                        LocalDate.parse(timestamp.take(10))
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
    }
}
