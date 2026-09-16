package org.example.memosm.ui.component

import android.text.format.DateFormat
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.memosm.R
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

/**
 * Combined Stats and Activity Card with responsive layout.
 * - Narrow screens: Stats on top, Calendar on bottom
 * - Wide screens: Calendar on left, Stats on right
 */
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

    Card(modifier = modifier.fillMaxWidth()) {
        @Suppress("COMPOSE_APPLIER_CALL_MISMATCH") BoxWithConstraints(modifier = Modifier.padding(16.dp)) {
            val isWide = maxWidth > 500.dp

            if (isWide) {
                // Wide layout: Calendar left, Stats right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Calendar section
                    Column(modifier = Modifier.weight(1f)) {
                        CalendarMonthView(
                            yearMonth = displayMonth,
                            activityData = activityData,
                            weekStartDayOffset = weekStartDayOffset,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Stats section - smaller on tablet since vertical space is limited
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        StatsGrid(userStats, compact = true)
                    }
                }
            } else {
                // Narrow layout: Stats top, Calendar bottom
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Stats section - larger on phone since horizontal space is available
                    Spacer(modifier = Modifier.height(8.dp))
                    StatsGrid(userStats, compact = false)

                    Spacer(modifier = Modifier.height(16.dp))

                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Calendar section
                    CalendarMonthView(
                        yearMonth = displayMonth,
                        activityData = activityData,
                        weekStartDayOffset = weekStartDayOffset,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsGrid(stats: UserStats?, compact: Boolean = false) {
    val notAvailable = stringResource(R.string.common_not_available)
    val dividerPadding = if (compact) 10.dp else 16.dp

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // First Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatItem(
                label = stringResource(R.string.profile_stats_memos),
                value = stats?.totalMemoCount?.toString() ?: notAvailable,
                icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                compact = compact,
                modifier = Modifier.weight(1f)
            )
            StatItem(
                label = stringResource(R.string.profile_stats_tags),
                value = stats?.tagCount?.size?.toString() ?: notAvailable,
                icon = Icons.Outlined.Tag,
                compact = compact,
                modifier = Modifier.weight(1f)
            )
            StatItem(
                label = stringResource(R.string.profile_stats_pinned),
                value = stats?.pinnedMemos?.size?.toString() ?: notAvailable,
                icon = Icons.Outlined.PushPin,
                compact = compact,
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = dividerPadding, horizontal = 24.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        // Second Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatItem(
                label = stringResource(R.string.profile_stats_links),
                value = stats?.memoTypeStats?.linkCount?.toString() ?: notAvailable,
                icon = Icons.Outlined.Link,
                compact = compact,
                modifier = Modifier.weight(1f)
            )
            StatItem(
                label = stringResource(R.string.profile_stats_code),
                value = stats?.memoTypeStats?.codeCount?.toString() ?: notAvailable,
                icon = Icons.Outlined.Code,
                compact = compact,
                modifier = Modifier.weight(1f)
            )
            StatItem(
                label = stringResource(R.string.profile_stats_todo),
                value = stats?.memoTypeStats?.todoCount?.toString() ?: notAvailable,
                icon = Icons.Outlined.TaskAlt,
                compact = compact,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatItem(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    icon: ImageVector,
    compact: Boolean = false,
) {
    val iconSize = if (compact) 18.dp else 22.dp
    val valueStyle =
        if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
    val labelStyle =
        if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
    val verticalPadding = if (compact) 2.dp else 4.dp
    val iconSpacing = if (compact) 2.dp else 4.dp

    Column(
        modifier = modifier.padding(vertical = verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(iconSpacing))
        Text(
            text = value, style = valueStyle, fontWeight = FontWeight.Bold
        )
        Text(text = label, style = labelStyle)
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
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
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
            modifier = Modifier.padding(bottom = 8.dp)
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

        Spacer(modifier = Modifier.height(4.dp))

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
                            .aspectRatio(1f)
                            .padding(1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (dayOfMonth in 1..daysInMonth) {
                            val date = yearMonth.atDay(dayOfMonth)
                            val count = activityData[date] ?: 0
                            val isToday = date == today

                            val intensity = calculateIntensity(count, maxCount)

                            val cellColor = when {
                                count > 0 -> lerp(surfaceVariant, primaryColor, intensity)
                                else -> surfaceVariant.copy(alpha = 0.5f)
                            }

                            val textColor = when {
                                count > 0 -> MaterialTheme.colorScheme.onPrimary
                                isToday -> primaryColor
                                else -> onSurfaceVariant.copy(alpha = 0.8f)
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(cellColor)
                                    .then(
                                        if (isToday && count == 0) {
                                            Modifier.background(
                                                primaryColor.copy(alpha = 0.15f),
                                                RoundedCornerShape(4.dp)
                                            )
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
