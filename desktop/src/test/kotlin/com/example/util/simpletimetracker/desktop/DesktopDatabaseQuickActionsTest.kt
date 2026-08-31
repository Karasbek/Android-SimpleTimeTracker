package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopDatabaseQuickActionsTest {
    @Test
    fun sharedTimerServiceSupportsMultipleRunningActivities() {
        val database = temporaryDatabase()
        val timer = DesktopTimerService(database, MemoryPreferences(true), clock(100, 200))
        database.addActivity("One")
        database.addActivity("Two")
        val ids = database.activities().associate { it.name to it.id }

        timer.start(ids.getValue("One"))
        timer.start(ids.getValue("Two"))

        assertEquals(2, database.activities().count { it.startedAt != null })
    }

    @Test
    fun completedRecordPreservesRunningData() {
        val database = temporaryDatabase()
        database.addActivity("One")
        val id = database.activities().single().id
        database.addRunningRecord(DesktopRunningRecord(id, 100, "note", 42))

        assertEquals(true, database.completeRunningRecord(id, 250))

        DriverManager.getConnection("jdbc:sqlite:${database.path}").use { connection ->
            connection.prepareStatement(
                "SELECT type_id, time_started, time_ended, comment, tag_id FROM records",
            ).use { query ->
                query.executeQuery().use { result ->
                    result.next()
                    assertEquals(id, result.getLong("type_id"))
                    assertEquals(100, result.getLong("time_started"))
                    assertEquals(250, result.getLong("time_ended"))
                    assertEquals("note", result.getString("comment"))
                    assertEquals(42, result.getLong("tag_id"))
                }
            }
        }
    }

    @Test
    fun previousCompletedActivitySkipsArchivedAndInstantTypes() {
        val database = temporaryDatabase()
        val timer = DesktopTimerService(database, MemoryPreferences(true), clock(100, 200, 300, 400))
        database.addActivity("One")
        database.addActivity("Two")
        val ids = database.activities().associate { it.name to it.id }
        timer.toggle(ids.getValue("One"))
        timer.toggle(ids.getValue("One"))
        timer.toggle(ids.getValue("Two"))
        timer.toggle(ids.getValue("Two"))
        assertEquals(ids.getValue("Two"), database.previousCompletedActivityId())

        database.archiveActivity(ids.getValue("Two"))
        assertEquals(ids.getValue("One"), database.previousCompletedActivityId())
        DriverManager.getConnection("jdbc:sqlite:${database.path}").use { connection ->
            connection.prepareStatement("UPDATE recordTypes SET instantDuration = 60 WHERE id = ?").use {
                it.setLong(1, ids.getValue("One"))
                it.executeUpdate()
            }
        }
        assertNull(database.previousCompletedActivityId())
    }

    private fun temporaryDatabase(): DesktopDatabase = DesktopDatabase(
        Files.createTempDirectory("desktop-database-test").resolve("tracker.sqlite3"),
    )
}

internal class MemoryPreferences(
    override var allowMultitasking: Boolean,
) : DesktopSemanticPreferences

internal fun clock(vararg timestamps: Long): () -> Long {
    val values = timestamps.iterator()
    return { values.nextLong() }
}
