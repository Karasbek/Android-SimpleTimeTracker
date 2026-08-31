package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopDatabaseQuickActionsTest {

    @Test
    fun sharedToggleSupportsMultipleRunningActivities() {
        val database = databaseWithClock(100, 200)
        database.addActivity("One")
        database.addActivity("Two")
        val ids = database.activities().associate { it.name to it.id }

        database.toggle(ids.getValue("One"))
        database.toggle(ids.getValue("Two"))

        assertEquals(2, database.activities().count { it.startedAt != null })
    }

    @Test
    fun previousCompletedActivitySkipsArchivedAndInstantTypes() {
        val database = databaseWithClock(100, 200, 300, 400)
        database.addActivity("One")
        database.addActivity("Two")
        val ids = database.activities().associate { it.name to it.id }

        database.toggle(ids.getValue("One"))
        database.toggle(ids.getValue("One"))
        database.toggle(ids.getValue("Two"))
        database.toggle(ids.getValue("Two"))
        assertEquals(ids.getValue("Two"), database.previousCompletedActivityId())

        database.archiveActivity(ids.getValue("Two"))
        assertEquals(ids.getValue("One"), database.previousCompletedActivityId())

        DriverManager.getConnection("jdbc:sqlite:${database.path}").use { connection ->
            connection.prepareStatement(
                "UPDATE recordTypes SET instantDuration = 60 WHERE id = ?",
            ).use { update ->
                update.setLong(1, ids.getValue("One"))
                update.executeUpdate()
            }
        }
        assertNull(database.previousCompletedActivityId())
    }

    private fun databaseWithClock(vararg timestamps: Long): DesktopDatabase {
        val values = timestamps.iterator()
        val path = Files.createTempDirectory("desktop-database-test").resolve("tracker.sqlite3")
        return DesktopDatabase(path) { values.nextLong() }
    }
}
