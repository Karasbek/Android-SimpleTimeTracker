package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopTimerLifecycleSemanticsTest {
    @Test
    fun ordinaryActivityWithoutDefaultDurationKeepsRunningTimerBehavior() {
        val database = database()
        database.addActivity("Ordinary")
        val activity = database.activities().single()
        val service = DesktopTimerService(database, MemoryPreferences(true), clock(100))

        assertEquals(TimerActionResult.STARTED, service.start(activity.id))
        assertEquals(listOf(activity.id), database.runningRecords().map(DesktopRunningRecord::activityId))
        assertEquals(0, recordCount(database))
    }

    @Test
    fun defaultDurationCreatesInstantCompletedRecordWithoutRunningState() {
        val database = database()
        database.addActivity("Instant")
        val activity = database.activities().single()
        database.setActivityDefaultDuration(activity.id, 60)
        val service = DesktopTimerService(database, MemoryPreferences(true), clock(1_000, 2_000))

        assertEquals(TimerActionResult.COMPLETED, service.toggle(activity.id))
        assertTrue(database.runningRecords().isEmpty())
        assertEquals(listOf(1_000L to 61_000L), recordTimes(database))

        assertEquals(TimerActionResult.COMPLETED, service.toggle(activity.id))
        assertEquals(2, recordCount(database))
    }

    @Test
    fun instantCompletedRecordIsNotDiscardedByShortRunningRecordPolicy() {
        val database = database()
        database.addActivity("Instant")
        val id = database.activities().single().id
        database.setActivityDefaultDuration(id, 1)
        val service = DesktopTimerService(database, MemoryPreferences(true, 60), clock(1_000))

        assertEquals(TimerActionResult.COMPLETED, service.start(id))
        assertEquals(listOf(1_000L to 2_000L), recordTimes(database))
    }

    @Test
    fun defaultDurationPersistsAfterReopenAndRepeatExcludesInstantActivity() {
        val path = Files.createTempDirectory("desktop-timer-semantics").resolve("tracker.sqlite3")
        val first = DesktopDatabase(path)
        first.addActivity("Instant")
        val activity = first.activities().single()
        first.setActivityDefaultDuration(activity.id, 15)

        val reopened = DesktopDatabase(path)
        assertEquals(15L, reopened.activities().single().defaultDurationSeconds)
        val service = DesktopTimerService(reopened, MemoryPreferences(true), clock(100))
        service.start(activity.id)

        assertEquals(null, reopened.previousCompletedActivityId())
    }

    @Test
    fun instantStartHonorsMultitaskingByStoppingOtherTimersThroughSamePolicy() {
        val database = database()
        database.addActivity("Running")
        database.addActivity("Instant")
        val ids = database.activities().associate { it.name to it.id }
        database.setActivityDefaultDuration(ids.getValue("Instant"), 10)
        val service = DesktopTimerService(database, MemoryPreferences(false, 3), clock(100, 200))

        service.start(ids.getValue("Running"))
        assertEquals(TimerActionResult.COMPLETED, service.start(ids.getValue("Instant")))

        assertTrue(database.runningRecords().isEmpty())
        assertEquals(listOf(200L to 10_200L), recordTimes(database))
    }

    @Test
    fun tooShortRecordsAreDiscardedButNormalRecordsAreSaved() {
        val database = database()
        database.addActivity("One")
        val id = database.activities().single().id
        val preferences = MemoryPreferences(true, 3)
        val service = DesktopTimerService(database, preferences, clock(100, 3_100, 10_000, 14_000))

        service.start(id)
        service.stop(id)
        assertEquals(0, recordCount(database))
        assertTrue(database.runningRecords().isEmpty())

        service.start(id)
        service.stop(id)
        assertEquals(listOf(10_000L to 14_000L), recordTimes(database))
    }

    @Test
    fun quickActionsUseSameShortRecordPolicyForMultitaskingSwitch() {
        val database = database()
        database.addActivity("A")
        database.addActivity("B")
        val ids = database.activities().associate { it.name to it.id }
        val preferences = MemoryPreferences(false, 3)
        val service = DesktopTimerService(database, preferences, clock(100, 200))
        val actions = DesktopQuickActions(
            database,
            service,
            DesktopPinnedActivitiesStore(Files.createTempDirectory("desktop-pins").resolve("pins")),
        )

        actions.toggle(ids.getValue("A"))
        actions.toggle(ids.getValue("B"))

        assertEquals(listOf(ids.getValue("B")), database.runningRecords().map(DesktopRunningRecord::activityId))
        assertEquals(0, recordCount(database))
    }

    @Test
    fun quickActionsUseSameInstantDomainFlow() {
        val database = database()
        database.addActivity("Instant")
        val id = database.activities().single().id
        database.setActivityDefaultDuration(id, 20)
        val service = DesktopTimerService(database, MemoryPreferences(true), clock(500))
        val actions = DesktopQuickActions(
            database,
            service,
            DesktopPinnedActivitiesStore(Files.createTempDirectory("desktop-pins").resolve("pins")),
        )

        actions.toggle(id)

        assertTrue(database.runningRecords().isEmpty())
        assertEquals(listOf(500L to 20_500L), recordTimes(database))
    }

    private fun database(): DesktopDatabase = DesktopDatabase(
        Files.createTempDirectory("desktop-timer-semantics").resolve("tracker.sqlite3"),
    )

    private fun recordCount(database: DesktopDatabase): Int =
        DriverManager.getConnection("jdbc:sqlite:${database.path}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM records").use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }

    private fun recordTimes(database: DesktopDatabase): List<Pair<Long, Long>> =
        DriverManager.getConnection("jdbc:sqlite:${database.path}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT time_started, time_ended FROM records ORDER BY id").use { result ->
                    buildList {
                        while (result.next()) add(result.getLong(1) to result.getLong(2))
                    }
                }
            }
        }
}
