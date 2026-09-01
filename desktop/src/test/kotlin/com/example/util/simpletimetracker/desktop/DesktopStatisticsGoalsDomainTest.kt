package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import java.sql.DriverManager
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopStatisticsGoalsDomainTest {
    @Test
    fun version8MigratesToGoalsSchemaWithoutLosingProductData() {
        val database = database()
        database.addActivity("Work")
        val activity = database.activities().single()
        assertEquals(RecordWriteResult.SAVED, DesktopRecordService(database).create(DesktopRecordDraft(activity.id, 100, 200, "kept", emptyList())))
        DriverManager.getConnection("jdbc:sqlite:${database.path}").use { db ->
            db.createStatement().use { it.execute("DROP TABLE record_type_goals"); it.execute("PRAGMA user_version = 8") }
        }

        val migrated = DesktopDatabase(database.path)

        assertEquals(9, schemaVersion(database.path))
        assertEquals(listOf("Work"), migrated.activities().map(ActivityRow::name))
        assertEquals("kept", migrated.historyForDate(LocalDate.of(1970, 1, 1)).single().comment)
        assertTrue(migrated.goals().isEmpty())
        assertTrue(DesktopDatabase(database.path).goals().isEmpty())
    }

    @Test
    fun goalsPersistAndApplyAndroidGoalAndLimitThresholds() {
        val database = database()
        database.addActivity("Work")
        val activity = database.activities().single()
        val recordService = DesktopRecordService(database)
        assertEquals(RecordWriteResult.SAVED, recordService.create(DesktopRecordDraft(activity.id, hour(9), hour(11), "", emptyList())))
        val time = DesktopTimeService(MemoryPreferences(true), UTC) { hour(12) }
        val service = DesktopGoalsService(database, time)
        val goal = service.save(DesktopGoal(ownerType = DesktopGoalOwnerType.ACTIVITY, ownerId = activity.id, range = DesktopGoalRange.DAILY, measure = DesktopGoalMeasure.DURATION, subtype = DesktopGoalSubtype.GOAL, value = 2 * 3600, daysOfWeek = DayOfWeek.entries.toSet()))
        val limit = service.save(DesktopGoal(ownerType = DesktopGoalOwnerType.ACTIVITY, ownerId = activity.id, range = DesktopGoalRange.DAILY, measure = DesktopGoalMeasure.DURATION, subtype = DesktopGoalSubtype.LIMIT, value = 2 * 3600, daysOfWeek = DayOfWeek.entries.toSet()))
        assertEquals(DesktopGoalWriteResult.SAVED, goal.first)
        assertEquals(DesktopGoalWriteResult.SAVED, limit.first)

        val records = DesktopRecordsRangeService(database) { hour(12) }.get(DesktopTimeRange(hour(0), hour(24)))
        val progress = service.progress(LocalDate.of(1970, 1, 1), records, hideFinished = false)

        assertTrue(progress.first { it.goal.id == goal.second }.reached)
        assertFalse(progress.first { it.goal.id == limit.second }.reached)
        assertTrue(progress.first { it.goal.id == limit.second }.successful)
        assertEquals(2, DesktopGoalsService(DesktopDatabase(database.path), time).all().size)
    }

    @Test
    fun goalsSupportCategoryTagCountWeekdaysAndArchivedHistoricalActivity() {
        val database = database()
        database.addActivity("Work")
        val activity = database.activities().single()
        val taxonomy = DesktopTagCategoryService(database)
        val category = taxonomy.saveCategory(draft = DesktopCategoryDraft("Office")).second!!
        val tag = taxonomy.saveTag(draft = DesktopTagDraft("Focus", DesktopTagValueType.NUMERIC, "pts")).second!!
        assertEquals(DesktopTaxonomyWriteResult.SAVED, DesktopActivityEditorService(database).update(activity.id, DesktopActivityDetailsDraft("Work", 0, setOf(category), setOf(tag), emptySet())))
        assertEquals(RecordWriteResult.SAVED, DesktopRecordService(database).create(DesktopRecordDraft(activity.id, hour(9), hour(10), "", listOf(DesktopRecordTag(tag, 2.5)))))
        database.archiveActivity(activity.id)
        val time = DesktopTimeService(MemoryPreferences(true), UTC) { hour(12) }
        val service = DesktopGoalsService(database, time)
        assertEquals(DesktopGoalWriteResult.SAVED, service.save(DesktopGoal(ownerType = DesktopGoalOwnerType.CATEGORY, ownerId = category, range = DesktopGoalRange.WEEKLY, measure = DesktopGoalMeasure.COUNT, subtype = DesktopGoalSubtype.GOAL, value = 1)).first)
        assertEquals(DesktopGoalWriteResult.SAVED, service.save(DesktopGoal(ownerType = DesktopGoalOwnerType.TAG, ownerId = tag, range = DesktopGoalRange.DAILY, measure = DesktopGoalMeasure.COUNT, subtype = DesktopGoalSubtype.GOAL, value = 1, daysOfWeek = setOf(DayOfWeek.THURSDAY))).first)
        val records = DesktopRecordsRangeService(database) { hour(12) }.get(DesktopTimeRange(hour(0), hour(24)))

        val progress = service.progress(LocalDate.of(1970, 1, 1), records, hideFinished = false)

        assertEquals(2, progress.size)
        assertTrue(progress.all(DesktopGoalProgress::successful))
        assertTrue(service.progress(LocalDate.of(1970, 1, 2), records, false).none { it.goal.ownerType == DesktopGoalOwnerType.TAG })
    }

    @Test
    fun detailedStatisticsReuseClippingAndKeepOverlappingCategoryTagGroups() {
        val database = database()
        database.addActivity("Work")
        val activity = database.activities().single()
        val taxonomy = DesktopTagCategoryService(database)
        val firstCategory = taxonomy.saveCategory(draft = DesktopCategoryDraft("A")).second!!
        val secondCategory = taxonomy.saveCategory(draft = DesktopCategoryDraft("B")).second!!
        val tag = taxonomy.saveTag(draft = DesktopTagDraft("Value", DesktopTagValueType.NUMERIC, "kg")).second!!
        DesktopActivityEditorService(database).update(activity.id, DesktopActivityDetailsDraft("Work", 0, setOf(firstCategory, secondCategory), setOf(tag), emptySet()))
        DesktopRecordService(database).create(DesktopRecordDraft(activity.id, 100, 300, "", listOf(DesktopRecordTag(tag, 1.5))))
        val range = DesktopTimeRange(150, 250)
        val records = DesktopRecordsRangeService(database) { 500 }.get(range)
        val statistics = DesktopDetailedStatisticsService(database)

        assertEquals(100L, statistics.breakdown(records, range, DesktopStatisticsGrouping.ACTIVITY).single().durationMillis)
        assertEquals(setOf("A", "B"), statistics.breakdown(records, range, DesktopStatisticsGrouping.CATEGORY).map(DesktopStatisticsBreakdown::name).toSet())
        val tagBreakdown = statistics.breakdown(records, range, DesktopStatisticsGrouping.TAG).single()
        assertEquals(100L, tagBreakdown.durationMillis)
        assertEquals(1.5, tagBreakdown.numericValueSum)
        assertEquals(1L, tagBreakdown.numericValueCount)
    }

    @Test
    fun sessionMonthlyAndMissingOwnerGoalsRemainSafeAndRetroactive() {
        val database = database()
        database.addActivity("Work")
        val activity = database.activities().single()
        val tag = DesktopTagCategoryService(database).saveTag(draft = DesktopTagDraft("Temp", DesktopTagValueType.NONE, "")).second!!
        assertEquals(RecordWriteResult.SAVED, DesktopRecordService(database).create(DesktopRecordDraft(activity.id, hour(1), hour(4), "", listOf(DesktopRecordTag(tag, null)))))
        val time = DesktopTimeService(MemoryPreferences(true), UTC) { hour(12) }
        val service = DesktopGoalsService(database, time)
        val session = service.save(DesktopGoal(ownerType = DesktopGoalOwnerType.ACTIVITY, ownerId = activity.id, range = DesktopGoalRange.SESSION, measure = DesktopGoalMeasure.DURATION, subtype = DesktopGoalSubtype.GOAL, value = 3 * 3600)).second!!
        val monthly = service.save(DesktopGoal(ownerType = DesktopGoalOwnerType.ACTIVITY, ownerId = activity.id, range = DesktopGoalRange.MONTHLY, measure = DesktopGoalMeasure.COUNT, subtype = DesktopGoalSubtype.GOAL, value = 1)).second!!
        // A stale relation can exist after deletion/import; it must never crash progress rendering.
        database.saveGoal(DesktopGoal(ownerType = DesktopGoalOwnerType.TAG, ownerId = tag, range = DesktopGoalRange.DAILY, measure = DesktopGoalMeasure.COUNT, subtype = DesktopGoalSubtype.GOAL, value = 1, daysOfWeek = DayOfWeek.entries.toSet()))
        DesktopTagCategoryService(database).deleteTag(tag)
        val records = DesktopRecordsRangeService(database) { hour(12) }.get(DesktopTimeRange(hour(0), hour(24)))

        val progress = service.progress(LocalDate.of(1970, 1, 1), records, hideFinished = false)

        assertTrue(progress.first { it.goal.id == session }.successful)
        assertTrue(progress.first { it.goal.id == monthly }.successful)
        assertTrue(progress.any { it.ownerName.startsWith("Удалённый объект") })
        assertTrue(service.progress(LocalDate.of(1970, 1, 1), records, hideFinished = true).none { it.successful })
    }

    private fun database(): DesktopDatabase = DesktopDatabase(Files.createTempDirectory("desktop-goals-test").resolve("tracker.sqlite3"))
    private fun schemaVersion(path: java.nio.file.Path): Int = DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { it.executeQuery("PRAGMA user_version").use { result -> result.next(); result.getInt(1) } } }
    private fun hour(hour: Int): Long = hour * 3_600_000L

    private companion object { val UTC: ZoneId = ZoneId.of("UTC") }
}
