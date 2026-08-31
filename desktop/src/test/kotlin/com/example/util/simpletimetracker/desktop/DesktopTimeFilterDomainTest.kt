package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopTimeFilterDomainTest {
    @Test
    fun shiftedDaysWeekAndMonthUseAndroidCompatibleBoundaries() {
        val preferences = MemoryPreferences(
            allowMultitasking = true,
            startOfDayShiftMillis = 2 * HOUR,
            firstDayOfWeek = DayOfWeek.MONDAY,
        )
        val service = DesktopTimeService(preferences, UTC) { instant(2025, 1, 2, 3) }

        assertEquals(LocalDate.of(2025, 1, 1), service.userDate(instant(2025, 1, 2, 1, 59)))
        assertEquals(LocalDate.of(2025, 1, 2), service.userDate(instant(2025, 1, 2, 2)))
        assertEquals(
            DesktopTimeRange(instant(2025, 1, 2, 2), instant(2025, 1, 3, 2)),
            service.day(LocalDate.of(2025, 1, 2)),
        )
        assertEquals(LocalDate.of(2025, 1, 1), service.previousDay(LocalDate.of(2025, 1, 2)))
        assertEquals(LocalDate.of(2025, 1, 3), service.nextDay(LocalDate.of(2025, 1, 2)))
        assertEquals(
            DesktopTimeRange(instant(2024, 12, 30, 2), instant(2025, 1, 6, 2)),
            service.week(LocalDate.of(2025, 1, 2)),
        )
        assertEquals(
            DesktopTimeRange(instant(2025, 1, 1, 2), instant(2025, 2, 1, 2)),
            service.month(LocalDate.of(2025, 1, 2)),
        )
    }

    @Test
    fun negativeShiftIsPreservedAndMovesTheBoundaryIntoThePreviousLocalDate() {
        val service = DesktopTimeService(
            MemoryPreferences(true, startOfDayShiftMillis = -2 * HOUR),
            UTC,
        )

        assertEquals(LocalDate.of(2025, 1, 2), service.userDate(instant(2025, 1, 1, 23)))
        assertEquals(
            DesktopTimeRange(instant(2025, 1, 1, 22), instant(2025, 1, 2, 22)),
            service.day(LocalDate.of(2025, 1, 2)),
        )
    }

    @Test
    fun weekUsesConfiguredFirstDayAndShiftKeepsWallClockAcrossDst() {
        val sunday = DesktopTimeService(
            MemoryPreferences(true, firstDayOfWeek = DayOfWeek.SUNDAY),
            UTC,
        )
        assertEquals(
            DesktopTimeRange(instant(2024, 12, 29), instant(2025, 1, 5)),
            sunday.week(LocalDate.of(2025, 1, 2)),
        )

        val amsterdam = DesktopTimeService(
            MemoryPreferences(true, startOfDayShiftMillis = 6 * HOUR),
            ZoneId.of("Europe/Amsterdam"),
        )
        val start = amsterdam.day(LocalDate.of(2025, 3, 30)).startedAt
        val end = amsterdam.day(LocalDate.of(2025, 3, 30)).endedAt
        assertEquals(6, java.time.Instant.ofEpochMilli(start).atZone(ZoneId.of("Europe/Amsterdam")).hour)
        assertEquals(6, java.time.Instant.ofEpochMilli(end).atZone(ZoneId.of("Europe/Amsterdam")).hour)
        assertEquals(24 * HOUR, end - start)

        val midnight = DesktopTimeService(MemoryPreferences(true), ZoneId.of("Europe/Amsterdam"))
        assertEquals(
            23 * HOUR,
            midnight.day(LocalDate.of(2025, 3, 30)).endedAt - midnight.day(LocalDate.of(2025, 3, 30)).startedAt,
        )
    }

    @Test
    fun halfOpenIntersectionClipsExactlyLikeAndroidRangeMapper() {
        val range = DesktopTimeRange(100, 300)

        assertEquals(100, range.clippedDuration(50, 200))
        assertEquals(100, range.clippedDuration(200, 400))
        assertEquals(200, range.clippedDuration(50, 400))
        assertEquals(100, range.clippedDuration(100, 200))
        assertEquals(0, range.clippedDuration(100, 100))
        assertFalse(range.intersects(0, 100))
        assertFalse(range.intersects(300, 400))
        assertFalse(range.intersects(100, 100))
        assertTrue(range.intersects(100, 101))
    }

    @Test
    fun rangeServiceAppliesActivityCategoryAndTagFilterSemanticsAndKeepsArchivedHistory() {
        val database = database()
        database.addActivity("Work")
        database.addActivity("Break")
        val activities = database.activities().associateBy(ActivityRow::name)
        val taxonomy = DesktopTagCategoryService(database)
        val workCategory = taxonomy.saveCategory(draft = DesktopCategoryDraft("Office")).second!!
        val focusTag = taxonomy.saveTag(draft = DesktopTagDraft("Focus", DesktopTagValueType.NONE, "")).second!!
        val editor = DesktopActivityEditorService(database)
        editor.update(
            activities.getValue("Work").id,
            DesktopActivityDetailsDraft("Work", 0, setOf(workCategory), emptySet(), emptySet()),
        )
        val records = DesktopRecordService(database)
        assertEquals(
            RecordWriteResult.SAVED,
            records.create(DesktopRecordDraft(activities.getValue("Work").id, 100, 300, "", listOf(DesktopRecordTag(focusTag, null)))),
        )
        assertEquals(
            RecordWriteResult.SAVED,
            records.create(DesktopRecordDraft(activities.getValue("Break").id, 200, 400, "", emptyList())),
        )
        val service = DesktopRecordsRangeService(database) { 500 }
        val range = DesktopTimeRange(150, 350)

        assertEquals(2, service.get(range).size)
        assertEquals(300, service.totalDuration(range))
        assertEquals(
            setOf("Work", "Break"),
            service.get(
                range,
                DesktopRecordFilter(
                    includedActivityIds = setOf(activities.getValue("Break").id),
                    includedCategoryIds = setOf(workCategory),
                ),
            ).map(DesktopTimelineRecord::activityName).toSet(),
        )
        assertTrue(
            service.get(
                range,
                DesktopRecordFilter(
                    includedActivityIds = setOf(activities.getValue("Break").id),
                    includedTagIds = setOf(focusTag),
                ),
            ).isEmpty(),
        )
        assertEquals(
            listOf("Break"),
            service.get(range, DesktopRecordFilter(includeUntagged = true)).map(DesktopTimelineRecord::activityName),
        )
        assertEquals(
            listOf("Break"),
            service.get(range, DesktopRecordFilter(excludedTagIds = setOf(focusTag))).map(DesktopTimelineRecord::activityName),
        )

        database.archiveActivity(activities.getValue("Work").id)
        assertEquals(
            listOf("Work"),
            service.get(range, DesktopRecordFilter(includedCategoryIds = setOf(workCategory)))
                .map(DesktopTimelineRecord::activityName),
        )

        taxonomy.archiveTag(focusTag)
        assertEquals(
            listOf("Work"),
            service.get(range, DesktopRecordFilter(includedTagIds = setOf(focusTag))).map(DesktopTimelineRecord::activityName),
        )
    }

    @Test
    fun runningRecordsAreIncludedAndClippedByTheSameRangeAndFilterService() {
        val database = database()
        database.addActivity("Running")
        val id = database.activities().single().id
        val timer = DesktopTimerService(database, MemoryPreferences(true), clock(250))
        assertEquals(TimerActionResult.STARTED, timer.start(id))
        val service = DesktopRecordsRangeService(database) { 500 }

        val records = service.get(DesktopTimeRange(300, 450))

        assertEquals(1, records.size)
        assertTrue(records.single().isRunning)
        assertEquals(150, service.totalDuration(DesktopTimeRange(300, 450)))
        assertTrue(service.get(DesktopTimeRange(0, 200)).isEmpty())
    }

    @Test
    fun savedFiltersPersistUpdateAndDeleteWithoutBlockingEntityDeletion() {
        val database = database()
        database.addActivity("Work")
        val activityId = database.activities().single().id
        val tagId = DesktopTagCategoryService(database)
            .saveTag(draft = DesktopTagDraft("Focus", DesktopTagValueType.NONE, "")).second!!
        val filters = DesktopSavedFilterService(database)
        val original = DesktopRecordFilter(includedActivityIds = setOf(activityId), includedTagIds = setOf(tagId))

        val saved = filters.save(name = "Работа", filter = original)
        assertEquals(DesktopSavedFilterResult.SAVED, saved.first)
        val id = saved.second!!
        assertEquals(
            original,
            DesktopSavedFilterService(DesktopDatabase(database.path)).all().single().filter,
        )
        assertEquals(
            DesktopSavedFilterResult.NAME_CONFLICT,
            filters.save(name = "Работа", filter = DesktopRecordFilter.EMPTY).first,
        )
        val updated = DesktopRecordFilter(excludedActivityIds = setOf(activityId), includeUntagged = true)
        assertEquals(DesktopSavedFilterResult.SAVED, filters.save(id, "Другое", updated).first)
        assertEquals("Другое", filters.all().single().name)
        assertEquals(updated, filters.all().single().filter)

        assertEquals(DesktopTaxonomyWriteResult.SAVED, DesktopTagCategoryService(database).deleteTag(tagId))
        assertEquals(updated, filters.all().single().filter)
        assertEquals(DesktopSavedFilterResult.SAVED, filters.delete(id))
        assertTrue(filters.all().isEmpty())
    }

    @Test
    fun advancedSavedFilterAndUntrackedIntervalsPersistAndUseUnionCoverage() {
        val database = database()
        database.addActivity("Work")
        val activity = database.activities().single()
        val records = DesktopRecordService(database)
        assertEquals(RecordWriteResult.SAVED, records.create(DesktopRecordDraft(activity.id, 100, 250, "alpha", emptyList())))
        assertEquals(RecordWriteResult.SAVED, records.create(DesktopRecordDraft(activity.id, 200, 350, "", emptyList())))

        val filter = DesktopRecordFilter(
            commentFilter = DesktopCommentFilter.CONTAINS,
            commentQuery = "ALP",
            dateRange = DesktopTimeRange(0, 500),
            daysOfWeek = setOf(DayOfWeek.MONDAY),
            timeOfDayStartMillis = 60_000,
            timeOfDayEndMillis = 120_000,
            minDurationMillis = 10,
            maxDurationMillis = 1_000,
            multitaskOnly = true,
            duplicates = DesktopDuplicateFilter.SAME_ACTIVITY,
        )
        val saved = DesktopSavedFilterService(database).save(name = "Расширенный", filter = filter)
        assertEquals(DesktopSavedFilterResult.SAVED, saved.first)
        assertEquals(filter, DesktopSavedFilterService(DesktopDatabase(database.path)).all().single().filter)

        val gaps = DesktopUntrackedRecords.calculate(
            records = listOf(
                DesktopTimelineRecord(1, activity.id, "Work", 100, 250, "", emptyList(), emptySet(), false),
                DesktopTimelineRecord(2, activity.id, "Work", 200, 350, "", emptyList(), emptySet(), false),
            ),
            range = DesktopTimeRange(0, 500),
            now = 500,
            minimumDurationMillis = 0,
        )
        assertEquals(listOf(350L to 500L), gaps.map { it.startedAt to it.endedAt })
        assertTrue(gaps.all(DesktopTimelineRecord::isUntracked))
    }

    private fun database(): DesktopDatabase = DesktopDatabase(
        Files.createTempDirectory("desktop-time-filter-test").resolve("tracker.sqlite3"),
    )

    private fun instant(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        LocalDate.of(year, month, day).atTime(hour, minute).atZone(UTC).toInstant().toEpochMilli()

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")
        const val HOUR = 3_600_000L
    }
}
