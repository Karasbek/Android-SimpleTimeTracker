package com.example.util.simpletimetracker.desktop

import java.sql.Connection
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

enum class DesktopCommentFilter { ANY, NO_COMMENT, HAS_COMMENT, CONTAINS }
enum class DesktopDuplicateFilter { NONE, SAME_TIMES, SAME_ACTIVITY }
enum class DesktopRecordKindFilter { ALL, COMPLETED, RUNNING, UNTRACKED }

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
    val commentFilter: DesktopCommentFilter = DesktopCommentFilter.ANY,
    val commentQuery: String = "",
    /** Optional absolute half-open date range, matching Android RecordsFilter.Date. */
    val dateRange: DesktopTimeRange? = null,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    /** Milliseconds after local midnight. A range crossing midnight has start > end. */
    val timeOfDayStartMillis: Long? = null,
    val timeOfDayEndMillis: Long? = null,
    val minDurationMillis: Long? = null,
    val maxDurationMillis: Long? = null,
    val recordKind: DesktopRecordKindFilter = DesktopRecordKindFilter.ALL,
    val multitaskOnly: Boolean = false,
    val duplicates: DesktopDuplicateFilter = DesktopDuplicateFilter.NONE,
    /** Android ManuallyFiltered is an exclusion list. */
    val manuallyExcludedRecordIds: Set<Long> = emptySet(),
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
    val isUntracked: Boolean = false,
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
    private val preferences: DesktopSemanticPreferences? = null,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    fun get(
        range: DesktopTimeRange,
        filter: DesktopRecordFilter = DesktopRecordFilter.EMPTY,
        includeUntracked: Boolean = filter.recordKind == DesktopRecordKindFilter.UNTRACKED,
    ): List<DesktopTimelineRecord> {
        val now = currentTimeMillis()
        val tracked = database.completedTimelineRecords(range) + database.runningTimelineRecords(range, now)
        val records = if (includeUntracked) {
            tracked + DesktopUntrackedRecords.calculate(
                records = tracked,
                range = range,
                now = now,
                minimumDurationMillis = (preferences?.ignoreShortUntrackedDurationSeconds ?: 60L) * 1_000L,
            )
        } else tracked
        return records
            .filter { filter.matches(it, range, tracked, preferences?.startOfDayShiftMillis ?: 0L) }
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

private fun DesktopRecordFilter.matches(
    record: DesktopTimelineRecord,
    displayedRange: DesktopTimeRange,
    tracked: List<DesktopTimelineRecord>,
    startOfDayShiftMillis: Long,
): Boolean {
    if (record.id in manuallyExcludedRecordIds) return false
    if (recordKind == DesktopRecordKindFilter.UNTRACKED && !record.isUntracked) return false
    if (recordKind == DesktopRecordKindFilter.RUNNING && !record.isRunning) return false
    if (recordKind == DesktopRecordKindFilter.COMPLETED && (record.isRunning || record.isUntracked)) return false
    dateRange?.let { if (!it.intersects(record.startedAt, record.endedAt)) return false }
    val duration = displayedRange.clippedDuration(record.startedAt, record.endedAt)
    minDurationMillis?.let { if (duration < it) return false }
    maxDurationMillis?.let { if (duration > it) return false }
    if (!record.isUntracked) when (commentFilter) {
        DesktopCommentFilter.ANY -> Unit
        DesktopCommentFilter.NO_COMMENT -> if (record.comment.isNotEmpty()) return false
        DesktopCommentFilter.HAS_COMMENT -> if (record.comment.isEmpty()) return false
        DesktopCommentFilter.CONTAINS -> if (!record.comment.contains(commentQuery, ignoreCase = true)) return false
    }
    if (daysOfWeek.isNotEmpty() && !record.matchesAnyUserDay(daysOfWeek, startOfDayShiftMillis)) return false
    if (timeOfDayStartMillis != null && timeOfDayEndMillis != null &&
        !record.overlapsTimeOfDay(timeOfDayStartMillis, timeOfDayEndMillis)
    ) return false
    // Android permits only Date/DaysOfWeek/TimeOfDay/Duration alongside
    // its Untracked filter. It is a synthetic record and has no taxonomy.
    if (record.isUntracked) return recordKind == DesktopRecordKindFilter.UNTRACKED || recordKind == DesktopRecordKindFilter.ALL
    if (multitaskOnly && tracked.none { other ->
            other.id != record.id && other.startedAt < record.endedAt && other.endedAt > record.startedAt
        }
    ) return false
    if (duplicates != DesktopDuplicateFilter.NONE && !record.isDuplicateOf(tracked, duplicates)) return false
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

private fun DesktopTimelineRecord.matchesAnyUserDay(days: Set<DayOfWeek>, startOfDayShiftMillis: Long): Boolean {
    val zone = ZoneId.systemDefault()
    var date = Instant.ofEpochMilli(startedAt - startOfDayShiftMillis).atZone(zone).toLocalDate()
    val endDate = Instant.ofEpochMilli(endedAt.coerceAtLeast(startedAt) - startOfDayShiftMillis).atZone(zone).toLocalDate()
    while (!date.isAfter(endDate)) {
        if (date.dayOfWeek in days) return true
        date = date.plusDays(1)
    }
    return false
}

private fun DesktopTimelineRecord.overlapsTimeOfDay(start: Long, end: Long): Boolean {
    if (start == end) return false
    val zone = ZoneId.systemDefault()
    var date = Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate().minusDays(1)
    val last = Instant.ofEpochMilli(endedAt).atZone(zone).toLocalDate()
    while (!date.isAfter(last)) {
        val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val intervalStart = midnight + start
        val intervalEnd = midnight + end + if (end <= start) 86_400_000L else 0L
        if (startedAt < intervalEnd && endedAt > intervalStart) return true
        date = date.plusDays(1)
    }
    return false
}

private fun DesktopTimelineRecord.isDuplicateOf(
    all: List<DesktopTimelineRecord>,
    mode: DesktopDuplicateFilter,
): Boolean = all.any { other ->
    other.id != id && !other.isRunning && when (mode) {
        DesktopDuplicateFilter.SAME_TIMES -> other.startedAt == startedAt && other.endedAt == endedAt
        DesktopDuplicateFilter.SAME_ACTIVITY -> other.activityId == activityId
        DesktopDuplicateFilter.NONE -> false
    }
}

/** Synthetic untracked intervals; no database rows are ever written for these. */
object DesktopUntrackedRecords {
    fun calculate(
        records: List<DesktopTimelineRecord>,
        range: DesktopTimeRange,
        now: Long,
        minimumDurationMillis: Long,
    ): List<DesktopTimelineRecord> {
        val end = min(range.endedAt, now)
        if (end <= range.startedAt || records.isEmpty()) return emptyList()
        // Android starts untracked calculation at the first persisted record,
        // never fabricating a gap before the app has any tracking history.
        val calculationStart = max(range.startedAt, records.minOf(DesktopTimelineRecord::startedAt))
        if (end <= calculationStart) return emptyList()
        val covered = records.mapNotNull { record ->
            val start = max(calculationStart, record.startedAt)
            val clippedEnd = min(end, record.endedAt)
            start.takeIf { it < clippedEnd }?.let { DesktopTimeRange(it, clippedEnd) }
        }.sortedBy(DesktopTimeRange::startedAt)
        val merged = covered.fold(mutableListOf<DesktopTimeRange>()) { result, candidate ->
            val previous = result.lastOrNull()
            if (previous != null && candidate.startedAt <= previous.endedAt) {
                result[result.lastIndex] = DesktopTimeRange(previous.startedAt, max(previous.endedAt, candidate.endedAt))
            } else result += candidate
            result
        }
        val gaps = mutableListOf<DesktopTimeRange>()
        var cursor = calculationStart
        merged.forEach { interval ->
            if (cursor < interval.startedAt) gaps += DesktopTimeRange(cursor, interval.startedAt)
            cursor = max(cursor, interval.endedAt)
        }
        if (cursor < end) gaps += DesktopTimeRange(cursor, end)
        return gaps.filter { it.endedAt - it.startedAt > minimumDurationMillis }
            .map { gap ->
                DesktopTimelineRecord(
                    id = -gap.startedAt,
                    activityId = Long.MIN_VALUE,
                    activityName = "Не отслеживалось",
                    startedAt = gap.startedAt,
                    endedAt = gap.endedAt,
                    comment = "",
                    tags = emptyList(),
                    categoryIds = emptySet(),
                    isRunning = false,
                    isUntracked = true,
                )
            }
    }
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
            SELECT id, name, include_uncategorized, exclude_uncategorized, include_untagged, exclude_untagged,
                comment_mode, comment_query, date_started, date_ended, time_of_day_start, time_of_day_end,
                duration_min, duration_max, show_untracked, multitask_only, duplicates_mode, record_kind
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
                                commentFilter = enumOrDefault(result.getString("comment_mode"), DesktopCommentFilter.ANY),
                                commentQuery = result.getString("comment_query"),
                                dateRange = nullableRange(result, "date_started", "date_ended"),
                                timeOfDayStartMillis = nullableLong(result, "time_of_day_start"),
                                timeOfDayEndMillis = nullableLong(result, "time_of_day_end"),
                                minDurationMillis = nullableLong(result, "duration_min"),
                                maxDurationMillis = nullableLong(result, "duration_max"),
                                recordKind = enumOrDefault(result.getString("record_kind"), DesktopRecordKindFilter.ALL),
                                multitaskOnly = result.getInt("multitask_only") != 0,
                                duplicates = enumOrDefault(result.getString("duplicates_mode"), DesktopDuplicateFilter.NONE),
                                daysOfWeek = savedFilterDaysOfWeek(db, id),
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
                    id, name, include_uncategorized, exclude_uncategorized, include_untagged, exclude_untagged,
                    comment_mode, comment_query, date_started, date_ended, time_of_day_start, time_of_day_end,
                    duration_min, duration_max, show_untracked, multitask_only, duplicates_mode, record_kind
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { insert ->
                insert.setLong(1, id)
                insert.setString(2, name)
                insert.bindFilterFlags(filter, 3)
                insert.bindAdvancedFilter(filter, 7)
                insert.executeUpdate() == 1
            }
        } else {
            db.prepareStatement(
                """
                UPDATE saved_record_filters SET
                    name = ?, include_uncategorized = ?, exclude_uncategorized = ?,
                    include_untagged = ?, exclude_untagged = ?,
                    comment_mode = ?, comment_query = ?, date_started = ?, date_ended = ?,
                    time_of_day_start = ?, time_of_day_end = ?, duration_min = ?, duration_max = ?,
                    show_untracked = ?, multitask_only = ?, duplicates_mode = ?, record_kind = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { update ->
                update.setString(1, name)
                update.bindFilterFlags(filter, 2)
                update.bindAdvancedFilter(filter, 6)
                update.setLong(18, id)
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
        db.prepareStatement("DELETE FROM saved_record_filter_days_of_week WHERE filter_id = ?").use { delete ->
            delete.setLong(1, id)
            delete.executeUpdate()
        }
        saveFilterIds(db, "activities", id, "INCLUDE", filter.includedActivityIds)
        saveFilterIds(db, "activities", id, "EXCLUDE", filter.excludedActivityIds)
        saveFilterIds(db, "tags", id, "INCLUDE", filter.includedTagIds)
        saveFilterIds(db, "tags", id, "EXCLUDE", filter.excludedTagIds)
        saveFilterIds(db, "categories", id, "INCLUDE", filter.includedCategoryIds)
        saveFilterIds(db, "categories", id, "EXCLUDE", filter.excludedCategoryIds)
        saveFilterDaysOfWeek(db, id, filter.daysOfWeek)
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
        db.prepareStatement("DELETE FROM saved_record_filter_days_of_week WHERE filter_id = ?").use { delete ->
            delete.setLong(1, filterId)
            delete.executeUpdate()
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

private fun java.sql.PreparedStatement.bindAdvancedFilter(filter: DesktopRecordFilter, startIndex: Int) {
    setString(startIndex, filter.commentFilter.name)
    setString(startIndex + 1, filter.commentQuery)
    setNullableLong(startIndex + 2, filter.dateRange?.startedAt)
    setNullableLong(startIndex + 3, filter.dateRange?.endedAt)
    setNullableLong(startIndex + 4, filter.timeOfDayStartMillis)
    setNullableLong(startIndex + 5, filter.timeOfDayEndMillis)
    setNullableLong(startIndex + 6, filter.minDurationMillis)
    setNullableLong(startIndex + 7, filter.maxDurationMillis)
    setInt(startIndex + 8, if (filter.recordKind == DesktopRecordKindFilter.UNTRACKED) 1 else 0)
    setInt(startIndex + 9, if (filter.multitaskOnly) 1 else 0)
    setString(startIndex + 10, filter.duplicates.name)
    setString(startIndex + 11, filter.recordKind.name)
}

private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) setNull(index, java.sql.Types.INTEGER) else setLong(index, value)
}

private fun nullableLong(result: java.sql.ResultSet, column: String): Long? =
    result.getLong(column).takeUnless { result.wasNull() }

private fun nullableRange(result: java.sql.ResultSet, start: String, end: String): DesktopTimeRange? {
    val actualStart = nullableLong(result, start) ?: return null
    val actualEnd = nullableLong(result, end) ?: return null
    return DesktopTimeRange(actualStart, actualEnd.coerceAtLeast(actualStart))
}

private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, default: T): T =
    value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

private fun savedFilterDaysOfWeek(db: Connection, filterId: Long): Set<DayOfWeek> =
    db.prepareStatement("SELECT day_of_week FROM saved_record_filter_days_of_week WHERE filter_id = ?").use { query ->
        query.setLong(1, filterId)
        query.executeQuery().use { result ->
            buildSet { while (result.next()) add(DayOfWeek.of(result.getInt(1))) }
        }
    }

private fun saveFilterDaysOfWeek(db: Connection, filterId: Long, days: Set<DayOfWeek>) {
    if (days.isEmpty()) return
    db.prepareStatement("INSERT INTO saved_record_filter_days_of_week(filter_id, day_of_week) VALUES (?, ?)").use { insert ->
        days.forEach { day ->
            insert.setLong(1, filterId)
            insert.setInt(2, day.value)
            insert.addBatch()
        }
        insert.executeBatch()
    }
}
