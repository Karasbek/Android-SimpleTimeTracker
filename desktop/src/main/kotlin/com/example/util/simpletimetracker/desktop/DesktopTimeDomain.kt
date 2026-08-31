package com.example.util.simpletimetracker.desktop

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min

/** A half-open absolute interval, matching Android RangeMapper filtering semantics. */
data class DesktopTimeRange(
    val startedAt: Long,
    val endedAt: Long,
) {
    init {
        require(endedAt >= startedAt) { "Range end must not precede range start" }
    }

    fun intersects(startedAt: Long, endedAt: Long): Boolean =
        startedAt < this.endedAt && endedAt > this.startedAt

    fun clippedDuration(startedAt: Long, endedAt: Long): Long =
        (min(endedAt, this.endedAt) - max(startedAt, this.startedAt)).coerceAtLeast(0)
}

enum class DesktopRangeLength {
    DAY,
    WEEK,
    MONTH,
}

class DesktopTimeService(
    private val preferences: DesktopSemanticPreferences,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun userDate(timestamp: Long = now()): LocalDate =
        localShift(timestamp, -preferences.startOfDayShiftMillis).toLocalDate()

    fun currentDay(): DesktopTimeRange = day(userDate())

    fun day(date: LocalDate): DesktopTimeRange =
        DesktopTimeRange(boundary(date), boundary(date.plusDays(1)))

    fun previousDay(date: LocalDate): LocalDate = date.minusDays(1)

    fun nextDay(date: LocalDate): LocalDate = date.plusDays(1)

    fun week(anchor: LocalDate = userDate()): DesktopTimeRange {
        val start = anchor.with(TemporalAdjusters.previousOrSame(preferences.firstDayOfWeek))
        return DesktopTimeRange(boundary(start), boundary(start.plusWeeks(1)))
    }

    fun month(anchor: LocalDate = userDate()): DesktopTimeRange {
        val month = YearMonth.from(anchor)
        return DesktopTimeRange(boundary(month.atDay(1)), boundary(month.plusMonths(1).atDay(1)))
    }

    fun range(length: DesktopRangeLength, anchor: LocalDate = userDate()): DesktopTimeRange = when (length) {
        DesktopRangeLength.DAY -> day(anchor)
        DesktopRangeLength.WEEK -> week(anchor)
        DesktopRangeLength.MONTH -> month(anchor)
    }

    fun custom(startedAt: Long, endedAt: Long): DesktopTimeRange =
        DesktopTimeRange(startedAt, endedAt.coerceAtLeast(startedAt))

    private fun boundary(date: LocalDate): Long =
        date.atStartOfDay(zone)
            .toLocalDateTime()
            .plusNanos(preferences.startOfDayShiftMillis * 1_000_000)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun localShift(timestamp: Long, shiftMillis: Long): ZonedDateTime =
        Instant.ofEpochMilli(timestamp)
            .atZone(zone)
            .toLocalDateTime()
            .plusNanos(shiftMillis * 1_000_000)
            .atZone(zone)
}
