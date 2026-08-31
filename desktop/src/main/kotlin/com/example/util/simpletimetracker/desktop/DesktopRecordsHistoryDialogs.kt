package com.example.util.simpletimetracker.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Compact Compose calendar used by Records; selection remains a shifted-day selection in DesktopTimeService. */
@Composable
internal fun ModernCalendarDialog(
    selected: LocalDate,
    onDismiss: () -> Unit,
    onSelected: (LocalDate) -> Unit,
) {
    var month by remember(selected) { mutableStateOf(YearMonth.from(selected)) }
    DesktopDialogSurface("Выбрать день", onDismiss) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { month = month.minusMonths(1) }) { Text("←") }
            Text(
                month.format(DateTimeFormatter.ofPattern("LLLL yyyy")),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.h6,
            )
            OutlinedButton(onClick = { month = month.plusMonths(1) }) { Text("→") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DayOfWeek.entries.forEach { day -> Text(day.name.take(2), style = MaterialTheme.typography.caption) }
        }
        val offset = month.atDay(1).dayOfWeek.value - 1
        val cells = List(offset) { null } + (1..month.lengthOfMonth()).map(month::atDay)
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { date ->
                    if (date == null) Spacer(Modifier.weight(1f))
                    else if (date == selected) Button(modifier = Modifier.weight(1f), onClick = { onSelected(date) }) { Text(date.dayOfMonth.toString()) }
                    else TextButton(modifier = Modifier.weight(1f), onClick = { onSelected(date) }) { Text(date.dayOfMonth.toString()) }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        DesktopDialogActions(onDismiss, "Сегодня", { onSelected(LocalDate.now()) })
    }
}

@Composable
internal fun ModernCustomRangeDialog(
    initial: DesktopTimeRange?,
    onDismiss: () -> Unit,
    onApply: (DesktopTimeRange) -> Unit,
) {
    var startedAt by remember { mutableStateOf(initial?.startedAt?.let(::modernDateTimeText).orEmpty()) }
    var endedAt by remember { mutableStateOf(initial?.endedAt?.takeIf { it != Long.MAX_VALUE }?.let(::modernDateTimeText).orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    DesktopDialogSurface("Произвольный диапазон", onDismiss) {
        Text("Границы полузакрытые: начало включается, конец не включается.", style = MaterialTheme.typography.body2, color = DesktopUiTokens.SecondaryText)
        ModernHistoryField("Начало", startedAt, { startedAt = it }, "гггг-мм-дд чч:мм")
        ModernHistoryField("Конец", endedAt, { endedAt = it }, "гггг-мм-дд чч:мм")
        error?.let { Text(it, color = MaterialTheme.colors.error) }
        DesktopDialogActions(onDismiss, "Применить", {
            val start = modernDateTimeParse(startedAt)
            val end = modernDateTimeParse(endedAt)
            if (start == null || end == null || end < start) error = "Укажите корректные начало и конец диапазона"
            else onApply(DesktopTimeRange(start, end))
        })
    }
}

@Composable
internal fun ModernSplitRecordDialog(
    record: DesktopTimelineRecord,
    activities: List<ActivityRow>,
    service: DesktopRecordActionsService,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var splitAt by remember(record.id) { mutableStateOf(modernDateTimeText((record.startedAt + record.endedAt) / 2)) }
    var afterActivity by remember(record.id) { mutableStateOf(record.activityId) }
    var error by remember { mutableStateOf<String?>(null) }
    DesktopDialogSurface("Разделить запись", onDismiss) {
        Text("Первая часть сохранит исходную активность; для второй можно выбрать другую.", style = MaterialTheme.typography.body2, color = DesktopUiTokens.SecondaryText)
        ModernHistoryField("Момент разделения", splitAt, { splitAt = it }, "гггг-мм-дд чч:мм")
        ModernSplitSelector("Активность второй части", activities, afterActivity) { afterActivity = it }
        error?.let { Text(it, color = MaterialTheme.colors.error) }
        DesktopDialogActions(onDismiss, "Разделить", {
            val at = modernDateTimeParse(splitAt)
            if (at == null || at <= record.startedAt || at >= record.endedAt) error = "Момент должен быть внутри исходной записи"
            else if (service.split(record.id, at, afterActivity) == RecordWriteResult.SAVED) onSaved()
            else error = "Не удалось разделить запись"
        })
    }
}

@Composable
private fun ModernSplitSelector(label: String, activities: List<ActivityRow>, selectedId: Long, onSelected: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.caption, color = DesktopUiTokens.SecondaryText)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(activities.firstOrNull { it.id == selectedId }?.name.orEmpty(), modifier = Modifier.weight(1f))
                Text("⌄")
            }
            androidx.compose.material.DropdownMenu(expanded, { expanded = false }) {
                activities.forEach { activity ->
                    androidx.compose.material.DropdownMenuItem(onClick = { expanded = false; onSelected(activity.id) }) { Text(activity.name) }
                }
            }
        }
    }
}

@Composable
private fun ModernHistoryField(label: String, value: String, onValueChange: (String) -> Unit, hint: String) {
    androidx.compose.material.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
    )
}
