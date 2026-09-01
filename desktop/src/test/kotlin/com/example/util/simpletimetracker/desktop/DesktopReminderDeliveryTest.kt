package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopReminderDeliveryTest {
    @Test
    fun recurringOccurrencesAndFailedNotificationUseOccurrenceAwareJournal() {
        val db = database(); val zone = ZoneId.of("UTC"); val monday = LocalDate.of(2026, 1, 5).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        db.saveScheduledReminder(DesktopScheduledReminder(text = "weekly", schedule = DesktopReminderSchedule.Weekly(setOf(DayOfWeek.MONDAY), 9 * 3_600_000L)))
        val journal = DesktopReminderDeliveryJournal(Files.createTempDirectory("delivery").resolve("journal")); var attempts = 0
        val failed = DesktopReminderDeliveryService(db, DesktopTimeService(MemoryPreferences(true), zone) { monday }, DesktopNotificationAdapter { _, _ -> attempts++; false }, journal, { monday }, zone)
        failed.tick(); failed.tick(); assertEquals(2, attempts)
        val delivered = mutableListOf<String>()
        DesktopReminderDeliveryService(db, DesktopTimeService(MemoryPreferences(true), zone) { monday }, DesktopNotificationAdapter { _, text -> delivered += text; true }, journal, { monday }, zone).tick()
        val nextWeek = monday + 7 * 86_400_000L
        DesktopReminderDeliveryService(db, DesktopTimeService(MemoryPreferences(true), zone) { nextWeek }, DesktopNotificationAdapter { _, text -> delivered += text; true }, journal, { nextWeek }, zone).tick()
        assertEquals(listOf("weekly", "weekly"), delivered)
    }

    @Test
    fun scheduledOneTimeDeliversOnceAndDoesNotReplayAfterRestart() {
        val db = database(); val zone = ZoneId.of("UTC"); val date = LocalDate.of(2026, 1, 2); val at = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        db.saveScheduledReminder(DesktopScheduledReminder(text = "one", schedule = DesktopReminderSchedule.OneTime(date.toEpochDay(), 10 * 3_600_000L)))
        val messages = mutableListOf<String>(); val journal = DesktopReminderDeliveryJournal(Files.createTempDirectory("delivery").resolve("journal")); val delivery = DesktopReminderDeliveryService(db, DesktopTimeService(MemoryPreferences(true), zone) { at }, DesktopNotificationAdapter { _, text -> messages += text; true }, journal, { at }, zone)
        delivery.tick(); delivery.tick();
        assertEquals(listOf("one"), messages)
        DesktopReminderDeliveryService(db, DesktopTimeService(MemoryPreferences(true), zone) { at }, DesktopNotificationAdapter { _, text -> messages += text; true }, journal, { at }, zone).tick()
        assertEquals(listOf("one"), messages)
    }

    @Test
    fun weeklyConditionDisabledAndTrackedRunningRecordsAreCheckedAtDelivery() {
        val db = database(); db.addActivity("A"); val activity = db.activities().single().id; val zone = ZoneId.of("UTC"); val now = LocalDate.of(2026, 1, 5).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        db.saveScheduledReminder(DesktopScheduledReminder(text = "weekly", schedule = DesktopReminderSchedule.Weekly(setOf(DayOfWeek.MONDAY), 9 * 3_600_000L), condition = DesktopReminderCondition.ActivityNotTrackedToday(activity)))
        db.saveScheduledReminder(DesktopScheduledReminder(enabled = false, text = "off", schedule = DesktopReminderSchedule.Weekly(setOf(DayOfWeek.MONDAY), 9 * 3_600_000L)))
        val messages = mutableListOf<String>(); val delivery = delivery(db, zone, now, messages)
        delivery.tick(); assertEquals(listOf("weekly"), messages)
        DesktopTimerService(db, MemoryPreferences(true), { now - 1_000 }).start(activity)
        val next = now + 7 * 86_400_000L
        delivery(db, zone, next, messages).tick()
        assertEquals(listOf("weekly"), messages)
    }

    @Test
    fun activityOverrideDeliversRecurrentButRespectsDndAndJournal() {
        val db = database(); db.addActivity("A"); val id = db.activities().single().id; val zone = ZoneId.of("UTC"); val start = LocalDate.of(2026, 1, 5).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        DesktopTimerService(db, MemoryPreferences(true), { start }).start(id)
        db.saveActivityReminderOverride(id, DesktopActivityReminderOverride.Custom(10, true, setOf(DayOfWeek.MONDAY), 0, 0))
        val messages = mutableListOf<String>(); val journal = DesktopReminderDeliveryJournal(Files.createTempDirectory("delivery").resolve("journal"))
        DesktopReminderDeliveryService(db, DesktopTimeService(MemoryPreferences(true), zone) { start + 10_000 }, DesktopNotificationAdapter { _, text -> messages += text; true }, journal, { start + 10_000 }, zone).tick()
        DesktopReminderDeliveryService(db, DesktopTimeService(MemoryPreferences(true), zone) { start + 10_000 }, DesktopNotificationAdapter { _, text -> messages += text; true }, journal, { start + 10_000 }, zone).tick()
        assertEquals(1, messages.size)
        db.saveActivityReminderOverride(id, DesktopActivityReminderOverride.Custom(10, true, setOf(DayOfWeek.MONDAY), 11 * 3_600_000L, 13 * 3_600_000L))
        DesktopReminderDeliveryService(db, DesktopTimeService(MemoryPreferences(true), zone) { start + 20_000 }, DesktopNotificationAdapter { _, text -> messages += text; true }, journal, { start + 20_000 }, zone).tick()
        assertEquals(1, messages.size)
    }

    private fun delivery(db: DesktopDatabase, zone: ZoneId, now: Long, messages: MutableList<String>) = DesktopReminderDeliveryService(db, DesktopTimeService(MemoryPreferences(true), zone) { now }, DesktopNotificationAdapter { _, text -> messages += text; true }, DesktopReminderDeliveryJournal(Files.createTempDirectory("delivery").resolve("journal")), { now }, zone)
    private fun database() = DesktopDatabase(Files.createTempDirectory("delivery-db").resolve("tracker.sqlite3"))
}
