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
import java.awt.GridLayout
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JLabel
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

private fun uiDayBounds(
    date: LocalDate,
): Pair<Long, Long> {
    val zone = ZoneId.systemDefault()

    val start =
        date.atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    val end =
        date.plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    return start to end
}

private fun overlap(
    startedAt: Long,
    endedAt: Long,
    date: LocalDate,
): Long {
    val bounds = uiDayBounds(date)

    val start =
        maxOf(startedAt, bounds.first)

    val end =
        minOf(endedAt, bounds.second)

    return (end - start)
        .coerceAtLeast(0)
}

private data class RecordEditResult(
    val startedAt: Long,
    val endedAt: Long,
    val comment: String,
)

private fun editRecordDialog(
    record: DayRecordRow,
): RecordEditResult? {
    val startedField =
        JTextField(dateTimeText(record.startedAt))

    val endedField =
        JTextField(dateTimeText(record.endedAt))

    val commentField =
        JTextField(record.comment)

    val panel =
        JPanel(GridLayout(0, 1, 4, 4))

    panel.add(JLabel("Начало"))
    panel.add(startedField)

    panel.add(JLabel("Конец"))
    panel.add(endedField)

    panel.add(JLabel("Комментарий"))
    panel.add(commentField)

    val result =
        JOptionPane.showConfirmDialog(
            null,
            panel,
            "Редактировать запись",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )

    if (result != JOptionPane.OK_OPTION) {
        return null
    }

    return try {
        val startedAt =
            parseDateTime(startedField.text)

        val endedAt =
            parseDateTime(endedField.text)

        if (endedAt <= startedAt) {
            JOptionPane.showMessageDialog(
                null,
                "Конец должен быть позже начала",
            )
            null
        } else {
            RecordEditResult(
                startedAt = startedAt,
                endedAt = endedAt,
                comment = commentField.text,
            )
        }
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
    activities: List<ActivityRow>,
    todayRecords: List<DayRecordRow>,
    today: LocalDate,
    now: Long,
    onChanged: () -> Unit,
) {
    var newActivity by remember {
        mutableStateOf("")
    }

    val bounds = uiDayBounds(today)

    val finishedByActivity =
        todayRecords
            .groupBy { it.activityId }
            .mapValues { entry ->
                entry.value.sumOf {
                    overlap(
                        it.startedAt,
                        it.endedAt,
                        today,
                    )
                }
            }

    val runningTotal =
        activities.sumOf { activity ->
            activity.startedAt?.let {
                now - maxOf(
                    it,
                    bounds.first,
                )
            } ?: 0L
        }

    val finishedTotal =
        todayRecords.sumOf {
            overlap(
                it.startedAt,
                it.endedAt,
                today,
            )
        }

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

        Spacer(Modifier.height(18.dp))

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
                        now - maxOf(
                            it,
                            bounds.first,
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
                                    database.toggle(
                                        activity.id,
                                    )
                                    onChanged()
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
                                    val name =
                                        JOptionPane
                                            .showInputDialog(
                                                null,
                                                "Новое название",
                                                activity.name,
                                            )

                                    if (
                                        !name.isNullOrBlank()
                                    ) {
                                        database
                                            .renameActivity(
                                                activity.id,
                                                name,
                                            )
                                        onChanged()
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
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryV2(
    database: DesktopDatabase,
    activities: List<ActivityRow>,
    records: List<DayRecordRow>,
    date: LocalDate,
    today: LocalDate,
    onDateChanged: (LocalDate) -> Unit,
    onChanged: () -> Unit,
) {
    val total =
        records.sumOf {
            overlap(
                it.startedAt,
                it.endedAt,
                date,
            )
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

        ManualRecordButton(
            database = database,
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
                                    "${dateTimeText(record.startedAt)} — ${dateTimeText(record.endedAt)}",
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
                            }

                            Text(
                                durationText(
                                    overlap(
                                        record.startedAt,
                                        record.endedAt,
                                        date,
                                    ),
                                ),
                                fontWeight =
                                    FontWeight.Bold,
                            )

                            Spacer(
                                Modifier.width(6.dp),
                            )

                            TextButton(
                                onClick = {
                                    val edited =
                                        editRecordDialog(
                                            record,
                                        )

                                    if (
                                        edited != null
                                    ) {
                                        database
                                            .updateRecord(
                                                recordId =
                                                    record.id,
                                                startedAt =
                                                    edited.startedAt,
                                                endedAt =
                                                    edited.endedAt,
                                                comment =
                                                    edited.comment,
                                            )
                                        onChanged()
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
                                                "Удалить запись ${record.activityName}?",
                                                "Удаление",
                                                JOptionPane.YES_NO_OPTION,
                                            )

                                    if (
                                        answer ==
                                        JOptionPane.YES_OPTION
                                    ) {
                                        database
                                            .deleteRecord(
                                                record.id,
                                            )
                                        onChanged()
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
    activities: List<ActivityRow>,
    records: List<DayRecordRow>,
    date: LocalDate,
    today: LocalDate,
    now: Long,
    onDateChanged: (LocalDate) -> Unit,
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
                    overlap(
                        record.startedAt,
                        record.endedAt,
                        date,
                    )
                )
    }

    if (date == today) {
        val bounds =
            uiDayBounds(today)

        activities.forEach { activity ->
            val startedAt =
                activity.startedAt
                    ?: return@forEach

            val old =
                totals[activity.id]
                    ?.second ?: 0L

            totals[activity.id] =
                activity.name to (
                    old +
                        (
                            now -
                                maxOf(
                                    startedAt,
                                    bounds.first,
                                )
                            ).coerceAtLeast(0)
                    )
        }
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
) {
    var selectedTab by remember {
        mutableIntStateOf(0)
    }

    var revision by remember {
        mutableIntStateOf(0)
    }

    var now by remember {
        mutableLongStateOf(
            System.currentTimeMillis(),
        )
    }

    var selectedDate by remember {
        mutableStateOf(
            LocalDate.now(),
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    val today = LocalDate.now()

    val activities =
        remember(revision) {
            database.activities()
        }

    val archived =
        remember(revision) {
            database.archivedActivities()
        }

    val todayRecords =
        remember(
            revision,
            today,
        ) {
            database.historyForDate(
                today,
            )
        }

    val selectedRecords =
        remember(
            revision,
            selectedDate,
        ) {
            database.historyForDate(
                selectedDate,
            )
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
                                activities =
                                    activities,
                                todayRecords =
                                    todayRecords,
                                today =
                                    today,
                                now =
                                    now,
                                onChanged = {
                                    revision++
                                },
                            )


                                DesktopTab.HISTORY ->
                            HistoryV2(
                                database =
                                    database,
                                activities =
                                    activities,
                                records =
                                    selectedRecords,
                                date =
                                    selectedDate,
                                today =
                                    today,
                                onDateChanged = {
                                    selectedDate =
                                        it
                                },
                                onChanged = {
                                    revision++
                                },
                            )

                        DesktopTab.STATISTICS ->
                            StatisticsV2(
                                activities =
                                    activities,
                                records =
                                    selectedRecords,
                                date =
                                    selectedDate,
                                today =
                                    today,
                                now =
                                    now,
                                onDateChanged = {
                                    selectedDate =
                                        it
                                },
                            )

                        DesktopTab.ARCHIVE ->
                            ArchiveV2(
                                database =
                                    database,
                                activities =
                                    archived,
                                onChanged = {
                                    revision++
                                },
                            )
                    }
                }
            }
        }
    }
}

