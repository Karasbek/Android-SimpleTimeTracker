package com.example.util.simpletimetracker.desktop

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JLabel
import javax.swing.JComboBox
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField

private enum class DesktopTab(
    val title: String,
) {
    TRACKER("Трекер"),
    HISTORY("История"),
    STATISTICS("Статистика"),
    ARCHIVE("Архив"),
}

private val dtFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private val dateFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy")

private val clockFormatter =
    DateTimeFormatter
        .ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

private fun durationText(
    milliseconds: Long,
): String {
    val seconds =
        milliseconds.coerceAtLeast(0) / 1000

    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val rest = seconds % 60

    return "%02d:%02d:%02d".format(
        hours,
        minutes,
        rest,
    )
}

private fun clockText(
    milliseconds: Long,
): String {
    return clockFormatter.format(
        Instant.ofEpochMilli(milliseconds),
    )
}

private fun dateTimeText(
    milliseconds: Long,
): String {
    return LocalDateTime
        .ofInstant(
            Instant.ofEpochMilli(milliseconds),
            ZoneId.systemDefault(),
        )
        .format(dtFormatter)
}

private fun parseDateTime(
    text: String,
): Long {
    return LocalDateTime
        .parse(text.trim(), dtFormatter)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

private data class RecordEditResult(
    val activityId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val comment: String,
    val tags: List<DesktopRecordTag>,
)

private fun editRecordDialog(
    record: DesktopTimelineRecord,
    activities: List<ActivityRow>,
    database: DesktopDatabase,
): RecordEditResult? {
    if (activities.isEmpty()) return null
    val activityBox = JComboBox(activities.map(ActivityRow::name).toTypedArray())
    activityBox.selectedIndex = activities.indexOfFirst { it.id == record.activityId }.coerceAtLeast(0)
    val startedField =
        JTextField(dateTimeText(record.startedAt))

    val endedField =
        JTextField(dateTimeText(record.endedAt))

    val commentField =
        JTextField(record.comment)

    val panel = desktopFormPanel()

    panel.add(JLabel("Активность"))
    panel.add(activityBox)

    panel.add(JLabel("Начало"))
    panel.add(startedField)

    panel.add(JLabel("Конец"))
    panel.add(endedField)

    panel.add(JLabel("Комментарий"))
    panel.add(commentField)

    val result = showDesktopConfirmDialog(panel, "Редактировать запись")

    if (result != JOptionPane.OK_OPTION) {
        return null
    }

    return try {
        val startedAt =
            parseDateTime(startedField.text)

        val endedAt =
            parseDateTime(endedField.text)

        val activityId = activities[activityBox.selectedIndex].id
        val tags = selectDesktopRecordTagsDialog(
            tags = database.selectableTagsForActivity(activityId),
            selected = if (activityId == record.activityId) {
                record.tags.map { DesktopRecordTag(it.tagId, it.numericValue) }
            } else {
                emptyList()
            },
        ) ?: return null
        RecordEditResult(
            activityId = activityId,
            startedAt = startedAt,
            endedAt = endedAt,
            comment = commentField.text,
            tags = tags,
        )
    } catch (_: Throwable) {
        JOptionPane.showMessageDialog(
            null,
            "Формат времени: yyyy-MM-dd HH:mm:ss",
        )
        null
    }
}

@Composable
private fun DayNavigation(
    date: LocalDate,
    today: LocalDate,
    onChange: (LocalDate) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            Arrangement.SpaceBetween,
    ) {
        Button(
            onClick = {
                onChange(date.minusDays(1))
            },
        ) {
            Text("←")
        }

        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally,
        ) {
            Text(
                if (date == today) {
                    "Сегодня"
                } else {
                    date.format(dateFormatter)
                },
                fontWeight = FontWeight.Bold,
            )

            if (date != today) {
                TextButton(
                    onClick = {
                        onChange(today)
                    },
                ) {
                    Text("К сегодня")
                }
            }
        }

        Button(
            onClick = {
                onChange(date.plusDays(1))
            },
            enabled = date < today,
        ) {
            Text("→")
        }
    }
}

@Composable
private fun TrackerV2(
    database: DesktopDatabase,
    activityEditorService: DesktopActivityEditorService,
    tagCategoryService: DesktopTagCategoryService,
    semanticPreferences: DesktopSemanticPreferences,
    quickActions: DesktopQuickActions,
    activities: List<ActivityRow>,
    todayRecords: List<DesktopTimelineRecord>,
    todayRange: DesktopTimeRange,
    today: LocalDate,
    now: Long,
    onChanged: () -> Unit,
) {
    var newActivity by remember {
        mutableStateOf("")
    }

    val completedTodayRecords = todayRecords.filterNot(DesktopTimelineRecord::isRunning)

    val finishedByActivity =
        completedTodayRecords
            .groupBy { it.activityId }
            .mapValues { entry ->
                entry.value.sumOf {
                    todayRange.clippedDuration(it.startedAt, it.endedAt)
                }
            }

    val runningTotal = todayRecords.filter(DesktopTimelineRecord::isRunning)
        .sumOf { todayRange.clippedDuration(it.startedAt, it.endedAt) }

    val finishedTotal =
        completedTodayRecords.sumOf { todayRange.clippedDuration(it.startedAt, it.endedAt) }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Row(
            horizontalArrangement =
                Arrangement.spacedBy(28.dp),
        ) {
            Column {
                Text(
                    "Сегодня",
                    style =
                        MaterialTheme.typography.caption,
                )
                Text(
                    durationText(
                        finishedTotal +
                            runningTotal,
                    ),
                    style =
                        MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column {
                Text(
                    "Запущено",
                    style =
                        MaterialTheme.typography.caption,
                )
                Text(
                    activities
                        .count {
                            it.startedAt != null
                        }
                        .toString(),
                    style =
                        MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column {
                Text(
                    "Записей",
                    style =
                        MaterialTheme.typography.caption,
                )
                Text(
                    todayRecords.size.toString(),
                    style =
                        MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        TextButton(
            onClick = {
                if (editDesktopTimeSettingsDialog(semanticPreferences)) onChanged()
            },
        ) {
            Text("Время и недели")
        }

        Spacer(Modifier.height(18.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = quickActions.allowMultitasking,
                onCheckedChange = quickActions::setAllowMultitasking,
            )
            Text("Разрешить несколько таймеров одновременно")
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (quickActions.ignoreShortRecordsDurationSeconds == 0L) {
                    "Короткие записи: сохранять"
                } else {
                    "Не сохранять записи ≤ ${quickActions.ignoreShortRecordsDurationSeconds} сек."
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    val value = JOptionPane.showInputDialog(
                        null,
                        "Порог в секундах (0 — сохранять все)",
                        quickActions.ignoreShortRecordsDurationSeconds.toString(),
                    )?.trim()?.toLongOrNull()
                    if (value != null && value >= 0) {
                        quickActions.setIgnoreShortRecordsDurationSeconds(value)
                    }
                },
            ) {
                Text("Изменить")
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newActivity,
                onValueChange = {
                    newActivity = it
                },
                modifier =
                    Modifier.weight(1f),
                label = {
                    Text("Новая активность")
                },
                singleLine = true,
            )

            Spacer(Modifier.width(10.dp))

            Button(
                onClick = {
                    database.addActivity(
                        newActivity,
                    )
                    newActivity = ""
                    onChanged()
                },
                enabled =
                    newActivity.isNotBlank(),
            ) {
                Text("Добавить")
            }

            Spacer(Modifier.width(8.dp))

            TextButton(
                onClick = {
                    if (manageDesktopTagsDialog(tagCategoryService)) onChanged()
                },
            ) {
                Text("Теги")
            }

            TextButton(
                onClick = {
                    if (manageDesktopCategoriesDialog(tagCategoryService)) onChanged()
                },
            ) {
                Text("Категории")
            }
        }

        Spacer(Modifier.height(18.dp))

        LazyVerticalGrid(
            columns =
                GridCells.Adaptive(
                    minSize = 230.dp,
                ),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement =
                Arrangement.spacedBy(10.dp),
            verticalArrangement =
                Arrangement.spacedBy(10.dp),
        ) {
            gridItems(
                items =
                    activities.sortedWith(
                        compareByDescending<ActivityRow> {
                            it.startedAt != null
                        }.thenBy {
                            it.name.lowercase()
                        },
                    ),
                key = {
                    it.id
                },
            ) { activity ->
                val running =
                    activity.startedAt?.let {
                        todayRange.clippedDuration(
                            it,
                            now,
                        )
                    } ?: 0L

                val total =
                    finishedByActivity
                        .getOrDefault(
                            activity.id,
                            0L,
                        ) +
                        running

                Card(
                    modifier =
                        Modifier.fillMaxWidth(),
                    elevation =
                        if (
                            activity.startedAt != null
                        ) {
                            8.dp
                        } else {
                            2.dp
                        },
                ) {
                    Column(
                        modifier =
                            Modifier.padding(14.dp),
                    ) {
                        Text(
                            activity.name,
                            style =
                                MaterialTheme.typography.h6,
                            fontWeight =
                                FontWeight.Bold,
                        )

                        Spacer(
                            Modifier.height(4.dp),
                        )

                        Text(
                            "Сегодня ${durationText(total)}",
                            style =
                                MaterialTheme.typography.caption,
                        )

                        if (activity.defaultDurationSeconds > 0) {
                            Text(
                                "Мгновенная запись: ${durationText(activity.defaultDurationSeconds * 1000L)}",
                                style = MaterialTheme.typography.caption,
                            )
                        }

                        Spacer(
                            Modifier.height(12.dp),
                        )

                        if (
                            activity.startedAt != null
                        ) {
                            Text(
                                durationText(
                                    now -
                                        activity.startedAt,
                                ),
                                style =
                                    MaterialTheme.typography.h5,
                                fontWeight =
                                    FontWeight.Bold,
                            )

                            Text(
                                "с ${clockText(activity.startedAt)}",
                                style =
                                    MaterialTheme.typography.caption,
                            )
                        } else {
                            Text("Не запущено")
                        }

                        Spacer(
                            Modifier.height(12.dp),
                        )

                        Row(
                            verticalAlignment =
                                Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = {
                                    quickActions.toggle(
                                        activity.id,
                                    )
                                },
                            ) {
                                Text(
                                    if (
                                        activity.startedAt ==
                                        null
                                    ) {
                                        "Старт"
                                    } else {
                                        "Стоп"
                                    },
                                )
                            }

                            Spacer(
                                Modifier.width(4.dp),
                            )

                            TextButton(
                                onClick = {
                                    editDesktopActivityDialog(
                                        activity = activity,
                                        categories = tagCategoryService.categories(),
                                        tags = tagCategoryService.tags(),
                                        selectedCategoryIds = database.categoryIdsForActivity(activity.id),
                                        selectedAllowedTagIds = database.allowedTagIdsForActivity(activity.id),
                                        selectedDefaultTagIds = database.defaultTagIdsForActivity(activity.id),
                                    )?.let { edited ->
                                        when (activityEditorService.update(activity.id, edited)) {
                                            DesktopTaxonomyWriteResult.SAVED -> onChanged()
                                            DesktopTaxonomyWriteResult.INVALID_NAME ->
                                                JOptionPane.showMessageDialog(null, "Укажите название и неотрицательную длительность")
                                            DesktopTaxonomyWriteResult.INVALID_RELATION ->
                                                JOptionPane.showMessageDialog(null, "Выбранная связь недоступна")
                                            DesktopTaxonomyWriteResult.NAME_CONFLICT,
                                            DesktopTaxonomyWriteResult.NOT_FOUND,
                                            -> JOptionPane.showMessageDialog(null, "Не удалось изменить активность")
                                        }
                                    }
                                },
                            ) {
                                Text("Изм.")
                            }

                            TextButton(
                                onClick = {
                                    val answer =
                                        JOptionPane
                                            .showConfirmDialog(
                                                null,
                                                "Архивировать ${activity.name}?",
                                                "Архив",
                                                JOptionPane.YES_NO_OPTION,
                                            )

                                    if (
                                        answer ==
                                        JOptionPane.YES_OPTION
                                    ) {
                                        try {
                                            database
                                                .archiveActivity(
                                                    activity.id,
                                                )
                                            onChanged()
                                        } catch (
                                            e: Throwable
                                        ) {
                                            JOptionPane
                                                .showMessageDialog(
                                                    null,
                                                    e.message,
                                                )
                                        }
                                    }
                                },
                                enabled =
                                    activity.startedAt ==
                                        null,
                            ) {
                                Text("Архив")
                            }

                        }

                        TextButton(
                            onClick = {
                                val pinned = quickActions.state.pinned.any {
                                    it.id == activity.id
                                }
                                quickActions.setPinned(activity.id, !pinned)
                            },
                        ) {
                            Text(
                                if (quickActions.state.pinned.any { it.id == activity.id }) {
                                    "Убрать из tray"
                                } else {
                                    "Закрепить в tray"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeFilterControls(
    database: DesktopDatabase,
    tagCategoryService: DesktopTagCategoryService,
    savedFilterService: DesktopSavedFilterService,
    filter: DesktopRecordFilter,
    onFilterChanged: (DesktopRecordFilter) -> Unit,
    onDataChanged: () -> Unit,
) {
    val allActivities = database.activities() + database.archivedActivities()
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                editDesktopRecordFilterDialog(
                    initial = filter,
                    activities = allActivities,
                    tags = tagCategoryService.tags(),
                    categories = tagCategoryService.categories(),
                )?.let(onFilterChanged)
            },
        ) { Text("Фильтр") }
        TextButton(onClick = { onFilterChanged(DesktopRecordFilter.EMPTY) }) { Text("Сбросить") }
        TextButton(
            onClick = {
                askDesktopSavedFilterName()?.let { name ->
                    when (savedFilterService.save(name = name, filter = filter).first) {
                        DesktopSavedFilterResult.SAVED -> onDataChanged()
                        DesktopSavedFilterResult.INVALID_NAME -> JOptionPane.showMessageDialog(null, "Укажите название")
                        DesktopSavedFilterResult.NAME_CONFLICT -> JOptionPane.showMessageDialog(null, "Такое название уже есть")
                        DesktopSavedFilterResult.NOT_FOUND -> Unit
                    }
                }
            },
        ) { Text("Сохранить") }
        TextButton(
            onClick = {
                chooseDesktopSavedFilterDialog(savedFilterService.all())?.let { saved ->
                    when (JOptionPane.showOptionDialog(
                        null,
                        saved.name,
                        "Сохранённый фильтр",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.PLAIN_MESSAGE,
                        null,
                        arrayOf("Применить", "Изменить", "Удалить", "Отмена"),
                        "Применить",
                    )) {
                        0 -> onFilterChanged(saved.filter)
                        1 -> editDesktopRecordFilterDialog(
                            saved.filter,
                            allActivities,
                            tagCategoryService.tags(),
                            tagCategoryService.categories(),
                        )?.let { edited ->
                            askDesktopSavedFilterName(saved.name)?.let { name ->
                                if (savedFilterService.save(saved.id, name, edited).first == DesktopSavedFilterResult.SAVED) {
                                    onFilterChanged(edited)
                                    onDataChanged()
                                }
                            }
                        }
                        2 -> if (savedFilterService.delete(saved.id) == DesktopSavedFilterResult.SAVED) onDataChanged()
                    }
                }
            },
        ) { Text("Сохранённые") }
    }
}

@Composable
private fun HistoryV2(
    database: DesktopDatabase,
    recordService: DesktopRecordService,
    tagCategoryService: DesktopTagCategoryService,
    savedFilterService: DesktopSavedFilterService,
    activities: List<ActivityRow>,
    records: List<DesktopTimelineRecord>,
    range: DesktopTimeRange,
    filter: DesktopRecordFilter,
    date: LocalDate,
    today: LocalDate,
    onDateChanged: (LocalDate) -> Unit,
    onFilterChanged: (DesktopRecordFilter) -> Unit,
    onChanged: () -> Unit,
) {
    val total = records.sumOf { range.clippedDuration(it.startedAt, it.endedAt) }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {

                DayNavigation(
            date = date,
            today = today,
            onChange = onDateChanged,
        )

        Spacer(Modifier.height(14.dp))

        TimeFilterControls(
            database = database,
            tagCategoryService = tagCategoryService,
            savedFilterService = savedFilterService,
            filter = filter,
            onFilterChanged = onFilterChanged,
            onDataChanged = onChanged,
        )

        Spacer(Modifier.height(8.dp))

        ManualRecordButton(
            database = database,
            recordService = recordService,
            activities = activities,
            date = date,
            onChanged = onChanged,
        )

        Spacer(Modifier.height(10.dp))

        Text(
            "Всего: ${durationText(total)}",
            style =
                MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(10.dp))

        if (records.isEmpty()) {
            Text("За этот день записей нет")
        } else {
            LazyColumn(
                modifier =
                    Modifier.fillMaxSize(),
                verticalArrangement =
                    Arrangement.spacedBy(5.dp),
            ) {
                items(
                    items = records,
                    key = {
                        it.id
                    },
                ) { record ->
                    Card(
                        modifier =
                            Modifier.fillMaxWidth(),
                        elevation = 1.dp,
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                            verticalAlignment =
                                Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier =
                                    Modifier.weight(1f),
                            ) {
                                Text(
                                    record.activityName,
                                    fontWeight =
                                        FontWeight.Bold,
                                )

                                Text(
                                    if (record.isRunning) {
                                        "${dateTimeText(record.startedAt)} — запущено"
                                    } else {
                                        "${dateTimeText(record.startedAt)} — ${dateTimeText(record.endedAt)}"
                                    },
                                    style =
                                        MaterialTheme.typography.caption,
                                )

                                if (
                                    record.comment
                                        .isNotBlank()
                                ) {
                                    Text(
                                        record.comment,
                                    )
                                }

                                if (record.tags.isNotEmpty()) {
                                    Text(
                                        record.tags.joinToString(" · ") { tag ->
                                            buildString {
                                                append(tag.name)
                                                tag.numericValue?.let { value ->
                                                    append(" (")
                                                    append(formatDesktopTagValue(value))
                                                    if (tag.valueSuffix.isNotBlank()) {
                                                        append(' ')
                                                        append(tag.valueSuffix)
                                                    }
                                                    append(')')
                                                }
                                            }
                                        },
                                        style = MaterialTheme.typography.caption,
                                    )
                                }
                            }

                            Text(
                                durationText(
                                    range.clippedDuration(record.startedAt, record.endedAt),
                                ),
                                fontWeight =
                                    FontWeight.Bold,
                            )

                            Spacer(
                                Modifier.width(6.dp),
                            )

                            if (!record.isRunning) TextButton(
                                onClick = {
                                    val edited =
                                        editRecordDialog(
                                            record,
                                            activities,
                                            database,
                                        )

                                    if (
                                        edited != null
                                    ) {
                                        when (recordService.update(
                                            record.id,
                                            DesktopRecordDraft(
                                                activityId = edited.activityId,
                                                startedAt = edited.startedAt,
                                                endedAt = edited.endedAt,
                                                comment = edited.comment,
                                                tags = edited.tags,
                                            ),
                                        )) {
                                            RecordWriteResult.SAVED -> onChanged()
                                            RecordWriteResult.ACTIVITY_UNAVAILABLE ->
                                                JOptionPane.showMessageDialog(null, "Активность недоступна")
                                            RecordWriteResult.TAG_UNAVAILABLE ->
                                                JOptionPane.showMessageDialog(null, "Тег недоступен")
                                            RecordWriteResult.INVALID_TAG_VALUE ->
                                                JOptionPane.showMessageDialog(null, "Некорректное значение тега")
                                            RecordWriteResult.RECORD_MISSING ->
                                                JOptionPane.showMessageDialog(null, "Запись не найдена")
                                        }
                                    }
                                },
                            ) {
                                Text("Изм.")
                            }

                            if (!record.isRunning) TextButton(
                                onClick = {
                                    val answer =
                                        JOptionPane
                                            .showConfirmDialog(
                                                null,
                                                "Удалить запись ${record.activityName}?",
                                                "Удаление",
                                                JOptionPane.YES_NO_OPTION,
                                            )

                                    if (
                                        answer ==
                                        JOptionPane.YES_OPTION
                                    ) {
                                        when (recordService.delete(record.id)) {
                                            RecordWriteResult.SAVED -> onChanged()
                                            RecordWriteResult.ACTIVITY_UNAVAILABLE -> Unit
                                            RecordWriteResult.TAG_UNAVAILABLE,
                                            RecordWriteResult.INVALID_TAG_VALUE,
                                            -> Unit
                                            RecordWriteResult.RECORD_MISSING ->
                                                JOptionPane.showMessageDialog(null, "Запись не найдена")
                                        }
                                    }
                                },
                            ) {
                                Text("Удалить")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatisticsV2(
    database: DesktopDatabase,
    tagCategoryService: DesktopTagCategoryService,
    savedFilterService: DesktopSavedFilterService,
    records: List<DesktopTimelineRecord>,
    range: DesktopTimeRange,
    rangeLength: DesktopRangeLength,
    onRangeLengthChanged: (DesktopRangeLength) -> Unit,
    filter: DesktopRecordFilter,
    date: LocalDate,
    today: LocalDate,
    onDateChanged: (LocalDate) -> Unit,
    onFilterChanged: (DesktopRecordFilter) -> Unit,
    onDataChanged: () -> Unit,
) {
    val totals =
        linkedMapOf<Long, Pair<String, Long>>()

    records.forEach { record ->
        val old =
            totals[record.activityId]
                ?.second ?: 0L

        totals[record.activityId] =
            record.activityName to (
                old +
                    range.clippedDuration(record.startedAt, record.endedAt)
                )
    }

    val rows =
        totals.entries
            .map {
                Triple(
                    it.key,
                    it.value.first,
                    it.value.second,
                )
            }
            .sortedByDescending {
                it.third
            }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        DayNavigation(
            date = date,
            today = today,
            onChange = onDateChanged,
        )

        Spacer(Modifier.height(14.dp))

        Row {
            DesktopRangeLength.entries.forEach { length ->
                TextButton(onClick = { onRangeLengthChanged(length) }) {
                    val title = when (length) {
                        DesktopRangeLength.DAY -> "День"
                        DesktopRangeLength.WEEK -> "Неделя"
                        DesktopRangeLength.MONTH -> "Месяц"
                    }
                    Text(if (length == rangeLength) "[$title]" else title)
                }
            }
        }

        TimeFilterControls(
            database = database,
            tagCategoryService = tagCategoryService,
            savedFilterService = savedFilterService,
            filter = filter,
            onFilterChanged = onFilterChanged,
            onDataChanged = onDataChanged,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Статистика",
            style =
                MaterialTheme.typography.h5,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(12.dp))

        if (rows.isEmpty()) {
            Text(
                "За этот день ничего не отслеживалось",
            )
        } else {
            Text(
                "Всего: ${durationText(rows.sumOf { it.third })}",
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier =
                    Modifier.fillMaxSize(),
            ) {
                items(
                    items = rows,
                    key = {
                        it.first
                    },
                ) { row ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    vertical = 10.dp,
                                ),
                    ) {
                        Text(
                            row.second,
                            modifier =
                                Modifier.weight(1f),
                        )

                        Text(
                            durationText(
                                row.third,
                            ),
                            fontWeight =
                                FontWeight.Bold,
                        )
                    }

                    Divider()
                }
            }
        }
    }
}

@Composable
private fun ArchiveV2(
    database: DesktopDatabase,
    activities: List<ActivityRow>,
    onChanged: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Text(
            "Архив активностей",
            style =
                MaterialTheme.typography.h5,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            "История архивированных активностей сохраняется.",
        )

        Spacer(Modifier.height(14.dp))

        if (activities.isEmpty()) {
            Text("Архив пуст")
        } else {
            LazyColumn(
                modifier =
                    Modifier.fillMaxSize(),
            ) {
                items(
                    items = activities,
                    key = {
                        it.id
                    },
                ) { activity ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    vertical = 8.dp,
                                ),
                        verticalAlignment =
                            Alignment.CenterVertically,
                    ) {
                        Text(
                            activity.name,
                            modifier =
                                Modifier.weight(1f),
                            fontWeight =
                                FontWeight.Bold,
                        )

                        Button(
                            onClick = {
                                database
                                    .restoreActivity(
                                        activity.id,
                                    )
                                onChanged()
                            },
                        ) {
                            Text("Вернуть")
                        }
                    }

                    Divider()
                }
            }
        }
    }
}

@Composable
fun DesktopAppV2(
    database: DesktopDatabase,
    recordService: DesktopRecordService,
    activityEditorService: DesktopActivityEditorService,
    tagCategoryService: DesktopTagCategoryService,
    semanticPreferences: DesktopSemanticPreferences,
    timeService: DesktopTimeService,
    recordsRangeService: DesktopRecordsRangeService,
    savedFilterService: DesktopSavedFilterService,
    quickActions: DesktopQuickActions,
    revision: Int,
    onDataChanged: () -> Unit,
) {
    var selectedTab by remember {
        mutableIntStateOf(0)
    }

    var now by remember {
        mutableLongStateOf(
            System.currentTimeMillis(),
        )
    }

    var selectedDate by remember {
        mutableStateOf(
            timeService.userDate(),
        )
    }

    var activeFilter by remember { mutableStateOf(DesktopRecordFilter.EMPTY) }
    var statisticsRangeLength by remember { mutableStateOf(DesktopRangeLength.DAY) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    val today = timeService.userDate(now)

    val activities =
        remember(revision) {
            database.activities()
        }

    val archived =
        remember(revision) {
            database.archivedActivities()
        }

    val todayRange = remember(revision, today) { timeService.day(today) }
    val selectedHistoryRange = remember(revision, selectedDate) { timeService.day(selectedDate) }
    val selectedStatisticsRange = remember(revision, selectedDate, statisticsRangeLength) {
        timeService.range(statisticsRangeLength, selectedDate)
    }

    val todayRecords =
        remember(
            revision,
            today,
            now,
        ) {
            recordsRangeService.get(todayRange)
        }

    val selectedHistoryRecords =
        remember(
            revision,
            selectedDate,
            activeFilter,
            now,
        ) {
            recordsRangeService.get(selectedHistoryRange, activeFilter)
        }

    val selectedStatisticsRecords =
        remember(
            revision,
            selectedDate,
            statisticsRangeLength,
            activeFilter,
            now,
        ) {
            recordsRangeService.get(selectedStatisticsRange, activeFilter)
        }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier =
                    Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 20.dp,
                                top = 14.dp,
                                end = 20.dp,
                            ),
                ) {
                    Text(
                        "Simple Time Tracker",
                        style =
                            MaterialTheme.typography.h5,
                        fontWeight =
                            FontWeight.Bold,
                    )

                    Text(
                        "Linux · ${database.path}",
                        style =
                            MaterialTheme.typography.caption,
                    )
                }

                Spacer(Modifier.height(10.dp))

                TabRow(
                    selectedTabIndex =
                        selectedTab,
                ) {
                    DesktopTab.entries
                        .forEachIndexed {
                            index,
                            tab ->

                            Tab(
                                selected =
                                    index ==
                                        selectedTab,
                                onClick = {
                                    selectedTab =
                                        index
                                },
                                text = {
                                    Text(
                                        tab.title,
                                    )
                                },
                            )
                        }
                }

                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                ) {
                    when (
                        DesktopTab.entries[
                            selectedTab
                        ]
                    ) {
                        DesktopTab.TRACKER ->
                            TrackerV2(
                                database =
                                    database,
                                activityEditorService = activityEditorService,
                                tagCategoryService = tagCategoryService,
                                semanticPreferences = semanticPreferences,
                                quickActions =
                                    quickActions,
                                activities =
                                    activities,
                                todayRecords =
                                    todayRecords,
                                todayRange = todayRange,
                                today =
                                    today,
                                now =
                                    now,
                                onChanged = {
                                    onDataChanged()
                                },
                            )


                                DesktopTab.HISTORY ->
                            HistoryV2(
                                database = database,
                                recordService = recordService,
                                tagCategoryService = tagCategoryService,
                                savedFilterService = savedFilterService,
                                activities =
                                    activities,
                                records =
                                    selectedHistoryRecords,
                                range = selectedHistoryRange,
                                filter = activeFilter,
                                date =
                                    selectedDate,
                                today =
                                    today,
                                onDateChanged = {
                                    selectedDate =
                                        it
                                },
                                onFilterChanged = { activeFilter = it },
                                onChanged = {
                                    onDataChanged()
                                },
                            )

                        DesktopTab.STATISTICS ->
                            StatisticsV2(
                                database = database,
                                tagCategoryService = tagCategoryService,
                                savedFilterService = savedFilterService,
                                records =
                                    selectedStatisticsRecords,
                                range = selectedStatisticsRange,
                                rangeLength = statisticsRangeLength,
                                onRangeLengthChanged = { statisticsRangeLength = it },
                                filter = activeFilter,
                                date =
                                    selectedDate,
                                today =
                                    today,
                                onDateChanged = {
                                    selectedDate =
                                        it
                                },
                                onFilterChanged = { activeFilter = it },
                                onDataChanged = { onDataChanged() },
                            )

                        DesktopTab.ARCHIVE ->
                            ArchiveV2(
                                database =
                                    database,
                                activities =
                                    archived,
                                onChanged = {
                                    onDataChanged()
                                },
                            )
                    }
                }
            }
        }
    }
}
