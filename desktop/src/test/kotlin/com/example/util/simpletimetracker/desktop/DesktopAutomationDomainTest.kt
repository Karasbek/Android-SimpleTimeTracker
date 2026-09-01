package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import java.sql.DriverManager
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAutomationDomainTest {
    @Test
    fun version9MigratesAutomationTablesWithoutLosingRecords() {
        val db = database()
        db.addActivity("Kept")
        val id = db.activities().single().id
        assertEquals(RecordWriteResult.SAVED, DesktopRecordService(db).create(DesktopRecordDraft(id, 10, 20, "kept", emptyList())))
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { connection -> connection.createStatement().use { statement ->
            listOf("activity_reminder_overrides", "scheduled_reminders", "activity_suggestion_items", "activity_suggestions", "complex_rule_tags", "complex_rules").forEach { statement.execute("DROP TABLE $it") }
            statement.execute("PRAGMA user_version = 9")
        } }
        val migrated = DesktopDatabase(db.path)
        assertEquals(10, schemaVersion(db.path))
        assertEquals("Kept", migrated.activities().single().name)
        assertEquals("kept", migrated.historyForDate(LocalDate.ofEpochDay(0)).single().comment)
        assertTrue(migrated.complexRules().isEmpty())
        assertTrue(migrated.scheduledReminders().isEmpty())
    }

    @Test
    fun complexRulesApplyAllConditionsAndOverrideMultitaskingAndTags() {
        val db = database(); db.addActivity("A"); db.addActivity("B")
        val (a, b) = db.activities().map(ActivityRow::id)
        val tag = DesktopTagCategoryService(db).saveTag(draft = DesktopTagDraft("Score", DesktopTagValueType.NUMERIC, "")).second!!
        db.saveComplexRule(DesktopComplexRule(action = DesktopComplexRuleAction.ASSIGN_TAG, assignedTags = listOf(DesktopComplexRuleTag(tag, 2.5)), startingActivityIds = setOf(b), daysOfWeek = setOf(DayOfWeek.THURSDAY)))
        db.saveComplexRule(DesktopComplexRule(action = DesktopComplexRuleAction.DISALLOW_MULTITASKING, startingActivityIds = setOf(b), daysOfWeek = setOf(DayOfWeek.THURSDAY)))
        val time = DesktopTimeService(MemoryPreferences(true), ZoneId.of("UTC")) { 100 }
        val service = DesktopTimerService(db, MemoryPreferences(true), clock(100, 200), DesktopComplexRuleProcessor(db, time))
        assertEquals(TimerActionResult.STARTED, service.start(a))
        assertEquals(TimerActionResult.STARTED, service.start(b))
        assertEquals(listOf(b), db.runningRecords().map(DesktopRunningRecord::activityId))
        assertEquals(listOf(DesktopRecordTag(tag, 2.5)), db.runningRecords().single().tags)
        assertEquals(2, DesktopDatabase(db.path).complexRules().size)
    }

    @Test
    fun allowRuleWinsOverDisallowAndStaleRuleReferencesDoNotCrash() {
        val db = database(); db.addActivity("A"); db.addActivity("B")
        val (a, b) = db.activities().map(ActivityRow::id)
        db.saveComplexRule(DesktopComplexRule(action = DesktopComplexRuleAction.DISALLOW_MULTITASKING, startingActivityIds = setOf(b)))
        db.saveComplexRule(DesktopComplexRule(action = DesktopComplexRuleAction.ALLOW_MULTITASKING, startingActivityIds = setOf(b)))
        db.saveComplexRule(DesktopComplexRule(action = DesktopComplexRuleAction.ASSIGN_TAG, startingActivityIds = setOf(999), assignedTags = listOf(DesktopComplexRuleTag(999, null))))
        val service = DesktopTimerService(db, MemoryPreferences(false), clock(100, 200), DesktopComplexRuleProcessor(db, DesktopTimeService(MemoryPreferences(false), ZoneId.of("UTC")) { 200 }))
        service.start(a)
        assertEquals(TimerActionResult.STARTED, service.start(b))
        assertEquals(setOf(a, b), db.runningRecords().map(DesktopRunningRecord::activityId).toSet())
    }

    @Test
    fun disallowOnlyPreviousStopsOnlyRuleCurrentActivitiesAndRequestedNumericValueUsesSharedStart() {
        val db = database(); listOf("A", "B", "C").forEach(db::addActivity)
        val ids = db.activities().associate { it.name to it.id }
        val numeric = DesktopTagCategoryService(db).saveTag(draft = DesktopTagDraft("Number", DesktopTagValueType.NUMERIC, "")).second!!
        db.saveComplexRule(DesktopComplexRule(action = DesktopComplexRuleAction.DISALLOW_MULTITASKING, disallowOnlyPrevious = true, startingActivityIds = setOf(ids.getValue("C")), currentActivityIds = setOf(ids.getValue("A"))))
        db.saveComplexRule(DesktopComplexRule(action = DesktopComplexRuleAction.ASSIGN_TAG, assignedTags = listOf(DesktopComplexRuleTag(numeric, null, true)), startingActivityIds = setOf(ids.getValue("C"))))
        val service = DesktopTimerService(db, MemoryPreferences(true), clock(100, 110, 120, 130), DesktopComplexRuleProcessor(db, DesktopTimeService(MemoryPreferences(true), ZoneId.of("UTC")) { 120 }))
        service.start(ids.getValue("A")); service.start(ids.getValue("B"))
        assertEquals(TimerActionResult.TAG_VALUE_REQUIRED, service.start(ids.getValue("C")))
        assertEquals(setOf(ids.getValue("A"), ids.getValue("B")), db.runningRecords().map(DesktopRunningRecord::activityId).toSet())
        assertEquals(TimerActionResult.STARTED, service.startWithTags(ids.getValue("C"), listOf(DesktopRecordTag(numeric, 9.0))))
        assertEquals(setOf(ids.getValue("B"), ids.getValue("C")), db.runningRecords().map(DesktopRunningRecord::activityId).toSet())
    }

    @Test
    fun suggestionsAndAutomationPersistenceIgnoreStaleActivities() {
        val db = database(); db.addActivity("A"); db.addActivity("B")
        val (a, b) = db.activities().map(ActivityRow::id)
        val suggestionId = db.saveActivitySuggestion(DesktopActivitySuggestion(forActivityId = a, suggestionActivityIds = setOf(b, 999)))
        assertEquals(suggestionId, DesktopDatabase(db.path).activitySuggestions().single().id)
        assertEquals(setOf(b), DesktopAutomationService(db).suggestionsFor(setOf(a), null))
        db.archiveActivity(b)
        assertTrue(DesktopAutomationService(db).suggestionsFor(setOf(a), null).isEmpty())
    }

    @Test
    fun reminderSchedulesAreLocalAndOverridesPersistWithDndAndCondition() {
        val db = database(); db.addActivity("A"); val activity = db.activities().single().id
        val weekly = DesktopScheduledReminder(text = "weekly", schedule = DesktopReminderSchedule.Weekly(setOf(DayOfWeek.MONDAY), 9 * 3_600_000L), condition = DesktopReminderCondition.ActivityNotTrackedToday(activity))
        val id = db.saveScheduledReminder(weekly)
        val reopened = DesktopDatabase(db.path)
        assertIs<DesktopReminderCondition.ActivityNotTrackedToday>(reopened.scheduledReminders().single().condition)
        val zone = ZoneId.of("Europe/Moscow")
        val date = LocalDate.of(2026, 3, 5)
        val oneTime = DesktopReminderSchedule.OneTime(date.toEpochDay(), 10 * 3_600_000L)
        val occurrence = DesktopReminderOccurrenceCalculator.next(oneTime, date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), zone)
        assertEquals(date, Instant.ofEpochMilli(occurrence!!.expectedAt).atZone(zone).toLocalDate())
        assertNull(DesktopReminderOccurrenceCalculator.next(oneTime, occurrence.expectedAt, zone))
        val monthly = DesktopReminderOccurrenceCalculator.next(DesktopReminderSchedule.Monthly(31, 8 * 3_600_000L), LocalDate.of(2026, 2, 1).atStartOfDay(zone).toInstant().toEpochMilli(), zone)
        assertEquals(28, Instant.ofEpochMilli(monthly!!.expectedAt).atZone(zone).dayOfMonth)
        val override = DesktopActivityReminderOverride.Custom(600, true, setOf(DayOfWeek.MONDAY), 22 * 3_600_000L, 7 * 3_600_000L)
        assertTrue(db.saveActivityReminderOverride(activity, override))
        assertEquals(override, DesktopDatabase(db.path).activityReminderOverride(activity))
        val mondayNoon = LocalDate.of(2026, 3, 2).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertTrue(DesktopActivityReminderPolicy.isDue(mondayNoon - 600_000, mondayNoon, override, zone))
        assertFalse(DesktopActivityReminderPolicy.isDue(mondayNoon - 1_200_000, mondayNoon, DesktopActivityReminderOverride.Custom(600, false, setOf(DayOfWeek.MONDAY), 0, 0), zone))
        assertTrue(DesktopActivityReminderPolicy.inDnd(23 * 3_600_000L, 22 * 3_600_000L, 7 * 3_600_000L))
        assertFalse(DesktopActivityReminderPolicy.inDnd(12 * 3_600_000L, 22 * 3_600_000L, 7 * 3_600_000L))
        assertTrue(DesktopAutomationService(db).shouldDeliver(weekly, DesktopTimeRange(0, 86_400_000)))
        DesktopRecordService(db).create(DesktopRecordDraft(activity, 100, 200, "", emptyList()))
        assertFalse(DesktopAutomationService(db).shouldDeliver(weekly, DesktopTimeRange(0, 86_400_000)))
        assertTrue(db.deleteScheduledReminder(id))
        assertTrue(db.scheduledReminders().isEmpty())
    }

    private fun database() = DesktopDatabase(Files.createTempDirectory("desktop-automation").resolve("tracker.sqlite3"))
    private fun schemaVersion(path: java.nio.file.Path) = DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { r -> r.next(); r.getInt(1) } } }
}
