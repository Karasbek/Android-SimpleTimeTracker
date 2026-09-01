package com.example.util.simpletimetracker.desktop

import java.sql.Connection
import java.time.DayOfWeek

/** Mirrors Android RecordTypeGoal without coupling desktop storage to Room entities. */
enum class DesktopGoalOwnerType { ACTIVITY, CATEGORY, TAG }
enum class DesktopGoalRange { SESSION, DAILY, WEEKLY, MONTHLY }
enum class DesktopGoalMeasure { DURATION, COUNT }
enum class DesktopGoalSubtype { GOAL, LIMIT }
enum class DesktopStatisticsGrouping { ACTIVITY, CATEGORY, TAG }

data class DesktopGoal(
    val id: Long = 0,
    val ownerType: DesktopGoalOwnerType,
    val ownerId: Long,
    val range: DesktopGoalRange,
    val measure: DesktopGoalMeasure,
    val subtype: DesktopGoalSubtype,
    /** Seconds for duration goals, number of records for count goals. */
    val value: Long,
    /** Android applies this set only to daily goals. */
    val daysOfWeek: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
)

data class DesktopStatisticsBreakdown(
    val id: Long,
    val name: String,
    val icon: String = "",
    val color: String = "",
    val durationMillis: Long,
    val count: Long,
    val numericValueSum: Double? = null,
    val numericValueCount: Long = 0,
)

data class DesktopGoalProgress(
    val goal: DesktopGoal,
    val ownerName: String,
    val current: Long,
    val target: Long,
    val reached: Boolean,
    val successful: Boolean,
) {
    val percent: Long = if (target == 0L) 0L else current * 100L / target
}

enum class DesktopGoalWriteResult { SAVED, NOT_FOUND, INVALID_OWNER, INVALID_VALUE }

class DesktopGoalsService(
    private val database: DesktopDatabase,
    private val timeService: DesktopTimeService,
) {
    fun all(): List<DesktopGoal> = database.goals()

    fun save(goal: DesktopGoal): Pair<DesktopGoalWriteResult, Long?> {
        if (goal.value < 0L) return DesktopGoalWriteResult.INVALID_VALUE to null
        if (!ownerExists(goal.ownerType, goal.ownerId)) return DesktopGoalWriteResult.INVALID_OWNER to null
        return DesktopGoalWriteResult.SAVED to database.saveGoal(goal)
    }

    fun delete(id: Long): DesktopGoalWriteResult =
        if (database.deleteGoal(id)) DesktopGoalWriteResult.SAVED else DesktopGoalWriteResult.NOT_FOUND

    fun progress(
        date: java.time.LocalDate,
        allRecords: List<DesktopTimelineRecord>,
        hideFinished: Boolean,
    ): List<DesktopGoalProgress> = all().mapNotNull { goal ->
        if (goal.range == DesktopGoalRange.DAILY && timeService.day(date).let { timeService.userDate(it.startedAt).dayOfWeek !in goal.daysOfWeek }) {
            return@mapNotNull null
        }
        val range = when (goal.range) {
            DesktopGoalRange.SESSION -> timeService.day(date)
            DesktopGoalRange.DAILY -> timeService.day(date)
            DesktopGoalRange.WEEKLY -> timeService.week(date)
            DesktopGoalRange.MONTHLY -> timeService.month(date)
        }
        val matched = allRecords.filter { it.matchesGoalOwner(goal) }
        val current = when (goal.range) {
            DesktopGoalRange.SESSION -> sessionValue(goal, matched)
            else -> aggregateValue(goal, matched, range)
        }
        val target = if (goal.measure == DesktopGoalMeasure.DURATION) goal.value * 1_000L else goal.value
        val reached = when (goal.subtype) {
            DesktopGoalSubtype.GOAL -> current >= target
            DesktopGoalSubtype.LIMIT -> current > target
        }
        val successful = when (goal.subtype) {
            DesktopGoalSubtype.GOAL -> reached
            DesktopGoalSubtype.LIMIT -> !reached
        }
        DesktopGoalProgress(goal, ownerName(goal), current, target, reached, successful)
            .takeUnless { hideFinished && successful }
    }.sortedBy { it.percent }

    private fun aggregateValue(goal: DesktopGoal, records: List<DesktopTimelineRecord>, range: DesktopTimeRange): Long = when (goal.measure) {
        DesktopGoalMeasure.DURATION -> records.sumOf { range.clippedDuration(it.startedAt, it.endedAt) }
        DesktopGoalMeasure.COUNT -> records.count { range.intersects(it.startedAt, it.endedAt) }.toLong()
    }

    /** Session goals are evaluated per session; desktop lists the highest current session progress. */
    private fun sessionValue(goal: DesktopGoal, records: List<DesktopTimelineRecord>): Long = when (goal.measure) {
        DesktopGoalMeasure.DURATION -> records.maxOfOrNull { (it.endedAt - it.startedAt).coerceAtLeast(0L) } ?: 0L
        DesktopGoalMeasure.COUNT -> if (records.isEmpty()) 0L else 1L
    }

    private fun DesktopTimelineRecord.matchesGoalOwner(goal: DesktopGoal): Boolean = when (goal.ownerType) {
        DesktopGoalOwnerType.ACTIVITY -> activityId == goal.ownerId
        DesktopGoalOwnerType.CATEGORY -> goal.ownerId in categoryIds
        DesktopGoalOwnerType.TAG -> tags.any { it.tagId == goal.ownerId }
    }

    private fun ownerExists(type: DesktopGoalOwnerType, id: Long): Boolean = when (type) {
        DesktopGoalOwnerType.ACTIVITY -> database.activities().any { it.id == id } || database.archivedActivities().any { it.id == id }
        DesktopGoalOwnerType.CATEGORY -> database.categories().any { it.id == id }
        DesktopGoalOwnerType.TAG -> database.tags().any { it.id == id }
    }

    private fun ownerName(goal: DesktopGoal): String = when (goal.ownerType) {
        DesktopGoalOwnerType.ACTIVITY -> (database.activities() + database.archivedActivities()).firstOrNull { it.id == goal.ownerId }?.name
        DesktopGoalOwnerType.CATEGORY -> database.categories().firstOrNull { it.id == goal.ownerId }?.name
        DesktopGoalOwnerType.TAG -> database.tags().firstOrNull { it.id == goal.ownerId }?.name
    } ?: "Удалённый объект #${goal.ownerId}"
}

class DesktopDetailedStatisticsService(
    private val database: DesktopDatabase,
) {
    /**
     * The supplied records have already passed DesktopRecordsRangeService: this deliberately
     * reuses its filter semantics and half-open clipping. Category/tag groups may overlap,
     * matching Android statistics rather than union-deduplicating records.
     */
    fun breakdown(
        records: List<DesktopTimelineRecord>,
        range: DesktopTimeRange,
        grouping: DesktopStatisticsGrouping,
    ): List<DesktopStatisticsBreakdown> {
        val tracked = records.filterNot(DesktopTimelineRecord::isUntracked)
        val groups = linkedMapOf<Long, MutableList<DesktopTimelineRecord>>()
        when (grouping) {
            DesktopStatisticsGrouping.ACTIVITY -> tracked.forEach { groups.getOrPut(it.activityId) { mutableListOf() } += it }
            DesktopStatisticsGrouping.CATEGORY -> tracked.forEach { record ->
                if (record.categoryIds.isEmpty()) groups.getOrPut(0L) { mutableListOf() } += record
                else record.categoryIds.forEach { groups.getOrPut(it) { mutableListOf() } += record }
            }
            DesktopStatisticsGrouping.TAG -> tracked.forEach { record ->
                if (record.tags.isEmpty()) groups.getOrPut(0L) { mutableListOf() } += record
                else record.tags.forEach { groups.getOrPut(it.tagId) { mutableListOf() } += record }
            }
        }
        return groups.map { (id, group) ->
            val metadata = metadata(grouping, id, group.first())
            val values = if (grouping == DesktopStatisticsGrouping.TAG) group.flatMap { record ->
                record.tags.filter { it.tagId == id }.mapNotNull(DesktopRecordTagView::numericValue)
            } else emptyList()
            DesktopStatisticsBreakdown(
                id = id,
                name = metadata.first,
                icon = metadata.second,
                color = metadata.third,
                durationMillis = group.sumOf { range.clippedDuration(it.startedAt, it.endedAt) },
                count = group.size.toLong(),
                numericValueSum = values.takeIf { it.isNotEmpty() }?.sum(),
                numericValueCount = values.size.toLong(),
            )
        }.sortedByDescending(DesktopStatisticsBreakdown::durationMillis)
    }

    private fun metadata(grouping: DesktopStatisticsGrouping, id: Long, fallback: DesktopTimelineRecord): Triple<String, String, String> = when (grouping) {
        DesktopStatisticsGrouping.ACTIVITY -> Triple(fallback.activityName, fallback.icon, fallback.colorInt)
        DesktopStatisticsGrouping.CATEGORY -> database.categories().firstOrNull { it.id == id }
            ?.let { Triple(it.name, "", it.colorInt) } ?: Triple("Без категории", "", "")
        DesktopStatisticsGrouping.TAG -> database.tags().firstOrNull { it.id == id }
            ?.let { Triple(it.name, "", "") } ?: Triple("Без тегов", "", "")
    }
}

fun DesktopDatabase.goals(): List<DesktopGoal> = crudConnection().use { db ->
    db.prepareStatement("SELECT id, owner_id, owner_type, goal_range, measure, subtype, value, days_of_week FROM record_type_goals ORDER BY goal_range, owner_type, owner_id, id").use { query ->
        query.executeQuery().use { result -> buildList {
            while (result.next()) add(result.toDesktopGoal())
        } }
    }
}

fun DesktopDatabase.saveGoal(goal: DesktopGoal): Long = crudConnection().use { db ->
    db.autoCommit = false
    try {
        val id = if (goal.id == 0L) nextId(db) else goal.id
        val saved = if (goal.id == 0L) {
            db.prepareStatement("INSERT INTO record_type_goals(id, owner_id, owner_type, goal_range, measure, subtype, value, days_of_week) VALUES (?, ?, ?, ?, ?, ?, ?, ?)").use { insert ->
                insert.bindGoal(id, goal); insert.executeUpdate() == 1
            }
        } else db.prepareStatement("UPDATE record_type_goals SET owner_id=?, owner_type=?, goal_range=?, measure=?, subtype=?, value=?, days_of_week=? WHERE id=?").use { update ->
            update.bindGoalWithoutId(goal); update.setLong(8, id); update.executeUpdate() == 1
        }
        check(saved) { "Goal not found" }
        db.commit(); id
    } catch (error: Throwable) { db.rollback(); throw error }
}

fun DesktopDatabase.deleteGoal(id: Long): Boolean = crudConnection().use { db ->
    db.prepareStatement("DELETE FROM record_type_goals WHERE id = ?").use { delete -> delete.setLong(1, id); delete.executeUpdate() == 1 }
}

private fun java.sql.PreparedStatement.bindGoal(id: Long, goal: DesktopGoal) {
    setLong(1, id)
    setLong(2, goal.ownerId)
    setString(3, goal.ownerType.name)
    setString(4, goal.range.name)
    setString(5, goal.measure.name)
    setString(6, goal.subtype.name)
    setLong(7, goal.value)
    setString(8, goal.daysOfWeek.sortedBy { it.value }.joinToString("") { it.value.toString() })
}

private fun java.sql.PreparedStatement.bindGoalWithoutId(goal: DesktopGoal) {
    setLong(1, goal.ownerId)
    setString(2, goal.ownerType.name)
    setString(3, goal.range.name)
    setString(4, goal.measure.name)
    setString(5, goal.subtype.name)
    setLong(6, goal.value)
    setString(7, goal.daysOfWeek.sortedBy { it.value }.joinToString("") { it.value.toString() })
}

private fun java.sql.ResultSet.toDesktopGoal(): DesktopGoal = DesktopGoal(
    id = getLong("id"), ownerId = getLong("owner_id"),
    ownerType = DesktopGoalOwnerType.valueOf(getString("owner_type")),
    range = DesktopGoalRange.valueOf(getString("goal_range")),
    measure = DesktopGoalMeasure.valueOf(getString("measure")),
    subtype = DesktopGoalSubtype.valueOf(getString("subtype")),
    value = getLong("value"),
    daysOfWeek = getString("days_of_week").mapNotNull { it.digitToIntOrNull()?.takeIf { day -> day in 1..7 }?.let(DayOfWeek::of) }.toSet(),
)
