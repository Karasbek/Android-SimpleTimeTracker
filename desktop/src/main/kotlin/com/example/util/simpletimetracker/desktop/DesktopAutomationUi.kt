@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.util.simpletimetracker.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
internal fun ModernRuleNumericTagsDialog(
    tags: List<DesktopTag>,
    onDismiss: () -> Unit,
    onConfirm: (List<DesktopRecordTag>) -> Unit,
) {
    var values by remember(tags) { mutableStateOf(tags.associate { it.id to "" }) }
    DesktopDialogSurface("Значения тегов", onDismiss) {
        Text("Правило запуска просит указать числовые значения.", color = DesktopUiTokens.SecondaryText)
        if (tags.isEmpty()) {
            Text("Нужный тег больше недоступен.", color = MaterialTheme.colors.error)
        }
        tags.forEach { tag ->
            OutlinedTextField(
                value = values[tag.id].orEmpty(),
                onValueChange = { values = values + (tag.id to it) },
                label = { Text(tag.name + tag.valueSuffix.takeIf(String::isNotBlank).orEmpty()) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val parsed = tags.mapNotNull { tag -> values[tag.id]?.toDoubleOrNull()?.let { DesktopRecordTag(tag.id, it) } }
        DesktopDialogActions(onDismiss, "Начать", { onConfirm(parsed) }, enabled = parsed.size == tags.size && tags.isNotEmpty())
    }
}

@Composable
internal fun ModernAutomationPage(
    database: DesktopDatabase,
    timeService: DesktopTimeService,
    activities: List<ActivityRow>,
    revision: Int,
    onChanged: () -> Unit,
) {
    var ruleEditor by remember { mutableStateOf<DesktopComplexRule?>(null) }
    var createRule by remember { mutableStateOf(false) }
    var suggestionEditor by remember { mutableStateOf<DesktopActivitySuggestion?>(null) }
    var createSuggestion by remember { mutableStateOf(false) }
    var reminderEditor by remember { mutableStateOf<DesktopScheduledReminder?>(null) }
    var createReminder by remember { mutableStateOf(false) }
    var overrideActivity by remember { mutableStateOf<ActivityRow?>(null) }
    val rules = remember(revision) { database.complexRules() }
    val suggestions = remember(revision) { database.activitySuggestions() }
    val reminders = remember(revision) { database.scheduledReminders() }
    val tags = remember(revision) { database.tags().filterNot(DesktopTag::archived) }

    Column(Modifier.fillMaxSize().padding(DesktopUiTokens.ScreenPadding)) {
        DesktopPageHeader("Автоматизация", "Правила запуска, подсказки и продуктовые расписания")
        Spacer(Modifier.height(DesktopUiTokens.SectionGap))
        AutomationSection("Сложные правила", "Они применяются общим DesktopTimerService для Tracker, tray и repeat.", "+ Правило", { createRule = true }) {
            if (rules.isEmpty()) Text("Нет правил", color = DesktopUiTokens.SecondaryText)
            rules.forEach { rule -> AutomationRow(
                title = rule.action.readable(),
                detail = rule.conditionText(activities),
                onEdit = { ruleEditor = rule },
                onDelete = { database.deleteComplexRule(rule.id); onChanged() },
            ) }
        }
        Spacer(Modifier.height(14.dp))
        AutomationSection("Подсказки активностей", "Отображаются на Tracker для запущенной либо последней активности.", "+ Подсказка", { createSuggestion = true }) {
            if (suggestions.isEmpty()) Text("Нет подсказок", color = DesktopUiTokens.SecondaryText)
            suggestions.forEach { suggestion -> AutomationRow(
                title = activityName(activities, suggestion.forActivityId),
                detail = "Предлагать: " + suggestion.suggestionActivityIds.joinToString { activityName(activities, it) },
                onEdit = { suggestionEditor = suggestion },
                onDelete = { database.deleteActivitySuggestion(suggestion.id); onChanged() },
            ) }
        }
        Spacer(Modifier.height(14.dp))
        AutomationSection("Запланированные напоминания", "Расписание и условия хранятся как product data; Linux delivery — отдельный platform adapter.", "+ Напоминание", { createReminder = true }) {
            if (reminders.isEmpty()) Text("Нет расписаний", color = DesktopUiTokens.SecondaryText)
            reminders.forEach { reminder ->
                val next = DesktopReminderOccurrenceCalculator.next(reminder.schedule, System.currentTimeMillis(), java.time.ZoneId.systemDefault())
                AutomationRow(reminder.text.ifBlank { "Напоминание" }, "${if (reminder.enabled) "Включено" else "Отключено"} · ${reminder.schedule.readable()} · следующее: ${next?.expectedAt?.let(::modernDateTimeText) ?: "—"}", { reminderEditor = reminder }, { database.deleteScheduledReminder(reminder.id); onChanged() })
            }
        }
        Spacer(Modifier.height(14.dp))
        AutomationSection("Переопределения напоминаний активности", "Персональные duration, recurrence и DND для активности.", "Настроить", { overrideActivity = activities.firstOrNull() }) {
            activities.filter { database.activityReminderOverride(it.id) !is DesktopActivityReminderOverride.Disabled }.forEach { activity ->
                AutomationRow(activity.name, database.activityReminderOverride(activity.id).readable(), { overrideActivity = activity }, { database.saveActivityReminderOverride(activity.id, DesktopActivityReminderOverride.Disabled); onChanged() })
            }
        }
    }
    if (createRule || ruleEditor != null) ComplexRuleDialog(ruleEditor, activities, tags, onDismiss = { createRule = false; ruleEditor = null }, onSave = { database.saveComplexRule(it); createRule = false; ruleEditor = null; onChanged() })
    if (createSuggestion || suggestionEditor != null) SuggestionDialog(suggestionEditor, activities, onDismiss = { createSuggestion = false; suggestionEditor = null }, onSave = { database.saveActivitySuggestion(it); createSuggestion = false; suggestionEditor = null; onChanged() })
    if (createReminder || reminderEditor != null) ReminderDialog(reminderEditor, activities, onDismiss = { createReminder = false; reminderEditor = null }, onSave = { database.saveScheduledReminder(it); createReminder = false; reminderEditor = null; onChanged() })
    overrideActivity?.let { activity -> OverrideDialog(activity, database.activityReminderOverride(activity.id), onDismiss = { overrideActivity = null }, onSave = { database.saveActivityReminderOverride(activity.id, it); overrideActivity = null; onChanged() }) }
}

@Composable private fun AutomationSection(title: String, subtitle: String, action: String, onAction: () -> Unit, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), elevation = 0.dp, shape = MaterialTheme.shapes.medium) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(title, style = MaterialTheme.typography.h6); Text(subtitle, style = MaterialTheme.typography.caption, color = DesktopUiTokens.SecondaryText) }; OutlinedButton(onClick = onAction) { Text(action) } }
        content()
    } }
}
@Composable private fun AutomationRow(title: String, detail: String, onEdit: () -> Unit, onDelete: () -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(title); Text(detail, style = MaterialTheme.typography.caption, color = DesktopUiTokens.SecondaryText) }; Row { OutlinedButton(onClick = onEdit) { Text("Изменить") }; Spacer(Modifier.padding(3.dp)); OutlinedButton(onClick = onDelete) { Text("Удалить") } } } }

@Composable private fun ComplexRuleDialog(rule: DesktopComplexRule?, activities: List<ActivityRow>, tags: List<DesktopTag>, onDismiss: () -> Unit, onSave: (DesktopComplexRule) -> Unit) {
    var action by remember(rule) { mutableStateOf(rule?.action ?: DesktopComplexRuleAction.DISALLOW_MULTITASKING) }
    var disabled by remember(rule) { mutableStateOf(rule?.disabled ?: false) }
    var onlyPrevious by remember(rule) { mutableStateOf(rule?.disallowOnlyPrevious ?: false) }
    var starting by remember(rule) { mutableStateOf(rule?.startingActivityIds.orEmpty()) }
    var current by remember(rule) { mutableStateOf(rule?.currentActivityIds.orEmpty()) }
    var days by remember(rule) { mutableStateOf(rule?.daysOfWeek.orEmpty()) }
    var assigned by remember(rule) { mutableStateOf(rule?.assignedTags.orEmpty()) }
    var numericValues by remember(rule) { mutableStateOf(rule?.assignedTags.orEmpty().associate { it.tagId to it.numericValue?.toString().orEmpty() }) }
    var selectOnStart by remember(rule) { mutableStateOf<Set<Long>>(rule?.assignedTags.orEmpty().filter(DesktopComplexRuleTag::selectValueOnStart).map(DesktopComplexRuleTag::tagId).toSet()) }
    DesktopDialogSurface(if (rule == null) "Новое правило" else "Изменить правило", onDismiss, wide = true) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DesktopComplexRuleAction.entries.forEach { value -> OutlinedButton(onClick = { action = value }) { Text(if (action == value) "✓ ${value.readable()}" else value.readable()) } } }
        Row { Checkbox(disabled, { disabled = it }); Text("Отключено") }
        if (action == DesktopComplexRuleAction.DISALLOW_MULTITASKING) Row { Checkbox(onlyPrevious, { onlyPrevious = it }); Text("Останавливать только подходящие текущие активности") }
        ActivityChecks("Начинаемая активность", activities, starting) { starting = it }
        ActivityChecks("Текущие активности", activities, current) { current = it }
        DayChecks(days) { days = it }
        if (action == DesktopComplexRuleAction.ASSIGN_TAG) {
            Text("Назначаемые теги", style = MaterialTheme.typography.subtitle1)
            tags.forEach { tag ->
                val selected = assigned.any { it.tagId == tag.id }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(selected, { checked -> assigned = if (checked) assigned + DesktopComplexRuleTag(tag.id, null) else assigned.filterNot { it.tagId == tag.id }; if (!checked) selectOnStart = selectOnStart - tag.id })
                    Text(tag.name)
                    if (selected && tag.valueType == DesktopTagValueType.NUMERIC) {
                        OutlinedTextField(numericValues[tag.id].orEmpty(), { numericValues = numericValues + (tag.id to it) }, label = { Text("Значение (пусто: спросить)") }, modifier = Modifier.weight(1f))
                        Checkbox(tag.id in selectOnStart, { checked -> selectOnStart = if (checked) selectOnStart + tag.id else selectOnStart - tag.id })
                        Text("спросить")
                    }
                }
            }
        }
        DesktopDialogActions(onDismiss, "Сохранить", {
            val details = assigned.map { tag -> tag.copy(numericValue = numericValues[tag.tagId]?.toDoubleOrNull(), selectValueOnStart = tag.tagId in selectOnStart) }
            onSave(DesktopComplexRule(rule?.id ?: 0, disabled, action, onlyPrevious, details, starting, current, days))
        }, enabled = starting.isNotEmpty() || current.isNotEmpty() || days.isNotEmpty())
    }
}

@Composable private fun SuggestionDialog(suggestion: DesktopActivitySuggestion?, activities: List<ActivityRow>, onDismiss: () -> Unit, onSave: (DesktopActivitySuggestion) -> Unit) {
    var source by remember(suggestion, activities) { mutableStateOf(suggestion?.forActivityId ?: activities.firstOrNull()?.id ?: 0L) }
    var targets by remember(suggestion) { mutableStateOf(suggestion?.suggestionActivityIds.orEmpty()) }
    DesktopDialogSurface("Подсказка активности", onDismiss, wide = true) { Text("Когда активна (или была последней):")
        ActivityChecks("Источник", activities, setOf(source)) { source = it.firstOrNull() ?: source }
        ActivityChecks("Предлагать", activities, targets) { targets = it }
        DesktopDialogActions(onDismiss, "Сохранить", { onSave(DesktopActivitySuggestion(suggestion?.id ?: 0, source, targets - source)) }, source != 0L && targets.isNotEmpty())
    }
}

@Composable private fun ReminderDialog(reminder: DesktopScheduledReminder?, activities: List<ActivityRow>, onDismiss: () -> Unit, onSave: (DesktopScheduledReminder) -> Unit) {
    var enabled by remember(reminder) { mutableStateOf(reminder?.enabled ?: true) }; var text by remember(reminder) { mutableStateOf(reminder?.text.orEmpty()) }; var kind by remember(reminder) { mutableStateOf(reminder?.schedule?.javaClass?.simpleName ?: "Weekly") }; var time by remember(reminder) { mutableStateOf(reminder?.schedule?.timeOfDayMillis?.let(::timeText) ?: "09:00") }; var days by remember(reminder) { mutableStateOf((reminder?.schedule as? DesktopReminderSchedule.Weekly)?.daysOfWeek.orEmpty()) }; var date by remember(reminder) { mutableStateOf((reminder?.schedule as? DesktopReminderSchedule.OneTime)?.localEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty()) }; var dayOfMonth by remember(reminder) { mutableStateOf((reminder?.schedule as? DesktopReminderSchedule.Monthly)?.dayOfMonth?.toString() ?: "1") }; var conditionalActivity by remember(reminder) { mutableStateOf((reminder?.condition as? DesktopReminderCondition.ActivityNotTrackedToday)?.activityId) }
    DesktopDialogSurface("Напоминание", onDismiss, wide = true) { Row { Checkbox(enabled, { enabled = it }); Text("Включено") }; OutlinedTextField(text, { text = it }, label = { Text("Текст") }, modifier = Modifier.fillMaxWidth()); FlowRow { listOf("Weekly" to "Еженедельно", "OneTime" to "Один раз", "Monthly" to "Ежемесячно").forEach { (value,label) -> OutlinedButton(onClick = { kind = value }) { Text(if(kind==value) "✓ $label" else label) } } }; OutlinedTextField(time, { time = it }, label = { Text("Время HH:mm") }); when(kind) { "Weekly" -> DayChecks(days) { days = it }; "OneTime" -> OutlinedTextField(date, { date = it }, label = { Text("Локальная дата YYYY-MM-DD") }); else -> OutlinedTextField(dayOfMonth, { dayOfMonth = it }, label = { Text("День месяца") }) }; Text("Условие", style=MaterialTheme.typography.subtitle1); Row { Checkbox(conditionalActivity != null, { conditionalActivity = if(it) activities.firstOrNull()?.id else null }); Text("Активность ещё не отслеживалась сегодня") }; conditionalActivity?.let { selected -> ActivityChecks("Активность", activities, setOf(selected)) { conditionalActivity = it.firstOrNull() } }; val schedule = reminderSchedule(kind,time,days,date,dayOfMonth); DesktopDialogActions(onDismiss,"Сохранить",{ schedule?.let { onSave(DesktopScheduledReminder(reminder?.id ?: 0, enabled, text, it, conditionalActivity?.let(DesktopReminderCondition::ActivityNotTrackedToday) ?: DesktopReminderCondition.Always)) } },schedule!=null) }
}

@Composable private fun OverrideDialog(activity: ActivityRow, current: DesktopActivityReminderOverride, onDismiss: () -> Unit, onSave: (DesktopActivityReminderOverride) -> Unit) { val custom=current as? DesktopActivityReminderOverride.Custom; var enabled by remember(current){mutableStateOf(custom!=null)};var seconds by remember(current){mutableStateOf(custom?.durationSeconds?.toString()?:"3600")};var recurrent by remember(current){mutableStateOf(custom?.recurrent?:false)};var days by remember(current){mutableStateOf(custom?.daysOfWeek.orEmpty())};var dndStart by remember(current){mutableStateOf(custom?.dndStartMillis?.let(::timeText)?:"22:00")};var dndEnd by remember(current){mutableStateOf(custom?.dndEndMillis?.let(::timeText)?:"07:00")};DesktopDialogSurface("Напоминание: ${activity.name}",onDismiss){Row{Checkbox(enabled,{enabled=it});Text("Пользовательское правило")};if(enabled){OutlinedTextField(seconds,{seconds=it},label={Text("Длительность, секунды")});Row{Checkbox(recurrent,{recurrent=it});Text("Повторять")};DayChecks(days){days=it};OutlinedTextField(dndStart,{dndStart=it},label={Text("DND от HH:mm")});OutlinedTextField(dndEnd,{dndEnd=it},label={Text("DND до HH:mm")})};DesktopDialogActions(onDismiss,"Сохранить",{onSave(if(!enabled)DesktopActivityReminderOverride.Disabled else DesktopActivityReminderOverride.Custom(seconds.toLongOrNull()?.coerceAtLeast(0)?:0,recurrent,days,parseTime(dndStart)?:0,parseTime(dndEnd)?:0))})} }

@Composable private fun ActivityChecks(label:String, activities:List<ActivityRow>, selected:Set<Long>, onChange:(Set<Long>)->Unit){Text(label,style=MaterialTheme.typography.subtitle1);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){activities.forEach{a->Row{Checkbox(a.id in selected,{checked->onChange(if(checked)selected+a.id else selected-a.id)});Text(a.name)}}}}
@Composable private fun DayChecks(selected:Set<DayOfWeek>,onChange:(Set<DayOfWeek>)->Unit){Text("Дни недели",style=MaterialTheme.typography.subtitle1);FlowRow{DayOfWeek.entries.forEach{day->Row{Checkbox(day in selected,{checked->onChange(if(checked)selected+day else selected-day)});Text(day.name.take(3))}}}}
private fun activityName(activities:List<ActivityRow>,id:Long)=activities.firstOrNull{it.id==id}?.name?:"Удалённая активность #$id"
private fun DesktopComplexRuleAction.readable()=when(this){DesktopComplexRuleAction.ALLOW_MULTITASKING->"Разрешить параллельность";DesktopComplexRuleAction.DISALLOW_MULTITASKING->"Запретить параллельность";DesktopComplexRuleAction.ASSIGN_TAG->"Назначить теги"}
private fun DesktopComplexRule.conditionText(activities:List<ActivityRow>)=listOfNotNull(startingActivityIds.takeIf{it.isNotEmpty()}?.joinToString(prefix="Старт: "){activityName(activities,it)},currentActivityIds.takeIf{it.isNotEmpty()}?.joinToString(prefix="Текущие: "){activityName(activities,it)},daysOfWeek.takeIf{it.isNotEmpty()}?.joinToString(prefix="Дни: "){it.name.take(3)}).joinToString(" · ").ifBlank{"Нет условий"}
private fun DesktopReminderSchedule.readable()=when(this){is DesktopReminderSchedule.Weekly->"Неделя ${daysOfWeek.joinToString()} ${timeText(timeOfDayMillis)}";is DesktopReminderSchedule.OneTime->"${LocalDate.ofEpochDay(localEpochDay)} ${timeText(timeOfDayMillis)}";is DesktopReminderSchedule.Monthly->"Каждый месяц $dayOfMonth ${timeText(timeOfDayMillis)}"}
private fun DesktopActivityReminderOverride.readable()=when(this){DesktopActivityReminderOverride.Disabled->"Отключено";is DesktopActivityReminderOverride.Custom->"${durationSeconds} сек. · DND ${timeText(dndStartMillis)}–${timeText(dndEndMillis)}"}
private fun parseTime(value:String):Long?=runCatching{val p=value.split(':');(p[0].toLong()*3600+p[1].toLong()*60)*1000}.getOrNull()?.takeIf{it in 0 until 86_400_000L}
private fun timeText(value:Long)="%02d:%02d".format(value/3_600_000,(value/60_000)%60)
private fun reminderSchedule(kind:String,time:String,days:Set<DayOfWeek>,date:String,day:String):DesktopReminderSchedule?=parseTime(time)?.let{t->when(kind){"Weekly"->DesktopReminderSchedule.Weekly(days,t);"OneTime"->runCatching{DesktopReminderSchedule.OneTime(LocalDate.parse(date).toEpochDay(),t)}.getOrNull();else->day.toIntOrNull()?.let{DesktopReminderSchedule.Monthly(it,t)}}}
