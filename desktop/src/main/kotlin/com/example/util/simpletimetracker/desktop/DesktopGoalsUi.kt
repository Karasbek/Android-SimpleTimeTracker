package com.example.util.simpletimetracker.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun ModernGoalsPage(
    database: DesktopDatabase,
    timeService: DesktopTimeService,
    recordsRangeService: DesktopRecordsRangeService,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    revision: Int,
    onChanged: () -> Unit,
) {
    var editing by remember { mutableStateOf<DesktopGoal?>(null) }
    var creating by remember { mutableStateOf(false) }
    var hideFinished by remember { mutableStateOf(false) }
    val service = remember { DesktopGoalsService(database, timeService) }
    // Goals are deliberately retroactive: current configuration is evaluated for the selected date,
    // exactly as Android does (there is no goal-version history).
    val allRecords = remember(revision) { recordsRangeService.get(DesktopTimeRange(0L, Long.MAX_VALUE)) }
    val progress = remember(revision, date, hideFinished) { service.progress(date, allRecords, hideFinished) }
    Column(Modifier.fillMaxSize().padding(DesktopUiTokens.ScreenPadding)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Цели", style = MaterialTheme.typography.h4)
                Text(date.format(DateTimeFormatter.ofPattern("d MMMM yyyy")), style = MaterialTheme.typography.body2, color = DesktopUiTokens.SecondaryText)
            }
            OutlinedButton(onClick = { onDateChange(date.minusDays(1)) }) { Text("←") }
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { onDateChange(timeService.userDate()) }) { Text("Сегодня") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { onDateChange(date.plusDays(1)) }) { Text("→") }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { creating = true }) { Text("+ Цель") }
            Checkbox(hideFinished, { hideFinished = it })
            Text("Скрывать выполненные", style = MaterialTheme.typography.body2)
        }
        Spacer(Modifier.height(DesktopUiTokens.SectionGap))
        if (progress.isEmpty()) ModernGoalsEmptyState("Нет подходящих целей для выбранной даты.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            items(progress, key = { it.goal.id }) { item ->
                Card(elevation = 0.dp, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.ownerName, style = MaterialTheme.typography.h6)
                                Text(goalDescription(item.goal), style = MaterialTheme.typography.body2, color = DesktopUiTokens.SecondaryText)
                            }
                            Text(if (item.successful) "✓" else "${item.percent}%", style = MaterialTheme.typography.h6, color = if (item.successful) DesktopUiTokens.Primary else Color.Unspecified)
                        }
                        Text("${goalValueText(item.goal.measure, item.current)} / ${goalValueText(item.goal.measure, item.target)}", style = MaterialTheme.typography.body1)
                        Box(Modifier.fillMaxWidth().height(8.dp).padding(top = 1.dp)) {
                            Box(Modifier.fillMaxWidth().height(7.dp).background(DesktopUiTokens.Divider, MaterialTheme.shapes.small))
                            Box(Modifier.fillMaxWidth((item.current.toFloat() / item.target.coerceAtLeast(1L)).coerceIn(0f, 1f)).height(7.dp).background(if (item.successful) DesktopUiTokens.Primary else DesktopUiTokens.Active, MaterialTheme.shapes.small))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editing = item.goal }) { Text("Изменить") }
                            TextButton(onClick = { service.delete(item.goal.id); onChanged() }) { Text("Удалить") }
                        }
                    }
                }
            }
        }
    }
    if (creating || editing != null) ModernGoalEditorDialog(
        goal = editing,
        database = database,
        service = service,
        onDismiss = { creating = false; editing = null },
        onSaved = { creating = false; editing = null; onChanged() },
    )
}

@Composable
private fun ModernGoalsEmptyState(message: String) {
    Card(elevation = 0.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Text(message, Modifier.padding(28.dp), color = DesktopUiTokens.SecondaryText)
    }
}

@Composable
private fun ModernGoalEditorDialog(
    goal: DesktopGoal?,
    database: DesktopDatabase,
    service: DesktopGoalsService,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var ownerType by remember(goal?.id) { mutableStateOf(goal?.ownerType ?: DesktopGoalOwnerType.ACTIVITY) }
    var ownerId by remember(goal?.id) { mutableStateOf(goal?.ownerId ?: database.activities().firstOrNull()?.id ?: 0L) }
    var range by remember(goal?.id) { mutableStateOf(goal?.range ?: DesktopGoalRange.DAILY) }
    var measure by remember(goal?.id) { mutableStateOf(goal?.measure ?: DesktopGoalMeasure.DURATION) }
    var subtype by remember(goal?.id) { mutableStateOf(goal?.subtype ?: DesktopGoalSubtype.GOAL) }
    var value by remember(goal?.id) { mutableStateOf(goal?.value?.toString() ?: "3600") }
    var days by remember(goal?.id) { mutableStateOf(goal?.daysOfWeek ?: DayOfWeek.entries.toSet()) }
    var error by remember { mutableStateOf<String?>(null) }
    val owners = when (ownerType) {
        DesktopGoalOwnerType.ACTIVITY -> database.activities().map { it.id to it.name } + database.archivedActivities().map { it.id to it.name }
        DesktopGoalOwnerType.CATEGORY -> database.categories().map { it.id to it.name }
        DesktopGoalOwnerType.TAG -> database.tags().map { it.id to it.name }
    }
    if (owners.none { it.first == ownerId }) ownerId = owners.firstOrNull()?.first ?: 0L
    DesktopDialogSurface(if (goal == null) "Новая цель" else "Изменить цель", onDismiss, wide = true) {
        GoalEnumSelector("Объект", DesktopGoalOwnerType.entries.toList(), ownerType, { it.name }, { ownerType = it })
        GoalOwnerSelector("${ownerTypeLabel(ownerType)}", owners, ownerId, { ownerId = it })
        GoalEnumSelector("Диапазон", DesktopGoalRange.entries.toList(), range, { rangeLabel(it) }, { range = it })
        GoalEnumSelector("Измерение", DesktopGoalMeasure.entries.toList(), measure, { if (it == DesktopGoalMeasure.DURATION) "Длительность (сек.)" else "Количество записей" }, { measure = it })
        GoalEnumSelector("Тип", DesktopGoalSubtype.entries.toList(), subtype, { if (it == DesktopGoalSubtype.GOAL) "Цель" else "Лимит" }, { subtype = it })
        OutlinedTextField(value, { value = it }, label = { Text(if (measure == DesktopGoalMeasure.DURATION) "Значение, секунд" else "Количество") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (range == DesktopGoalRange.DAILY) {
            Text("Дни недели", style = MaterialTheme.typography.subtitle1)
            DayOfWeek.entries.chunked(2).forEach { pair -> Row(Modifier.fillMaxWidth()) {
                pair.forEach { day -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(day in days, { checked -> days = days.toMutableSet().apply { if (checked) add(day) else remove(day) } })
                    Text(dayLabel(day))
                } }
            } }
        }
        error?.let { Text(it, color = MaterialTheme.colors.error) }
        DesktopDialogActions(onDismiss, "Сохранить", onConfirm = {
            val parsed = value.toLongOrNull()
            if (ownerId == 0L || parsed == null || parsed < 0L || (range == DesktopGoalRange.DAILY && days.isEmpty())) {
                error = "Укажите объект, неотрицательное значение и хотя бы один день"
                return@DesktopDialogActions
            }
            if (service.save(DesktopGoal(goal?.id ?: 0L, ownerType, ownerId, range, measure, subtype, parsed, days)).first == DesktopGoalWriteResult.SAVED) {
                onSaved()
            } else error = "Не удалось сохранить цель: объект недоступен"
        })
    }
}

@Composable
private fun <T> GoalEnumSelector(label: String, values: List<T>, selected: T, labelOf: (T) -> String, onSelected: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column { Text(label, style = MaterialTheme.typography.caption, color = DesktopUiTokens.SecondaryText); Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(labelOf(selected), Modifier.weight(1f)); Text("⌄") }
        DropdownMenu(open, { open = false }) { values.forEach { value -> DropdownMenuItem(onClick = { open = false; onSelected(value) }) { Text(labelOf(value)) } } }
    } }
}

@Composable
private fun GoalOwnerSelector(label: String, values: List<Pair<Long, String>>, selected: Long, onSelected: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column { Text(label, style = MaterialTheme.typography.caption, color = DesktopUiTokens.SecondaryText); Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(values.firstOrNull { it.first == selected }?.second ?: "Нет объектов", Modifier.weight(1f)); Text("⌄") }
        DropdownMenu(open, { open = false }) { values.forEach { (id, name) -> DropdownMenuItem(onClick = { open = false; onSelected(id) }) { Text(name) } } }
    } }
}

private fun ownerTypeLabel(type: DesktopGoalOwnerType): String = when (type) { DesktopGoalOwnerType.ACTIVITY -> "Активность"; DesktopGoalOwnerType.CATEGORY -> "Категория"; DesktopGoalOwnerType.TAG -> "Тег" }
private fun rangeLabel(range: DesktopGoalRange): String = when (range) { DesktopGoalRange.SESSION -> "Сессия"; DesktopGoalRange.DAILY -> "День"; DesktopGoalRange.WEEKLY -> "Неделя"; DesktopGoalRange.MONTHLY -> "Месяц" }
private fun dayLabel(day: DayOfWeek): String = when (day) { DayOfWeek.MONDAY -> "Пн"; DayOfWeek.TUESDAY -> "Вт"; DayOfWeek.WEDNESDAY -> "Ср"; DayOfWeek.THURSDAY -> "Чт"; DayOfWeek.FRIDAY -> "Пт"; DayOfWeek.SATURDAY -> "Сб"; DayOfWeek.SUNDAY -> "Вс" }
private fun goalDescription(goal: DesktopGoal): String = "${ownerTypeLabel(goal.ownerType)} · ${rangeLabel(goal.range)} · ${if (goal.subtype == DesktopGoalSubtype.GOAL) "цель" else "лимит"}"
private fun goalValueText(measure: DesktopGoalMeasure, value: Long): String = if (measure == DesktopGoalMeasure.DURATION) "%02d:%02d:%02d".format(value / 3_600L, (value % 3_600L) / 60L, value % 60L) else "$value записей"
