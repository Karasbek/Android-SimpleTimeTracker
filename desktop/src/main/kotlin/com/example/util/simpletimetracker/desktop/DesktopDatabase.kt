package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ActivityRow(
    val id: Long,
    val name: String,
    val startedAt: Long?,
    val defaultDurationSeconds: Long = 0,
)

data class HistoryRow(
    val id: Long,
    val activityName: String,
    val startedAt: Long,
    val endedAt: Long,
)

class DesktopDatabase(
    databasePath: Path? = null,
) : DesktopTimerRepository,
    DesktopRecordRepository,
    DesktopTagCategoryRepository {

    val path: Path

    init {
        Class.forName("org.sqlite.JDBC")

        path = databasePath ?: run {
            val dataHome = System.getenv("XDG_DATA_HOME")
                ?.takeIf { it.isNotBlank() }
                ?.let(Paths::get)
                ?: Paths.get(System.getProperty("user.home"), ".local", "share")
            dataHome.resolve("simple-time-tracker").resolve("tracker.sqlite3")
        }
        Files.createDirectories(path.parent)

        connection().use { db ->
            db.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=NORMAL")
                statement.execute("PRAGMA busy_timeout=5000")
            }
            DesktopDatabaseSchema.initialize(db)

            db.prepareStatement(
                "SELECT COUNT(*) FROM desktop_id_allocator WHERE id = 1",
            ).use { query ->
                query.executeQuery().use { result ->
                    result.next()
                    if (result.getInt(1) == 0) {
                        val namespace = SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1
                        db.prepareStatement(
                            "INSERT INTO desktop_id_allocator(id, namespace, next_counter) VALUES(1, ?, 1)",
                        ).use { insert ->
                            insert.setLong(1, namespace.toLong())
                            insert.executeUpdate()
                        }
                    }
                }
            }
        }
    }

    private fun connection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").also { db ->
            db.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout=5000")
                statement.execute("PRAGMA foreign_keys=ON")
            }
        }

    internal fun nextId(db: Connection): Long {
        val values = db.prepareStatement(
            "SELECT namespace, next_counter FROM desktop_id_allocator WHERE id = 1",
        ).use { query ->
            query.executeQuery().use { result ->
                check(result.next())
                result.getLong("namespace") to result.getLong("next_counter")
            }
        }

        val namespace = values.first
        val counter = values.second
        check(counter in 1..0xffffffffL) { "ID counter exhausted" }

        db.prepareStatement(
            "UPDATE desktop_id_allocator SET next_counter = ? WHERE id = 1",
        ).use { update ->
            update.setLong(1, counter + 1)
            update.executeUpdate()
        }

        return (namespace shl 32) or counter
    }

    fun addActivity(name: String) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return

        connection().use { db ->
            db.autoCommit = false
            try {
                val id = nextId(db)
                db.prepareStatement(
                    """
                    INSERT INTO recordTypes(
                        id, name, icon, color, color_int, hidden,
                        instant, instantDuration, default_duration, note
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { insert ->
                    insert.setLong(1, id)
                    insert.setString(2, cleanName)
                    insert.setString(3, "")
                    insert.setInt(4, 0)
                    insert.setString(5, "")
                    insert.setInt(6, 0)
                    insert.setInt(7, 0)
                    insert.setLong(8, 0)
                    insert.setLong(9, 0)
                    insert.setString(10, "")
                    insert.executeUpdate()
                }
                db.commit()
            } catch (e: Throwable) {
                db.rollback()
                throw e
            }
        }
    }

    override fun activities(): List<ActivityRow> {
        return connection().use { db ->
            db.prepareStatement(
                """
                SELECT
                    rt.id,
                    rt.name,
                    rr.time_started,
                    rt.default_duration
                FROM recordTypes rt
                LEFT JOIN runningRecords rr ON rr.id = rt.id
                WHERE rt.hidden = 0
                ORDER BY rt.name COLLATE NOCASE
                """.trimIndent(),
            ).use { query ->
                query.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            val rawStartedAt = result.getLong("time_started")
                            val startedAt = if (result.wasNull()) null else rawStartedAt
                            add(
                                ActivityRow(
                                    id = result.getLong("id"),
                                    name = result.getString("name"),
                                    startedAt = startedAt,
                                    defaultDurationSeconds = result.getLong("default_duration"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun runningRecords(): List<DesktopRunningRecord> {
        return connection().use { db ->
            db.prepareStatement(
                "SELECT id, time_started, comment, tag_id FROM runningRecords ORDER BY time_started, id",
            ).use { query ->
                query.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                DesktopRunningRecord(
                                    activityId = result.getLong("id"),
                                    startedAt = result.getLong("time_started"),
                                    comment = result.getString("comment"),
                                    tagId = result.getLong("tag_id"),
                                    tags = runningRecordTags(db, result.getLong("id")),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun addRunningRecord(record: DesktopRunningRecord): Boolean {
        connection().use { db ->
            db.autoCommit = false
            try {
                val available = db.prepareStatement(
                    "SELECT COUNT(*) FROM recordTypes WHERE id = ? AND hidden = 0",
                ).use { query ->
                    query.setLong(1, record.activityId)
                    query.executeQuery().use { result ->
                        result.next() && result.getInt(1) == 1
                    }
                }
                if (!available) {
                    db.rollback()
                    return false
                }
                val inserted = db.prepareStatement(
                    "INSERT OR IGNORE INTO runningRecords(id, time_started, comment, tag_id) VALUES(?, ?, ?, ?)",
                ).use { insert ->
                    insert.setLong(1, record.activityId)
                    insert.setLong(2, record.startedAt)
                    insert.setString(3, record.comment)
                    insert.setLong(4, record.tagId)
                    insert.executeUpdate() == 1
                }
                if (inserted) {
                    replaceRunningRecordTags(db, record.activityId, record.tags)
                }
                db.commit()
                return inserted
            } catch (e: Throwable) {
                db.rollback()
                throw e
            }
        }
    }

    override fun addCompletedRecord(
        activityId: Long,
        startedAt: Long,
        endedAt: Long,
        comment: String,
        tagId: Long,
    ): Boolean = addCompletedRecordWithTags(
        activityId = activityId,
        startedAt = startedAt,
        endedAt = endedAt,
        comment = comment,
        tags = emptyList(),
    )

    override fun addCompletedRecordWithTags(
        activityId: Long,
        startedAt: Long,
        endedAt: Long,
        comment: String,
        tags: List<DesktopRecordTag>,
    ): Boolean {
        connection().use { db ->
            db.autoCommit = false
            try {
                val available = db.prepareStatement(
                    "SELECT COUNT(*) FROM recordTypes WHERE id = ? AND hidden = 0",
                ).use { query ->
                    query.setLong(1, activityId)
                    query.executeQuery().use { result ->
                        result.next() && result.getInt(1) == 1
                    }
                }
                if (!available) {
                    db.rollback()
                    return false
                }
                val recordId = insertCompletedRecord(db, activityId, startedAt, endedAt, comment, 0)
                replaceRecordTags(db, recordId, tags)
                db.commit()
                return true
            } catch (error: Throwable) {
                db.rollback()
                throw error
            }
        }
    }

    override fun updateCompletedRecord(
        recordId: Long,
        activityId: Long,
        startedAt: Long,
        endedAt: Long,
        comment: String,
        tags: List<DesktopRecordTag>,
    ): Boolean {
        connection().use { db ->
            db.autoCommit = false
            try {
                val available = db.prepareStatement(
                    "SELECT COUNT(*) FROM recordTypes WHERE id = ? AND hidden = 0",
                ).use { query ->
                    query.setLong(1, activityId)
                    query.executeQuery().use { result -> result.next() && result.getInt(1) == 1 }
                }
                if (!available) {
                    db.rollback()
                    return false
                }
                val updated = db.prepareStatement(
                    """
                    UPDATE records
                    SET type_id = ?, time_started = ?, time_ended = ?, comment = ?
                    WHERE id = ?
                    """.trimIndent(),
                ).use { update ->
                    update.setLong(1, activityId)
                    update.setLong(2, startedAt)
                    update.setLong(3, endedAt.coerceAtLeast(startedAt))
                    update.setString(4, comment)
                    update.setLong(5, recordId)
                    update.executeUpdate() == 1
                }
                if (updated) {
                    replaceRecordTags(db, recordId, tags)
                }
                db.commit()
                return updated
            } catch (error: Throwable) {
                db.rollback()
                throw error
            }
        }
    }

    override fun deleteCompletedRecord(recordId: Long): Boolean {
        return connection().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("DELETE FROM record_to_tag WHERE record_id = ?").use { delete ->
                    delete.setLong(1, recordId)
                    delete.executeUpdate()
                }
                val deleted = db.prepareStatement("DELETE FROM records WHERE id = ?").use { delete ->
                    delete.setLong(1, recordId)
                    delete.executeUpdate() == 1
                }
                db.commit()
                deleted
            } catch (error: Throwable) {
                db.rollback()
                throw error
            }
        }
    }

    override fun completeRunningRecord(activityId: Long, endedAt: Long): Boolean {
        connection().use { db ->
            db.autoCommit = false
            try {
                val running = db.prepareStatement(
                    "SELECT time_started, comment, tag_id FROM runningRecords WHERE id = ?",
                ).use { query ->
                    query.setLong(1, activityId)
                    query.executeQuery().use { result ->
                        if (result.next()) {
                            Triple(
                                result.getLong("time_started"),
                                result.getString("comment"),
                                result.getLong("tag_id"),
                            )
                        } else {
                            null
                        }
                    }
                } ?: run {
                    db.rollback()
                    return false
                }
                val tags = runningRecordTags(db, activityId)
                val actualEndedAt = endedAt.coerceAtLeast(running.first)
                val recordId = insertCompletedRecord(
                    db,
                    activityId,
                    running.first,
                    actualEndedAt,
                    running.second,
                    running.third,
                )
                replaceRecordTags(db, recordId, tags)
                db.prepareStatement("DELETE FROM running_record_to_tag WHERE running_record_id = ?").use { delete ->
                    delete.setLong(1, activityId)
                    delete.executeUpdate()
                }
                db.prepareStatement("DELETE FROM runningRecords WHERE id = ?").use { delete ->
                    delete.setLong(1, activityId)
                    check(delete.executeUpdate() == 1)
                }
                db.commit()
                return true
            } catch (e: Throwable) {
                db.rollback()
                throw e
            }
        }
    }

    override fun discardRunningRecord(activityId: Long): Boolean {
        return connection().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("DELETE FROM running_record_to_tag WHERE running_record_id = ?").use { delete ->
                    delete.setLong(1, activityId)
                    delete.executeUpdate()
                }
                val discarded = db.prepareStatement("DELETE FROM runningRecords WHERE id = ?").use { delete ->
                    delete.setLong(1, activityId)
                    delete.executeUpdate() == 1
                }
                db.commit()
                discarded
            } catch (error: Throwable) {
                db.rollback()
                throw error
            }
        }
    }

    private fun insertCompletedRecord(
        db: Connection,
        activityId: Long,
        startedAt: Long,
        endedAt: Long,
        comment: String,
        tagId: Long,
    ): Long {
        val recordId = nextId(db)
        db.prepareStatement(
            """
            INSERT INTO records(id, type_id, time_started, time_ended, comment, tag_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { insert ->
            insert.setLong(1, recordId)
            insert.setLong(2, activityId)
            insert.setLong(3, startedAt)
            insert.setLong(4, endedAt.coerceAtLeast(startedAt))
            insert.setString(5, comment)
            insert.setLong(6, tagId)
            insert.executeUpdate()
        }
        return recordId
    }

    override fun previousCompletedActivityId(): Long? {
        return connection().use { db ->
            db.prepareStatement(
                """
                SELECT r.type_id
                FROM records r
                JOIN recordTypes rt ON rt.id = r.type_id
                WHERE rt.hidden = 0 AND rt.default_duration = 0
                ORDER BY r.time_ended DESC, r.id DESC
                LIMIT 1
                """.trimIndent(),
            ).use { query ->
                query.executeQuery().use { result ->
                    if (result.next()) result.getLong(1) else null
                }
            }
        }
    }

    override fun previousCompletedRecord(): DesktopPreviousRecord? {
        return connection().use { db ->
            db.prepareStatement(
                """
                SELECT r.id, r.type_id, r.comment
                FROM records r
                JOIN recordTypes rt ON rt.id = r.type_id
                WHERE rt.hidden = 0 AND rt.default_duration = 0
                ORDER BY r.time_ended DESC, r.id DESC
                LIMIT 1
                """.trimIndent(),
            ).use { query ->
                query.executeQuery().use { result ->
                    if (!result.next()) return@use null
                    DesktopPreviousRecord(
                        activityId = result.getLong("type_id"),
                        comment = result.getString("comment"),
                        tags = recordTags(db, result.getLong("id"), activeOnly = true),
                    )
                }
            }
        }
    }

    override fun defaultTagsForActivity(activityId: Long): List<DesktopRecordTag> {
        return connection().use { db ->
            db.prepareStatement(
                """
                SELECT d.tag_id
                FROM record_type_to_default_tag d
                JOIN record_tags t ON t.id = d.tag_id
                WHERE d.record_type_id = ? AND t.archived = 0
                ORDER BY t.name COLLATE NOCASE, t.id
                """.trimIndent(),
            ).use { query ->
                query.setLong(1, activityId)
                query.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(DesktopRecordTag(result.getLong(1), null))
                    }
                }
            }
        }
    }

    override fun tags(): List<DesktopTag> {
        return connection().use { db ->
            db.prepareStatement(
                """
                SELECT id, name, archived, value_type, value_suffix
                FROM record_tags
                ORDER BY archived, name COLLATE NOCASE, id
                """.trimIndent(),
            ).use { query ->
                query.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                DesktopTag(
                                    id = result.getLong("id"),
                                    name = result.getString("name"),
                                    archived = result.getInt("archived") != 0,
                                    valueType = DesktopTagValueType.valueOf(result.getString("value_type")),
                                    valueSuffix = result.getString("value_suffix"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun categories(): List<DesktopCategory> {
        return connection().use { db ->
            db.prepareStatement("SELECT id, name FROM categories ORDER BY name COLLATE NOCASE, id").use { query ->
                query.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(DesktopCategory(result.getLong("id"), result.getString("name")))
                        }
                    }
                }
            }
        }
    }

    override fun saveTag(tagId: Long, draft: DesktopTagDraft): Long {
        return connection().use { db ->
            db.autoCommit = false
            try {
                val id = if (tagId == 0L) nextId(db) else tagId
                val saved = if (tagId == 0L) {
                    db.prepareStatement(
                        """
                        INSERT INTO record_tags(id, name, archived, value_type, value_suffix)
                        VALUES (?, ?, 0, ?, ?)
                        """.trimIndent(),
                    ).use { insert ->
                        insert.setLong(1, id)
                        insert.setString(2, draft.name)
                        insert.setString(3, draft.valueType.name)
                        insert.setString(4, draft.valueSuffix)
                        insert.executeUpdate() == 1
                    }
                } else {
                    db.prepareStatement(
                        "UPDATE record_tags SET name = ?, value_type = ?, value_suffix = ? WHERE id = ?",
                    ).use { update ->
                        update.setString(1, draft.name)
                        update.setString(2, draft.valueType.name)
                        update.setString(3, draft.valueSuffix)
                        update.setLong(4, id)
                        update.executeUpdate() == 1
                    }
                }
                check(saved) { "Тег не найден" }
                db.commit()
                id
            } catch (error: Throwable) {
                db.rollback()
                throw error
            }
        }
    }

    override fun setTagArchived(tagId: Long, archived: Boolean): Boolean {
        return connection().use { db ->
            db.prepareStatement("UPDATE record_tags SET archived = ? WHERE id = ?").use { update ->
                update.setInt(1, if (archived) 1 else 0)
                update.setLong(2, tagId)
                update.executeUpdate() == 1
            }
        }
    }

    override fun deleteTag(tagId: Long): Boolean {
        return connection().use { db ->
            db.autoCommit = false
            try {
                listOf(
                    "DELETE FROM record_to_tag WHERE tag_id = ?",
                    "DELETE FROM running_record_to_tag WHERE tag_id = ?",
                    "DELETE FROM record_type_to_tag WHERE tag_id = ?",
                    "DELETE FROM record_type_to_default_tag WHERE tag_id = ?",
                ).forEach { sql ->
                    db.prepareStatement(sql).use { delete ->
                        delete.setLong(1, tagId)
                        delete.executeUpdate()
                    }
                }
                val deleted = db.prepareStatement("DELETE FROM record_tags WHERE id = ?").use { delete ->
                    delete.setLong(1, tagId)
                    delete.executeUpdate() == 1
                }
                db.commit()
                deleted
            } catch (error: Throwable) {
                db.rollback()
                throw error
            }
        }
    }

    override fun saveCategory(categoryId: Long, draft: DesktopCategoryDraft): Long {
        return connection().use { db ->
            db.autoCommit = false
            try {
                val id = if (categoryId == 0L) nextId(db) else categoryId
                val saved = if (categoryId == 0L) {
                    db.prepareStatement("INSERT INTO categories(id, name) VALUES (?, ?)").use { insert ->
                        insert.setLong(1, id)
                        insert.setString(2, draft.name)
                        insert.executeUpdate() == 1
                    }
                } else {
                    db.prepareStatement("UPDATE categories SET name = ? WHERE id = ?").use { update ->
                        update.setString(1, draft.name)
                        update.setLong(2, id)
                        update.executeUpdate() == 1
                    }
                }
                check(saved) { "Категория не найдена" }
                db.commit()
                id
            } catch (error: Throwable) {
                db.rollback()
                throw error
            }
        }
    }

    override fun deleteCategory(categoryId: Long): Boolean {
        return connection().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("DELETE FROM record_type_category WHERE category_id = ?").use { delete ->
                    delete.setLong(1, categoryId)
                    delete.executeUpdate()
                }
                val deleted = db.prepareStatement("DELETE FROM categories WHERE id = ?").use { delete ->
                    delete.setLong(1, categoryId)
                    delete.executeUpdate() == 1
                }
                db.commit()
                deleted
            } catch (error: Throwable) {
                db.rollback()
                throw error
            }
        }
    }

    override fun categoryIdsForActivity(activityId: Long): Set<Long> = relationIds(
        table = "record_type_category",
        valueColumn = "category_id",
        activityId = activityId,
    )

    override fun allowedTagIdsForActivity(activityId: Long): Set<Long> = relationIds(
        table = "record_type_to_tag",
        valueColumn = "tag_id",
        activityId = activityId,
    )

    override fun defaultTagIdsForActivity(activityId: Long): Set<Long> = relationIds(
        table = "record_type_to_default_tag",
        valueColumn = "tag_id",
        activityId = activityId,
    )

    override fun updateActivityDetails(activityId: Long, draft: DesktopActivityDetailsDraft): Boolean {
        return connection().use { db ->
            db.autoCommit = false
            try {
                val updated = db.prepareStatement(
                    "UPDATE recordTypes SET name = ?, default_duration = ? WHERE id = ?",
                ).use { update ->
                    update.setString(1, draft.name)
                    update.setLong(2, draft.defaultDurationSeconds)
                    update.setLong(3, activityId)
                    update.executeUpdate() == 1
                }
                if (!updated) {
                    db.rollback()
                    return false
                }
                replaceActivityRelations(db, activityId, "record_type_category", "category_id", draft.categoryIds)
                replaceActivityRelations(db, activityId, "record_type_to_tag", "tag_id", draft.allowedTagIds)
                replaceActivityRelations(
                    db,
                    activityId,
                    "record_type_to_default_tag",
                    "tag_id",
                    draft.defaultTagIds,
                )
                db.commit()
                true
            } catch (error: Throwable) {
                db.rollback()
                throw error
            }
        }
    }

    fun selectableTagsForActivity(activityId: Long): List<DesktopTag> {
        return connection().use { db ->
            db.prepareStatement(
                """
                SELECT t.id, t.name, t.archived, t.value_type, t.value_suffix
                FROM record_tags t
                WHERE t.archived = 0
                  AND (
                    NOT EXISTS (SELECT 1 FROM record_type_to_tag assigned WHERE assigned.tag_id = t.id)
                    OR EXISTS (
                        SELECT 1 FROM record_type_to_tag assigned
                        WHERE assigned.tag_id = t.id AND assigned.record_type_id = ?
                    )
                  )
                ORDER BY t.name COLLATE NOCASE, t.id
                """.trimIndent(),
            ).use { query ->
                query.setLong(1, activityId)
                query.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                DesktopTag(
                                    id = result.getLong("id"),
                                    name = result.getString("name"),
                                    archived = false,
                                    valueType = DesktopTagValueType.valueOf(result.getString("value_type")),
                                    valueSuffix = result.getString("value_suffix"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun recordTagViews(recordId: Long): List<DesktopRecordTagView> = connection().use { db ->
        recordTagViews(db, recordId)
    }

    private fun relationIds(table: String, valueColumn: String, activityId: Long): Set<Long> {
        return connection().use { db ->
            db.prepareStatement(
                "SELECT $valueColumn FROM $table WHERE record_type_id = ?",
            ).use { query ->
                query.setLong(1, activityId)
                query.executeQuery().use { result ->
                    buildSet { while (result.next()) add(result.getLong(1)) }
                }
            }
        }
    }

    private fun replaceActivityRelations(
        db: Connection,
        activityId: Long,
        table: String,
        valueColumn: String,
        ids: Set<Long>,
    ) {
        db.prepareStatement("DELETE FROM $table WHERE record_type_id = ?").use { delete ->
            delete.setLong(1, activityId)
            delete.executeUpdate()
        }
        db.prepareStatement("INSERT INTO $table(record_type_id, $valueColumn) VALUES (?, ?)").use { insert ->
            ids.forEach { id ->
                insert.setLong(1, activityId)
                insert.setLong(2, id)
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    private fun replaceRecordTags(
        db: Connection,
        recordId: Long,
        tags: List<DesktopRecordTag>,
    ) {
        db.prepareStatement("DELETE FROM record_to_tag WHERE record_id = ?").use { delete ->
            delete.setLong(1, recordId)
            delete.executeUpdate()
        }
        insertTagRelations(db, "record_to_tag", "record_id", recordId, tags)
    }

    private fun replaceRunningRecordTags(
        db: Connection,
        runningRecordId: Long,
        tags: List<DesktopRecordTag>,
    ) {
        db.prepareStatement("DELETE FROM running_record_to_tag WHERE running_record_id = ?").use { delete ->
            delete.setLong(1, runningRecordId)
            delete.executeUpdate()
        }
        insertTagRelations(db, "running_record_to_tag", "running_record_id", runningRecordId, tags)
    }

    private fun insertTagRelations(
        db: Connection,
        table: String,
        recordColumn: String,
        recordId: Long,
        tags: List<DesktopRecordTag>,
    ) {
        if (tags.isEmpty()) return
        db.prepareStatement(
            "INSERT INTO $table($recordColumn, tag_id, numeric_value) VALUES (?, ?, ?)",
        ).use { insert ->
            tags.forEach { tag ->
                insert.setLong(1, recordId)
                insert.setLong(2, tag.tagId)
                if (tag.numericValue == null) insert.setNull(3, java.sql.Types.REAL)
                else insert.setDouble(3, tag.numericValue)
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    private fun recordTags(
        db: Connection,
        recordId: Long,
        activeOnly: Boolean,
    ): List<DesktopRecordTag> {
        val archiveFilter = if (activeOnly) "AND t.archived = 0" else ""
        return db.prepareStatement(
            """
            SELECT rtt.tag_id, rtt.numeric_value
            FROM record_to_tag rtt
            JOIN record_tags t ON t.id = rtt.tag_id
            WHERE rtt.record_id = ? $archiveFilter
            ORDER BY t.name COLLATE NOCASE, t.id
            """.trimIndent(),
        ).use { query ->
            query.setLong(1, recordId)
            query.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val value = result.getDouble("numeric_value")
                        val numericValue = value.takeUnless { result.wasNull() }
                        add(DesktopRecordTag(result.getLong("tag_id"), numericValue))
                    }
                }
            }
        }
    }

    private fun runningRecordTags(db: Connection, runningRecordId: Long): List<DesktopRecordTag> {
        return db.prepareStatement(
            """
            SELECT rtt.tag_id, rtt.numeric_value
            FROM running_record_to_tag rtt
            JOIN record_tags t ON t.id = rtt.tag_id
            WHERE rtt.running_record_id = ?
            ORDER BY t.name COLLATE NOCASE, t.id
            """.trimIndent(),
        ).use { query ->
            query.setLong(1, runningRecordId)
            query.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val value = result.getDouble("numeric_value")
                        val numericValue = value.takeUnless { result.wasNull() }
                        add(DesktopRecordTag(result.getLong("tag_id"), numericValue))
                    }
                }
            }
        }
    }

    private fun recordTagViews(db: Connection, recordId: Long): List<DesktopRecordTagView> {
        return db.prepareStatement(
            """
            SELECT t.id, t.name, t.value_type, t.value_suffix, rtt.numeric_value
            FROM record_to_tag rtt
            JOIN record_tags t ON t.id = rtt.tag_id
            WHERE rtt.record_id = ?
            ORDER BY t.name COLLATE NOCASE, t.id
            """.trimIndent(),
        ).use { query ->
            query.setLong(1, recordId)
            query.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val value = result.getDouble("numeric_value")
                        val numericValue = value.takeUnless { result.wasNull() }
                        add(
                            DesktopRecordTagView(
                                tagId = result.getLong("id"),
                                name = result.getString("name"),
                                valueType = DesktopTagValueType.valueOf(result.getString("value_type")),
                                valueSuffix = result.getString("value_suffix"),
                                numericValue = numericValue,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun historyToday(): List<HistoryRow> {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

        return connection().use { db ->
            db.prepareStatement(
                """
                SELECT
                    r.id,
                    rt.name,
                    r.time_started,
                    r.time_ended
                FROM records r
                JOIN recordTypes rt ON rt.id = r.type_id
                WHERE r.time_ended >= ?
                ORDER BY r.time_ended DESC
                LIMIT 100
                """.trimIndent(),
            ).use { query ->
                query.setLong(1, start)
                query.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                HistoryRow(
                                    id = result.getLong("id"),
                                    activityName = result.getString("name"),
                                    startedAt = result.getLong("time_started"),
                                    endedAt = result.getLong("time_ended"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
