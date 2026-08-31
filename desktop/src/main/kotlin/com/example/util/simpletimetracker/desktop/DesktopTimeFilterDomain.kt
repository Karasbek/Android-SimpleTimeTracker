package com.example.util.simpletimetracker.desktop

import java.sql.Connection

data class DesktopRecordFilter(
    val includedActivityIds: Set<Long> = emptySet(),
    val excludedActivityIds: Set<Long> = emptySet(),
    val includedCategoryIds: Set<Long> = emptySet(),
    val excludedCategoryIds: Set<Long> = emptySet(),
    val includeUncategorized: Boolean = false,
    val excludeUncategorized: Boolean = false,
    val includedTagIds: Set<Long> = emptySet(),
    val excludedTagIds: Set<Long> = emptySet(),
    val includeUntagged: Boolean = false,
    val excludeUntagged: Boolean = false,
) {
    companion object {
        val EMPTY = DesktopRecordFilter()
    }
}

data class DesktopTimelineRecord(
    val id: Long,
    val activityId: Long,
    val activityName: String,
    val startedAt: Long,
    val endedAt: Long,
    val comment: String,
    val tags: List<DesktopRecordTagView>,
    val categoryIds: Set<Long>,
    val isRunning: Boolean,
    val icon: String = "",
    val colorInt: String = "",
)

data class DesktopSavedRecordFilter(
    val id: Long,
    val name: String,
    val filter: DesktopRecordFilter,
)

enum class DesktopSavedFilterResult {
    SAVED,
    INVALID_NAME,
    NAME_CONFLICT,
    NOT_FOUND,
}

class DesktopSavedFilterService(
    private val database: DesktopDatabase,
) {
    fun all(): List<DesktopSavedRecordFilter> = database.savedRecordFilters()

    fun save(
        id: Long = 0,
        name: String,
        filter: DesktopRecordFilter,
    ): Pair<DesktopSavedFilterResult, Long?> {
        val normalized = name.trim()
        if (normalized.isEmpty()) return DesktopSavedFilterResult.INVALID_NAME to null
        if (all().any { it.id != id && it.name == normalized }) {
            return DesktopSavedFilterResult.NAME_CONFLICT to null
        }
        val savedId = database.saveRecordFilter(id, normalized, filter) ?: return DesktopSavedFilterResult.NOT_FOUND to null
        return DesktopSavedFilterResult.SAVED to savedId
    }

    fun delete(id: Long): DesktopSavedFilterResult =
        if (database.deleteSavedRecordFilter(id)) DesktopSavedFilterResult.SAVED else DesktopSavedFilterResult.NOT_FOUND
}

class DesktopRecordsRangeService(
    private val database: DesktopDatabase,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    fun get(range: DesktopTimeRange, filter: DesktopRecordFilter = DesktopRecordFilter.EMPTY): List<DesktopTimelineRecord> {
        val now = currentTimeMillis()
        return (database.completedTimelineRecords(range) + database.runningTimelineRecords(range, now))
            .filter { filter.matches(it) }
            .sortedWith(compareByDescending<DesktopTimelineRecord> { it.startedAt }.thenByDescending { it.id })
    }

    fun totalDuration(range: DesktopTimeRange, filter: DesktopRecordFilter = DesktopRecordFilter.EMPTY): Long =
        get(range, filter).sumOf { range.clippedDuration(it.startedAt, it.endedAt) }

    fun activityDurations(
        range: DesktopTimeRange,
        filter: DesktopRecordFilter = DesktopRecordFilter.EMPTY,
    ): Map<Long, Pair<String, Long>> = get(range, filter)
        .groupBy(DesktopTimelineRecord::activityId)
        .mapValues { (_, records) ->
            records.first().activityName to records.sumOf { range.clippedDuration(it.startedAt, it.endedAt) }
        }
}

private fun DesktopRecordFilter.matches(record: DesktopTimelineRecord): Boolean {
    val hasSelectedActivityCriterion =
        includedActivityIds.isNotEmpty() || includedCategoryIds.isNotEmpty() || includeUncategorized
    val activitySelected = !hasSelectedActivityCriterion ||
        record.activityId in includedActivityIds ||
        record.categoryIds.any { it in includedCategoryIds } ||
        (includeUncategorized && record.categoryIds.isEmpty())
    val activityFiltered =
        record.activityId in excludedActivityIds ||
        record.categoryIds.any { it in excludedCategoryIds } ||
        (excludeUncategorized && record.categoryIds.isEmpty())

    val tagIds = record.tags.map(DesktopRecordTagView::tagId).toSet()
    val tagSelected = (includedTagIds.isEmpty() && !includeUntagged) ||
        tagIds.any { it in includedTagIds } || (includeUntagged && tagIds.isEmpty())
    val tagFiltered = tagIds.any { it in excludedTagIds } || (excludeUntagged && tagIds.isEmpty())

    return activitySelected && !activityFiltered && tagSelected && !tagFiltered
}

fun DesktopDatabase.completedTimelineRecords(range: DesktopTimeRange): List<DesktopTimelineRecord> {
    val rows = crudConnection().use { db ->
        db.prepareStatement(
            """
            SELECT r.id, r.type_id, rt.name, rt.icon, rt.color_int, r.time_started, r.time_ended, r.comment
            FROM records r
            JOIN recordTypes rt ON rt.id = r.type_id
            WHERE r.time_started < ? AND r.time_ended > ?
            """.trimIndent(),
        ).use { query ->
            query.setLong(1, range.endedAt)
            query.setLong(2, range.startedAt)
            query.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            DesktopTimelineRecord(
                                id = result.getLong("id"),
                                activityId = result.getLong("type_id"),
                                activityName = result.getString("name"),
                                startedAt = result.getLong("time_started"),
                                endedAt = result.getLong("time_ended"),
                                comment = result.getString("comment"),
                                tags = emptyList(),
                                categoryIds = emptySet(),
                                isRunning = false,
                                icon = result.getString("icon"),
                                colorInt = result.getString("color_int"),
                            ),
                        )
                    }
                }
            }
        }
    }
    return rows.map { row ->
        row.copy(tags = recordTagViews(row.id), categoryIds = categoryIdsForActivity(row.activityId))
    }
}

fun DesktopDatabase.runningTimelineRecords(range: DesktopTimeRange, now: Long): List<DesktopTimelineRecord> {
    if (now <= range.startedAt) return emptyList()
    return crudConnection().use { db ->
        db.prepareStatement(
            """
            SELECT rr.id, rt.name, rt.icon, rt.color_int, rr.time_started, rr.comment
            FROM runningRecords rr
            JOIN recordTypes rt ON rt.id = rr.id
            WHERE rr.time_started < ?
            """.trimIndent(),
        ).use { query ->
            query.setLong(1, range.endedAt)
            query.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val startedAt = result.getLong("time_started")
                        if (startedAt < now) {
                            val id = result.getLong("id")
                            add(
                                DesktopTimelineRecord(
                                    id = id,
                                    activityId = id,
                                    activityName = result.getString("name"),
                                    startedAt = startedAt,
                                    endedAt = now,
                                    comment = result.getString("comment"),
                                    tags = runningRecordTagViews(id),
                                    categoryIds = categoryIdsForActivity(id),
                                    isRunning = true,
                                    icon = result.getString("icon"),
                                    colorInt = result.getString("color_int"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun DesktopDatabase.runningRecordTagViews(runningRecordId: Long): List<DesktopRecordTagView> = crudConnection().use { db ->
    db.prepareStatement(
        """
        SELECT t.id, t.name, t.value_type, t.value_suffix, rtt.numeric_value
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
                    add(
                        DesktopRecordTagView(
                            result.getLong("id"),
                            result.getString("name"),
                            DesktopTagValueType.valueOf(result.getString("value_type")),
                            result.getString("value_suffix"),
                            numericValue,
                        ),
                    )
                }
            }
        }
    }
}

fun DesktopDatabase.savedRecordFilters(): List<DesktopSavedRecordFilter> = crudConnection().use { db ->
    db.prepareStatement(
        """
        SELECT id, name, include_uncategorized, exclude_uncategorized, include_untagged, exclude_untagged
        FROM saved_record_filters
        ORDER BY name COLLATE NOCASE, id
        """.trimIndent(),
    ).use { query ->
        query.executeQuery().use { result ->
            buildList {
                while (result.next()) {
                    val id = result.getLong("id")
                    add(
                        DesktopSavedRecordFilter(
                            id = id,
                            name = result.getString("name"),
                            filter = DesktopRecordFilter(
                                includedActivityIds = savedFilterIds(db, "activities", id, "INCLUDE"),
                                excludedActivityIds = savedFilterIds(db, "activities", id, "EXCLUDE"),
                                includedCategoryIds = savedFilterIds(db, "categories", id, "INCLUDE"),
                                excludedCategoryIds = savedFilterIds(db, "categories", id, "EXCLUDE"),
                                includeUncategorized = result.getInt("include_uncategorized") != 0,
                                excludeUncategorized = result.getInt("exclude_uncategorized") != 0,
                                includedTagIds = savedFilterIds(db, "tags", id, "INCLUDE"),
                                excludedTagIds = savedFilterIds(db, "tags", id, "EXCLUDE"),
                                includeUntagged = result.getInt("include_untagged") != 0,
                                excludeUntagged = result.getInt("exclude_untagged") != 0,
                            ),
                        ),
                    )
                }
            }
        }
    }
}

fun DesktopDatabase.saveRecordFilter(
    filterId: Long,
    name: String,
    filter: DesktopRecordFilter,
): Long? = crudConnection().use { db ->
    db.autoCommit = false
    try {
        val id = if (filterId == 0L) nextId(db) else filterId
        val saved = if (filterId == 0L) {
            db.prepareStatement(
                """
                INSERT INTO saved_record_filters(
                    id, name, include_uncategorized, exclude_uncategorized, include_untagged, exclude_untagged
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { insert ->
                insert.setLong(1, id)
                insert.setString(2, name)
                insert.bindFilterFlags(filter, 3)
                insert.executeUpdate() == 1
            }
        } else {
            db.prepareStatement(
                """
                UPDATE saved_record_filters SET
                    name = ?, include_uncategorized = ?, exclude_uncategorized = ?,
                    include_untagged = ?, exclude_untagged = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { update ->
                update.setString(1, name)
                update.bindFilterFlags(filter, 2)
                update.setLong(6, id)
                update.executeUpdate() == 1
            }
        }
        if (!saved) {
            db.rollback()
            return null
        }
        listOf("activities", "tags", "categories").forEach { kind ->
            db.prepareStatement("DELETE FROM saved_record_filter_$kind WHERE filter_id = ?").use { delete ->
                delete.setLong(1, id)
                delete.executeUpdate()
            }
        }
        saveFilterIds(db, "activities", id, "INCLUDE", filter.includedActivityIds)
        saveFilterIds(db, "activities", id, "EXCLUDE", filter.excludedActivityIds)
        saveFilterIds(db, "tags", id, "INCLUDE", filter.includedTagIds)
        saveFilterIds(db, "tags", id, "EXCLUDE", filter.excludedTagIds)
        saveFilterIds(db, "categories", id, "INCLUDE", filter.includedCategoryIds)
        saveFilterIds(db, "categories", id, "EXCLUDE", filter.excludedCategoryIds)
        db.commit()
        id
    } catch (error: Throwable) {
        db.rollback()
        throw error
    }
}

fun DesktopDatabase.deleteSavedRecordFilter(filterId: Long): Boolean = crudConnection().use { db ->
    db.autoCommit = false
    try {
        listOf("activities", "tags", "categories").forEach { kind ->
            db.prepareStatement("DELETE FROM saved_record_filter_$kind WHERE filter_id = ?").use { delete ->
                delete.setLong(1, filterId)
                delete.executeUpdate()
            }
        }
        val deleted = db.prepareStatement("DELETE FROM saved_record_filters WHERE id = ?").use { delete ->
            delete.setLong(1, filterId)
            delete.executeUpdate() == 1
        }
        db.commit()
        deleted
    } catch (error: Throwable) {
        db.rollback()
        throw error
    }
}

private fun savedFilterIds(db: Connection, kind: String, filterId: Long, mode: String): Set<Long> =
    db.prepareStatement("SELECT entity_id FROM saved_record_filter_$kind WHERE filter_id = ? AND mode = ?").use { query ->
        query.setLong(1, filterId)
        query.setString(2, mode)
        query.executeQuery().use { result ->
            buildSet { while (result.next()) add(result.getLong(1)) }
        }
    }

private fun saveFilterIds(db: Connection, kind: String, filterId: Long, mode: String, ids: Set<Long>) {
    if (ids.isEmpty()) return
    db.prepareStatement(
        "INSERT INTO saved_record_filter_$kind(filter_id, entity_id, mode) VALUES (?, ?, ?)",
    ).use { insert ->
        ids.forEach { id ->
            insert.setLong(1, filterId)
            insert.setLong(2, id)
            insert.setString(3, mode)
            insert.addBatch()
        }
        insert.executeBatch()
    }
}

private fun java.sql.PreparedStatement.bindFilterFlags(filter: DesktopRecordFilter, startIndex: Int) {
    setInt(startIndex, if (filter.includeUncategorized) 1 else 0)
    setInt(startIndex + 1, if (filter.excludeUncategorized) 1 else 0)
    setInt(startIndex + 2, if (filter.includeUntagged) 1 else 0)
    setInt(startIndex + 3, if (filter.excludeUntagged) 1 else 0)
}
