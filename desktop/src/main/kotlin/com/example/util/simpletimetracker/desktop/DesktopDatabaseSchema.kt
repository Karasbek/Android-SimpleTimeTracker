package com.example.util.simpletimetracker.desktop

import java.sql.Connection

internal object DesktopDatabaseSchema {
    const val CURRENT_VERSION = 8
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
                3 -> migrate3To4(connection)
                4 -> migrate4To5(connection)
                5 -> migrate5To6(connection)
                6 -> migrate6To7(connection)
                7 -> migrate7To8(connection)
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
        createCoreIndexes(connection)
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

    private fun migrate3To4(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                "CREATE INDEX IF NOT EXISTS index_records_type_started ON records(type_id, time_started)",
            )
        }
    }

    private fun migrate4To5(connection: Connection) {
        createTagCategoryTables(connection)
        createTagCategoryIndexes(connection)
    }

    private fun migrate5To6(connection: Connection) {
        createSavedFilterTables(connection)
        createSavedFilterIndexes(connection)
    }

    private fun migrate6To7(connection: Connection) {
        connection.createStatement().use { statement ->
            if (!hasColumn(connection, "categories", "color")) statement.execute("ALTER TABLE categories ADD COLUMN color INTEGER NOT NULL DEFAULT 0")
            if (!hasColumn(connection, "categories", "color_int")) statement.execute("ALTER TABLE categories ADD COLUMN color_int TEXT NOT NULL DEFAULT ''")
            if (!hasColumn(connection, "categories", "note")) statement.execute("ALTER TABLE categories ADD COLUMN note TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * v8 keeps saved-filter entity selections normalized and adds only scalar
     * RecordsFilter properties. Existing v6 filters remain exact empty/default
     * instances of these new criteria.
     */
    private fun migrate7To8(connection: Connection) {
        connection.createStatement().use { statement ->
            listOf(
                "comment_mode TEXT NOT NULL DEFAULT 'ANY'",
                "comment_query TEXT NOT NULL DEFAULT ''",
                "date_started INTEGER",
                "date_ended INTEGER",
                "time_of_day_start INTEGER",
                "time_of_day_end INTEGER",
                "duration_min INTEGER",
                "duration_max INTEGER",
                "show_untracked INTEGER NOT NULL DEFAULT 0 CHECK (show_untracked IN (0, 1))",
                "multitask_only INTEGER NOT NULL DEFAULT 0 CHECK (multitask_only IN (0, 1))",
                "duplicates_mode TEXT NOT NULL DEFAULT 'NONE'",
                "record_kind TEXT NOT NULL DEFAULT 'ALL'",
            ).forEach { definition ->
                val column = definition.substringBefore(' ')
                if (!hasColumn(connection, "saved_record_filters", column)) {
                    statement.execute("ALTER TABLE saved_record_filters ADD COLUMN $definition")
                }
            }
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS saved_record_filter_days_of_week (
                    filter_id INTEGER NOT NULL,
                    day_of_week INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
                    PRIMARY KEY (filter_id, day_of_week),
                    FOREIGN KEY (filter_id) REFERENCES saved_record_filters(id) ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
        }
    }

    private fun hasColumn(connection: Connection, table: String, column: String): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { result ->
                generateSequence { if (result.next()) result.getString("name") else null }.any { it == column }
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
        createTagCategoryTables(connection)
        createSavedFilterTables(connection)
        createAdvancedSavedFilterTables(connection)
    }

    private fun createIndexes(connection: Connection) {
        createCoreIndexes(connection)
        createTagCategoryIndexes(connection)
        createSavedFilterIndexes(connection)
    }

    private fun createCoreIndexes(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                "CREATE INDEX IF NOT EXISTS index_records_type_ended ON records(type_id, time_ended)",
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS index_records_ended ON records(time_ended)",
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS index_records_type_started ON records(type_id, time_started)",
            )
        }
    }

    private fun createTagCategoryTables(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS record_tags (
                    id INTEGER PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
                    value_type TEXT NOT NULL DEFAULT 'NONE' CHECK (value_type IN ('NONE', 'NUMERIC')),
                    value_suffix TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS record_to_tag (
                    record_id INTEGER NOT NULL,
                    tag_id INTEGER NOT NULL,
                    numeric_value REAL,
                    PRIMARY KEY (record_id, tag_id),
                    FOREIGN KEY (record_id) REFERENCES records(id) ON DELETE RESTRICT,
                    FOREIGN KEY (tag_id) REFERENCES record_tags(id) ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS running_record_to_tag (
                    running_record_id INTEGER NOT NULL,
                    tag_id INTEGER NOT NULL,
                    numeric_value REAL,
                    PRIMARY KEY (running_record_id, tag_id),
                    FOREIGN KEY (running_record_id) REFERENCES runningRecords(id) ON DELETE RESTRICT,
                    FOREIGN KEY (tag_id) REFERENCES record_tags(id) ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS record_type_to_tag (
                    record_type_id INTEGER NOT NULL,
                    tag_id INTEGER NOT NULL,
                    PRIMARY KEY (record_type_id, tag_id),
                    FOREIGN KEY (record_type_id) REFERENCES recordTypes(id) ON DELETE RESTRICT,
                    FOREIGN KEY (tag_id) REFERENCES record_tags(id) ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS record_type_to_default_tag (
                    record_type_id INTEGER NOT NULL,
                    tag_id INTEGER NOT NULL,
                    PRIMARY KEY (record_type_id, tag_id),
                    FOREIGN KEY (record_type_id) REFERENCES recordTypes(id) ON DELETE RESTRICT,
                    FOREIGN KEY (tag_id) REFERENCES record_tags(id) ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS categories (
                    id INTEGER PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    color INTEGER NOT NULL DEFAULT 0,
                    color_int TEXT NOT NULL DEFAULT '',
                    note TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS record_type_category (
                    record_type_id INTEGER NOT NULL,
                    category_id INTEGER NOT NULL,
                    PRIMARY KEY (record_type_id, category_id),
                    FOREIGN KEY (record_type_id) REFERENCES recordTypes(id) ON DELETE RESTRICT,
                    FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
        }
    }

    private fun createTagCategoryIndexes(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("CREATE INDEX IF NOT EXISTS index_record_to_tag_tag ON record_to_tag(tag_id)")
            statement.execute("CREATE INDEX IF NOT EXISTS index_running_record_to_tag_tag ON running_record_to_tag(tag_id)")
            statement.execute("CREATE INDEX IF NOT EXISTS index_record_type_to_tag_tag ON record_type_to_tag(tag_id)")
            statement.execute("CREATE INDEX IF NOT EXISTS index_record_type_to_default_tag_tag ON record_type_to_default_tag(tag_id)")
            statement.execute("CREATE INDEX IF NOT EXISTS index_record_type_category_category ON record_type_category(category_id)")
        }
    }

    private fun createSavedFilterTables(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS saved_record_filters (
                    id INTEGER PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    include_uncategorized INTEGER NOT NULL DEFAULT 0 CHECK (include_uncategorized IN (0, 1)),
                    exclude_uncategorized INTEGER NOT NULL DEFAULT 0 CHECK (exclude_uncategorized IN (0, 1)),
                    include_untagged INTEGER NOT NULL DEFAULT 0 CHECK (include_untagged IN (0, 1)),
                    exclude_untagged INTEGER NOT NULL DEFAULT 0 CHECK (exclude_untagged IN (0, 1)),
                    comment_mode TEXT NOT NULL DEFAULT 'ANY',
                    comment_query TEXT NOT NULL DEFAULT '',
                    date_started INTEGER,
                    date_ended INTEGER,
                    time_of_day_start INTEGER,
                    time_of_day_end INTEGER,
                    duration_min INTEGER,
                    duration_max INTEGER,
                    show_untracked INTEGER NOT NULL DEFAULT 0 CHECK (show_untracked IN (0, 1)),
                    multitask_only INTEGER NOT NULL DEFAULT 0 CHECK (multitask_only IN (0, 1)),
                    duplicates_mode TEXT NOT NULL DEFAULT 'NONE',
                    record_kind TEXT NOT NULL DEFAULT 'ALL'
                )
                """.trimIndent(),
            )
            listOf("activities", "tags", "categories").forEach { kind ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS saved_record_filter_$kind (
                        filter_id INTEGER NOT NULL,
                        entity_id INTEGER NOT NULL,
                        mode TEXT NOT NULL CHECK (mode IN ('INCLUDE', 'EXCLUDE')),
                        PRIMARY KEY (filter_id, entity_id, mode),
                        FOREIGN KEY (filter_id) REFERENCES saved_record_filters(id) ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    private fun createAdvancedSavedFilterTables(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS saved_record_filter_days_of_week (
                    filter_id INTEGER NOT NULL,
                    day_of_week INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
                    PRIMARY KEY (filter_id, day_of_week),
                    FOREIGN KEY (filter_id) REFERENCES saved_record_filters(id) ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
        }
    }

    private fun createSavedFilterIndexes(connection: Connection) {
        connection.createStatement().use { statement ->
            listOf("activities", "tags", "categories").forEach { kind ->
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS index_saved_record_filter_${kind}_entity " +
                        "ON saved_record_filter_$kind(entity_id)",
                )
            }
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
