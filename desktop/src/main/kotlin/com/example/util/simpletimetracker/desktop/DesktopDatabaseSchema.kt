package com.example.util.simpletimetracker.desktop

import java.sql.Connection

internal object DesktopDatabaseSchema {
    const val CURRENT_VERSION = 3
    private const val LEGACY_VERSION = 1

    fun initialize(connection: Connection) {
        connection.autoCommit = false
        try {
            val version = userVersion(connection)
            when {
                version == 0 && isEmpty(connection) -> createCurrent(connection)
                version == 0 -> {
                    validateLegacySchema(connection)
                    setUserVersion(connection, LEGACY_VERSION)
                    migrate(connection, LEGACY_VERSION)
                }
                version in 1 until CURRENT_VERSION -> migrate(connection, version)
                version == CURRENT_VERSION -> Unit
                else -> error("Unsupported desktop database schema version: $version")
            }
            connection.commit()
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun migrate(connection: Connection, fromVersion: Int) {
        var version = fromVersion
        while (version < CURRENT_VERSION) {
            when (version) {
                1 -> migrate1To2(connection)
                2 -> migrate2To3(connection)
                else -> error("Missing desktop database migration from version $version")
            }
            version++
            setUserVersion(connection, version)
        }
    }

    private fun createCurrent(connection: Connection) {
        createTables(connection)
        createIndexes(connection)
        setUserVersion(connection, CURRENT_VERSION)
    }

    private fun migrate1To2(connection: Connection) {
        createIndexes(connection)
    }

    private fun migrate2To3(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                "ALTER TABLE recordTypes ADD COLUMN default_duration INTEGER NOT NULL DEFAULT 0",
            )
            statement.execute(
                "UPDATE recordTypes SET default_duration = instantDuration WHERE instantDuration > 0",
            )
        }
    }

    private fun createTables(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE recordTypes (
                    id INTEGER PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    icon TEXT NOT NULL,
                    color INTEGER NOT NULL,
                    color_int TEXT NOT NULL,
                    hidden INTEGER NOT NULL,
                    instant INTEGER NOT NULL,
                    instantDuration INTEGER NOT NULL,
                    default_duration INTEGER NOT NULL DEFAULT 0,
                    note TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE runningRecords (
                    id INTEGER PRIMARY KEY NOT NULL,
                    time_started INTEGER NOT NULL,
                    comment TEXT NOT NULL,
                    tag_id INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE records (
                    id INTEGER PRIMARY KEY NOT NULL,
                    type_id INTEGER NOT NULL,
                    time_started INTEGER NOT NULL,
                    time_ended INTEGER NOT NULL,
                    comment TEXT NOT NULL,
                    tag_id INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE desktop_id_allocator (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    namespace INTEGER NOT NULL,
                    next_counter INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    private fun createIndexes(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                "CREATE INDEX IF NOT EXISTS index_records_type_ended ON records(type_id, time_ended)",
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS index_records_ended ON records(time_ended)",
            )
        }
    }

    private fun validateLegacySchema(connection: Connection) {
        val required = setOf("recordTypes", "runningRecords", "records", "desktop_id_allocator")
        val actual = connection.prepareStatement(
            "SELECT name FROM sqlite_master WHERE type = 'table'",
        ).use { query ->
            query.executeQuery().use { result ->
                buildSet { while (result.next()) add(result.getString(1)) }
            }
        }
        check(actual.containsAll(required)) {
            "Unversioned desktop database has an unsupported schema"
        }
    }

    private fun isEmpty(connection: Connection): Boolean {
        return connection.prepareStatement(
            "SELECT COUNT(*) FROM sqlite_master WHERE type IN ('table', 'index') AND name NOT LIKE 'sqlite_%'",
        ).use { query ->
            query.executeQuery().use { result -> result.next() && result.getInt(1) == 0 }
        }
    }

    private fun userVersion(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                check(result.next())
                result.getInt(1)
            }
        }

    private fun setUserVersion(connection: Connection, version: Int) {
        connection.createStatement().use { it.execute("PRAGMA user_version = $version") }
    }
}
