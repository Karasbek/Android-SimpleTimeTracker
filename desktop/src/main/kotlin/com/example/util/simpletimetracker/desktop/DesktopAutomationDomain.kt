package com.example.util.simpletimetracker.desktop

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.YearMonth
import kotlin.math.max

enum class DesktopComplexRuleAction { ALLOW_MULTITASKING, DISALLOW_MULTITASKING, ASSIGN_TAG }

data class DesktopComplexRuleTag(
    val tagId: Long,
    val numericValue: Double?,
    val selectValueOnStart: Boolean = false,
)

data class DesktopComplexRule(
    val id: Long = 0,
    val disabled: Boolean = false,
    val action: DesktopComplexRuleAction,
    val disallowOnlyPrevious: Boolean = false,
    val assignedTags: List<DesktopComplexRuleTag> = emptyList(),
    val startingActivityIds: Set<Long> = emptySet(),
    val currentActivityIds: Set<Long> = emptySet(),
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
)

data class DesktopComplexRuleResult(
    val allowMultitasking: Boolean?,
    val disallowOnlyPreviousActivityIds: Set<Long>,
    val assignedTags: List<DesktopRecordTag>,
    val tagIdsToSelectValueOnStart: Set<Long>,
)

class DesktopComplexRuleProcessor(
    private val database: DesktopDatabase,
    private val timeService: DesktopTimeService,
) {
    fun process(startedAt: Long, startingActivityId: Long, currentActivityIds: Set<Long>): DesktopComplexRuleResult {
        val day = timeService.userDate(startedAt).dayOfWeek
        val matched = database.complexRules().filter { rule ->
            !rule.disabled && ruleHasConditions(rule) &&
                (rule.startingActivityIds.isEmpty() || startingActivityId in rule.startingActivityIds) &&
                (rule.currentActivityIds.isEmpty() || currentActivityIds.any { it in rule.currentActivityIds }) &&
                (rule.daysOfWeek.isEmpty() || day in rule.daysOfWeek)
        }
        val allows = matched.any { it.action == DesktopComplexRuleAction.ALLOW_MULTITASKING }
        val disallows = matched.filter { it.action == DesktopComplexRuleAction.DISALLOW_MULTITASKING }
        val tags = linkedMapOf<Long, DesktopComplexRuleTag>()
        matched.filter { it.action == DesktopComplexRuleAction.ASSIGN_TAG }.flatMap(DesktopComplexRule::assignedTags).forEach { tag ->
            val previous = tags[tag.tagId]
            if (previous == null || previous.numericValue == null && tag.numericValue != null) tags[tag.tagId] = tag
        }
        val selectable = tags.values.filter { it.selectValueOnStart && it.numericValue == null }.mapTo(mutableSetOf(), DesktopComplexRuleTag::tagId)
        return DesktopComplexRuleResult(
            allowMultitasking = when { allows -> true; disallows.isNotEmpty() -> false; else -> null },
            disallowOnlyPreviousActivityIds = disallows.filter(DesktopComplexRule::disallowOnlyPrevious).flatMap { it.currentActivityIds }.toSet()
                .takeUnless { disallows.any { !it.disallowOnlyPrevious } }.orEmpty(),
            assignedTags = tags.values.map { DesktopRecordTag(it.tagId, it.numericValue) },
            tagIdsToSelectValueOnStart = selectable,
        )
    }

    private fun ruleHasConditions(rule: DesktopComplexRule): Boolean = rule.startingActivityIds.isNotEmpty() || rule.currentActivityIds.isNotEmpty() || rule.daysOfWeek.isNotEmpty()
}

data class DesktopActivitySuggestion(val id: Long = 0, val forActivityId: Long, val suggestionActivityIds: Set<Long>)

data class DesktopScheduledReminder(
    val id: Long = 0,
    val enabled: Boolean = true,
    val text: String = "",
    val schedule: DesktopReminderSchedule,
    val condition: DesktopReminderCondition = DesktopReminderCondition.Always,
)
sealed interface DesktopReminderSchedule { val timeOfDayMillis: Long
    data class Weekly(val daysOfWeek: Set<DayOfWeek>, override val timeOfDayMillis: Long) : DesktopReminderSchedule
    data class OneTime(val localEpochDay: Long, override val timeOfDayMillis: Long) : DesktopReminderSchedule
    data class Monthly(val dayOfMonth: Int, override val timeOfDayMillis: Long) : DesktopReminderSchedule
}
sealed interface DesktopReminderCondition { data object Always : DesktopReminderCondition; data class ActivityNotTrackedToday(val activityId: Long) : DesktopReminderCondition }
data class DesktopReminderOccurrence(val triggerAt: Long, val expectedAt: Long)

object DesktopReminderOccurrenceCalculator {
    fun next(schedule: DesktopReminderSchedule, now: Long, zone: ZoneId, catchUpOverdueOneTime: Boolean = false): DesktopReminderOccurrence? = when (schedule) {
        is DesktopReminderSchedule.Weekly -> weekly(schedule, now, zone)
        is DesktopReminderSchedule.OneTime -> oneTime(schedule, now, zone, catchUpOverdueOneTime)
        is DesktopReminderSchedule.Monthly -> monthly(schedule, now, zone)
    }
    private fun weekly(schedule: DesktopReminderSchedule.Weekly, now: Long, zone: ZoneId): DesktopReminderOccurrence? {
        if (schedule.daysOfWeek.isEmpty() || !validTime(schedule.timeOfDayMillis)) return null
        var date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        repeat(8) { if (date.dayOfWeek in schedule.daysOfWeek) local(date, schedule.timeOfDayMillis, zone).takeIf { it > now }?.let { return DesktopReminderOccurrence(it, it) }; date = date.plusDays(1) }
        return null
    }
    private fun oneTime(schedule: DesktopReminderSchedule.OneTime, now: Long, zone: ZoneId, catchUp: Boolean): DesktopReminderOccurrence? {
        if (!validTime(schedule.timeOfDayMillis)) return null
        val expected = runCatching { local(LocalDate.ofEpochDay(schedule.localEpochDay), schedule.timeOfDayMillis, zone) }.getOrNull() ?: return null
        return if (expected > now) DesktopReminderOccurrence(expected, expected) else if (catchUp) DesktopReminderOccurrence(now, expected) else null
    }
    private fun monthly(schedule: DesktopReminderSchedule.Monthly, now: Long, zone: ZoneId): DesktopReminderOccurrence? {
        if (schedule.dayOfMonth !in 1..31 || !validTime(schedule.timeOfDayMillis)) return null
        var month = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone))
        repeat(2) { val at = local(month.atDay(schedule.dayOfMonth.coerceAtMost(month.lengthOfMonth())), schedule.timeOfDayMillis, zone); if (at > now) return DesktopReminderOccurrence(at, at); month = month.plusMonths(1) }
        return null
    }
    private fun local(date: LocalDate, time: Long, zone: ZoneId): Long = date.atStartOfDay(zone).plusNanos(time * 1_000_000).toInstant().toEpochMilli()
    private fun validTime(value: Long) = value in 0 until 86_400_000L
}

sealed interface DesktopActivityReminderOverride { data object Disabled : DesktopActivityReminderOverride; data class Custom(val durationSeconds: Long, val recurrent: Boolean, val daysOfWeek: Set<DayOfWeek>, val dndStartMillis: Long, val dndEndMillis: Long) : DesktopActivityReminderOverride }

/** Product policy only: notification scheduling/delivery is delegated to a future Linux adapter. */
object DesktopActivityReminderPolicy {
    fun isDue(
        startedAt: Long,
        now: Long,
        override: DesktopActivityReminderOverride,
        zone: ZoneId,
    ): Boolean {
        val rule = override as? DesktopActivityReminderOverride.Custom ?: return false
        val period = rule.durationSeconds.coerceAtLeast(0) * 1_000L
        if (period == 0L || now < startedAt + period) return false
        val local = Instant.ofEpochMilli(now).atZone(zone)
        if (rule.daysOfWeek.isNotEmpty() && local.dayOfWeek !in rule.daysOfWeek) return false
        val time = local.toLocalTime().toSecondOfDay() * 1_000L + local.nano / 1_000_000L
        if (inDnd(time, rule.dndStartMillis, rule.dndEndMillis)) return false
        return rule.recurrent || now < startedAt + period * 2
    }

    fun inDnd(timeOfDayMillis: Long, start: Long, end: Long): Boolean {
        if (start == end) return false
        return if (start < end) timeOfDayMillis in start until end else timeOfDayMillis >= start || timeOfDayMillis < end
    }
}

/** Domain-only automation data. Linux notification delivery remains a later platform adapter. */
class DesktopAutomationService(private val database: DesktopDatabase) {
    fun suggestionsFor(running: Set<Long>, previous: Long?): Set<Long> = (running.ifEmpty { previous?.let(::setOf).orEmpty() }).flatMap { id -> database.activitySuggestions().firstOrNull { it.forActivityId == id }?.suggestionActivityIds.orEmpty() }.toSet().filter { id -> database.activities().any { it.id == id } }.toSet()
    fun shouldDeliver(reminder: DesktopScheduledReminder, day: DesktopTimeRange): Boolean = when (val condition = reminder.condition) {
        DesktopReminderCondition.Always -> true
        is DesktopReminderCondition.ActivityNotTrackedToday ->
            database.completedTimelineRecords(day).none { it.activityId == condition.activityId } &&
                database.runningRecords().none { it.activityId == condition.activityId && it.startedAt < day.endedAt }
    }
}

fun DesktopDatabase.complexRules(): List<DesktopComplexRule> = crudConnection().use { db ->
    db.prepareStatement("SELECT id, disabled, action, disallow_only_previous, starting_activity_ids, current_activity_ids, days_of_week FROM complex_rules ORDER BY id").use { query ->
        query.executeQuery().use { result -> buildList { while (result.next()) {
            val id = result.getLong("id")
            add(DesktopComplexRule(id, result.getInt("disabled") != 0, DesktopComplexRuleAction.valueOf(result.getString("action")), result.getInt("disallow_only_previous") != 0, complexRuleTags(db, id), parseIds(result.getString("starting_activity_ids")), parseIds(result.getString("current_activity_ids")), parseDays(result.getString("days_of_week"))))
        } }
    }
}
}

fun DesktopDatabase.saveComplexRule(rule: DesktopComplexRule): Long = crudConnection().use { db ->
    db.autoCommit = false
    try {
        val id = if (rule.id == 0L) nextId(db) else rule.id
        val ok = if (rule.id == 0L) db.prepareStatement("INSERT INTO complex_rules(id, disabled, action, disallow_only_previous, starting_activity_ids, current_activity_ids, days_of_week) VALUES (?, ?, ?, ?, ?, ?, ?)").use { s ->
            s.setLong(1,id); s.bindComplexRule(2,rule); s.executeUpdate() == 1
        } else db.prepareStatement("UPDATE complex_rules SET disabled=?, action=?, disallow_only_previous=?, starting_activity_ids=?, current_activity_ids=?, days_of_week=? WHERE id=?").use { s ->
            s.bindComplexRule(1,rule); s.setLong(7,id); s.executeUpdate() == 1
        }
        check(ok)
        db.prepareStatement("DELETE FROM complex_rule_tags WHERE rule_id=?").use { it.setLong(1,id); it.executeUpdate() }
        db.prepareStatement("INSERT INTO complex_rule_tags(rule_id, tag_id, numeric_value, select_value_on_start) VALUES (?, ?, ?, ?)").use { insert -> rule.assignedTags.forEach { tag ->
            insert.setLong(1,id); insert.setLong(2,tag.tagId); if (tag.numericValue == null) insert.setNull(3, java.sql.Types.REAL) else insert.setDouble(3,tag.numericValue); insert.setInt(4,if(tag.selectValueOnStart)1 else 0); insert.addBatch()
        }; insert.executeBatch() }
        db.commit(); id
    } catch (error: Throwable) { db.rollback(); throw error }
}
fun DesktopDatabase.deleteComplexRule(id: Long): Boolean = crudConnection().use { db -> db.autoCommit=false; try { db.prepareStatement("DELETE FROM complex_rule_tags WHERE rule_id=?").use { it.setLong(1,id);it.executeUpdate() }; val result=db.prepareStatement("DELETE FROM complex_rules WHERE id=?").use{it.setLong(1,id);it.executeUpdate()==1}; db.commit();result } catch(e:Throwable){db.rollback();throw e} }

fun DesktopDatabase.activitySuggestions(): List<DesktopActivitySuggestion> = crudConnection().use { db -> db.prepareStatement("SELECT id, activity_id FROM activity_suggestions ORDER BY id").use { q -> q.executeQuery().use { r -> buildList { while(r.next()){ val id=r.getLong("id"); add(DesktopActivitySuggestion(id,r.getLong("activity_id"), suggestionItems(db,id))) } } } } }
fun DesktopDatabase.saveActivitySuggestion(suggestion: DesktopActivitySuggestion): Long = crudConnection().use { db -> db.autoCommit=false; try { val id=if(suggestion.id==0L)nextId(db) else suggestion.id; val ok=if(suggestion.id==0L) db.prepareStatement("INSERT INTO activity_suggestions(id, activity_id) VALUES (?,?)").use{it.setLong(1,id);it.setLong(2,suggestion.forActivityId);it.executeUpdate()==1} else db.prepareStatement("UPDATE activity_suggestions SET activity_id=? WHERE id=?").use{it.setLong(1,suggestion.forActivityId);it.setLong(2,id);it.executeUpdate()==1};check(ok);db.prepareStatement("DELETE FROM activity_suggestion_items WHERE suggestion_id=?").use{it.setLong(1,id);it.executeUpdate()};db.prepareStatement("INSERT INTO activity_suggestion_items(suggestion_id,activity_id) VALUES (?,?)").use{p->suggestion.suggestionActivityIds.forEach{p.setLong(1,id);p.setLong(2,it);p.addBatch()};p.executeBatch()};db.commit();id }catch(e:Throwable){db.rollback();throw e} }
fun DesktopDatabase.deleteActivitySuggestion(id: Long): Boolean = crudConnection().use { db -> db.autoCommit=false;try{db.prepareStatement("DELETE FROM activity_suggestion_items WHERE suggestion_id=?").use{it.setLong(1,id);it.executeUpdate()};val saved=db.prepareStatement("DELETE FROM activity_suggestions WHERE id=?").use{it.setLong(1,id);it.executeUpdate()==1};db.commit();saved}catch(e:Throwable){db.rollback();throw e} }

fun DesktopDatabase.scheduledReminders(): List<DesktopScheduledReminder> = crudConnection().use { db ->
    db.prepareStatement("SELECT id, enabled, text, schedule_type, days_of_week, one_time_epoch_day, day_of_month, time_of_day_millis, condition_type, condition_activity_id FROM scheduled_reminders ORDER BY id").use { query ->
        query.executeQuery().use { result -> buildList {
            while (result.next()) {
                val type = result.getString("schedule_type")
                val time = result.getLong("time_of_day_millis")
                val schedule = when (type) {
                    "WEEKLY" -> DesktopReminderSchedule.Weekly(parseDays(result.getString("days_of_week")), time)
                    "ONE_TIME" -> DesktopReminderSchedule.OneTime(result.getLong("one_time_epoch_day"), time)
                    "MONTHLY" -> DesktopReminderSchedule.Monthly(result.getInt("day_of_month"), time)
                    else -> continue
                }
                val condition = when (result.getString("condition_type")) {
                    "ALWAYS" -> DesktopReminderCondition.Always
                    "ACTIVITY_NOT_TRACKED_TODAY" -> {
                        val activityId = result.getLong("condition_activity_id")
                        if (result.wasNull()) continue
                        DesktopReminderCondition.ActivityNotTrackedToday(activityId)
                    }
                    else -> continue
                }
                add(DesktopScheduledReminder(result.getLong("id"), result.getInt("enabled") != 0, result.getString("text"), schedule, condition))
            }
        } }
    }
}

fun DesktopDatabase.saveScheduledReminder(reminder: DesktopScheduledReminder): Long = crudConnection().use { db ->
    val id = if (reminder.id == 0L) nextId(db) else reminder.id
    val sql = if (reminder.id == 0L) {
        "INSERT INTO scheduled_reminders(id, enabled, text, schedule_type, days_of_week, one_time_epoch_day, day_of_month, time_of_day_millis, condition_type, condition_activity_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    } else {
        "UPDATE scheduled_reminders SET enabled=?, text=?, schedule_type=?, days_of_week=?, one_time_epoch_day=?, day_of_month=?, time_of_day_millis=?, condition_type=?, condition_activity_id=? WHERE id=?"
    }
    db.prepareStatement(sql).use { statement ->
        var index = 1
        if (reminder.id == 0L) statement.setLong(index++, id)
        statement.setInt(index++, if (reminder.enabled) 1 else 0)
        statement.setString(index++, reminder.text)
        when (val schedule = reminder.schedule) {
            is DesktopReminderSchedule.Weekly -> { statement.setString(index++, "WEEKLY"); statement.setString(index++, encodeDays(schedule.daysOfWeek)); statement.setNull(index++, java.sql.Types.INTEGER); statement.setNull(index++, java.sql.Types.INTEGER); statement.setLong(index++, schedule.timeOfDayMillis) }
            is DesktopReminderSchedule.OneTime -> { statement.setString(index++, "ONE_TIME"); statement.setString(index++, ""); statement.setLong(index++, schedule.localEpochDay); statement.setNull(index++, java.sql.Types.INTEGER); statement.setLong(index++, schedule.timeOfDayMillis) }
            is DesktopReminderSchedule.Monthly -> { statement.setString(index++, "MONTHLY"); statement.setString(index++, ""); statement.setNull(index++, java.sql.Types.INTEGER); statement.setInt(index++, schedule.dayOfMonth); statement.setLong(index++, schedule.timeOfDayMillis) }
        }
        when (val condition = reminder.condition) {
            DesktopReminderCondition.Always -> { statement.setString(index++, "ALWAYS"); statement.setNull(index++, java.sql.Types.INTEGER) }
            is DesktopReminderCondition.ActivityNotTrackedToday -> { statement.setString(index++, "ACTIVITY_NOT_TRACKED_TODAY"); statement.setLong(index++, condition.activityId) }
        }
        if (reminder.id != 0L) statement.setLong(index, id)
        check(statement.executeUpdate() == 1)
    }
    id
}

fun DesktopDatabase.deleteScheduledReminder(id: Long): Boolean = crudConnection().use { db ->
    db.prepareStatement("DELETE FROM scheduled_reminders WHERE id=?").use { statement -> statement.setLong(1, id); statement.executeUpdate() == 1 }
}

fun DesktopDatabase.activityReminderOverride(activityId: Long): DesktopActivityReminderOverride = crudConnection().use { db ->
    db.prepareStatement("SELECT mode, duration_seconds, recurrent, days_of_week, dnd_start_millis, dnd_end_millis FROM activity_reminder_overrides WHERE activity_id=?").use { query ->
        query.setLong(1, activityId)
        query.executeQuery().use { result ->
            if (!result.next() || result.getString("mode") == "DISABLED") DesktopActivityReminderOverride.Disabled
            else DesktopActivityReminderOverride.Custom(result.getLong("duration_seconds"), result.getInt("recurrent") != 0, parseDays(result.getString("days_of_week")), result.getLong("dnd_start_millis"), result.getLong("dnd_end_millis"))
        }
    }
}

fun DesktopDatabase.saveActivityReminderOverride(activityId: Long, override: DesktopActivityReminderOverride): Boolean = crudConnection().use { db ->
    db.prepareStatement("INSERT INTO activity_reminder_overrides(activity_id, mode, duration_seconds, recurrent, days_of_week, dnd_start_millis, dnd_end_millis) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(activity_id) DO UPDATE SET mode=excluded.mode, duration_seconds=excluded.duration_seconds, recurrent=excluded.recurrent, days_of_week=excluded.days_of_week, dnd_start_millis=excluded.dnd_start_millis, dnd_end_millis=excluded.dnd_end_millis").use { statement ->
        statement.setLong(1, activityId)
        when (override) {
            DesktopActivityReminderOverride.Disabled -> { statement.setString(2, "DISABLED"); statement.setLong(3, 0); statement.setInt(4, 0); statement.setString(5, ""); statement.setLong(6, 0); statement.setLong(7, 0) }
            is DesktopActivityReminderOverride.Custom -> { statement.setString(2, "CUSTOM"); statement.setLong(3, override.durationSeconds.coerceAtLeast(0)); statement.setInt(4, if (override.recurrent) 1 else 0); statement.setString(5, encodeDays(override.daysOfWeek)); statement.setLong(6, override.dndStartMillis.coerceIn(0, 86_399_999)); statement.setLong(7, override.dndEndMillis.coerceIn(0, 86_399_999)) }
        }
        statement.executeUpdate() == 1
    }
}

private fun java.sql.PreparedStatement.bindComplexRule(offset:Int, rule:DesktopComplexRule) { setInt(offset,if(rule.disabled)1 else 0);setString(offset+1,rule.action.name);setInt(offset+2,if(rule.disallowOnlyPrevious)1 else 0);setString(offset+3,encodeIds(rule.startingActivityIds));setString(offset+4,encodeIds(rule.currentActivityIds));setString(offset+5,encodeDays(rule.daysOfWeek)) }
private fun complexRuleTags(db:java.sql.Connection,id:Long):List<DesktopComplexRuleTag> = db.prepareStatement("SELECT tag_id,numeric_value,select_value_on_start FROM complex_rule_tags WHERE rule_id=? ORDER BY tag_id").use{q->q.setLong(1,id);q.executeQuery().use{r->buildList{while(r.next()){val value=r.getDouble("numeric_value").takeUnless{r.wasNull()};add(DesktopComplexRuleTag(r.getLong("tag_id"),value,r.getInt("select_value_on_start")!=0))}}}}
private fun suggestionItems(db:java.sql.Connection,id:Long):Set<Long> = db.prepareStatement("SELECT activity_id FROM activity_suggestion_items WHERE suggestion_id=?").use{q->q.setLong(1,id);q.executeQuery().use{r->buildSet{while(r.next())add(r.getLong(1))}}}
private fun parseIds(value:String):Set<Long> = value.split(',').mapNotNull{it.toLongOrNull()}.toSet()
private fun encodeIds(value:Set<Long>):String = value.sorted().joinToString(",")
private fun parseDays(value:String):Set<DayOfWeek> = value.mapNotNull{it.digitToIntOrNull()?.takeIf{n->n in 1..7}?.let(DayOfWeek::of)}.toSet()
private fun encodeDays(value:Set<DayOfWeek>):String = value.sortedBy{it.value}.joinToString(""){it.value.toString()}
