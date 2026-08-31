package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopDatabaseMigrationTest {
    @Test
    fun newDatabaseIsCreatedAtCurrentVersion() {
        val database = DesktopDatabase(temporaryPath())

        assertEquals(DesktopDatabaseSchema.CURRENT_VERSION, userVersion(database.path))
        assertTrue(tableNames(database.path).containsAll(EXPECTED_TABLES))
    }

    @Test
    fun unversionedLegacyDatabaseMigratesWithoutDataLoss() {
        val path = temporaryPath()
        createLegacyDatabase(path, version = 0)

        val database = DesktopDatabase(path)

        assertEquals(DesktopDatabaseSchema.CURRENT_VERSION, userVersion(path))
        assertEquals(listOf(ActivityRow(1, "Running", 100)), database.activities())
        assertEquals(1, database.historyToday().size)
        assertEquals(1, database.runningRecords().size)
        assertEquals(1, rowCount(path, "records"))
    }

    @Test
    fun previousVersionMigratesSequentiallyWithoutDataLoss() {
        val path = temporaryPath()
        createLegacyDatabase(path, version = 1)

        val database = DesktopDatabase(path)

        assertEquals(DesktopDatabaseSchema.CURRENT_VERSION, userVersion(path))
        assertEquals(listOf(ActivityRow(1, "Running", 100)), database.activities())
        assertEquals(1, database.runningRecords().size)
        assertEquals(1, rowCount(path, "records"))
    }

    @Test
    fun reopeningCurrentDatabaseIsIdempotent() {
        val path = temporaryPath()
        val first = DesktopDatabase(path)
        first.addActivity("Persisted")

        val second = DesktopDatabase(path)

        assertEquals(DesktopDatabaseSchema.CURRENT_VERSION, userVersion(path))
        assertEquals(listOf("Persisted"), second.activities().map(ActivityRow::name))
    }

    private fun createLegacyDatabase(path: Path, version: Int) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE recordTypes (id INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL, " +
                        "icon TEXT NOT NULL, color INTEGER NOT NULL, color_int TEXT NOT NULL, " +
                        "hidden INTEGER NOT NULL, instant INTEGER NOT NULL, instantDuration INTEGER NOT NULL, " +
                        "note TEXT NOT NULL)",
                )
                statement.execute(
                    "CREATE TABLE runningRecords (id INTEGER PRIMARY KEY NOT NULL, time_started INTEGER NOT NULL, " +
                        "comment TEXT NOT NULL, tag_id INTEGER NOT NULL)",
                )
                statement.execute(
                    "CREATE TABLE records (id INTEGER PRIMARY KEY NOT NULL, type_id INTEGER NOT NULL, " +
                        "time_started INTEGER NOT NULL, time_ended INTEGER NOT NULL, comment TEXT NOT NULL, " +
                        "tag_id INTEGER NOT NULL)",
                )
                statement.execute(
                    "CREATE TABLE desktop_id_allocator (id INTEGER PRIMARY KEY CHECK (id = 1), " +
                        "namespace INTEGER NOT NULL, next_counter INTEGER NOT NULL)",
                )
                statement.execute(
                    "INSERT INTO recordTypes VALUES (1, 'Running', '', 0, '', 0, 0, 0, '')",
                )
                statement.execute("INSERT INTO runningRecords VALUES (1, 100, 'running note', 0)")
                val now = System.currentTimeMillis()
                statement.execute("INSERT INTO records VALUES (2, 1, ${now - 1000}, $now, 'done note', 0)")
                statement.execute("INSERT INTO desktop_id_allocator VALUES (1, 123, 3)")
                statement.execute("PRAGMA user_version = $version")
            }
        }
    }

    private fun userVersion(path: Path): Int = connection(path) { db ->
        db.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private fun tableNames(path: Path): Set<String> = connection(path) { db ->
        db.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table'").use { query ->
            query.executeQuery().use { result ->
                buildSet { while (result.next()) add(result.getString(1)) }
            }
        }
    }

    private fun rowCount(path: Path, table: String): Int = connection(path) { db ->
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private fun <T> connection(path: Path, block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:$path").use(block)

    private fun temporaryPath(): Path =
        Files.createTempDirectory("desktop-migration-test").resolve("tracker.sqlite3")

    companion object {
        private val EXPECTED_TABLES = setOf(
            "recordTypes",
            "runningRecords",
            "records",
            "desktop_id_allocator",
        )
    }
}
