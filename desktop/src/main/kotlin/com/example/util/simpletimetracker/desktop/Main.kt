package com.example.util.simpletimetracker.desktop

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class MainTab(val title: String) {
    TRACKER("Трекер"),
    HISTORY("История"),
    STATISTICS("Статистика"),
}

private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, rest)
}

private fun formatClock(milliseconds: Long): String {
    return DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(milliseconds))
}

@Composable
private fun Summary(
    activities: List<ActivityRow>,
    history: List<HistoryRow>,
    now: Long,
) {
    val finished = history.sumOf { it.endedAt - it.startedAt }
    val running = activities.sumOf { activity ->
        activity.startedAt?.let { now - it } ?: 0L
    }
    val runningCount = activities.count { it.startedAt != null }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column {
            Text("Сегодня", style = MaterialTheme.typography.caption)
            Text(
                formatDuration(finished + running),
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold,
            )
        }

        Column {
            Text("Запущено", style = MaterialTheme.typography.caption)
            Text(
                runningCount.toString(),
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold,
            )
        }

        Column {
            Text("Записей", style = MaterialTheme.typography.caption)
            Text(
                history.size.toString(),
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TrackerScreen(
    database: DesktopDatabase,
    activities: List<ActivityRow>,
    history: List<HistoryRow>,
    now: Long,
    onChanged: () -> Unit,
) {
    var activityName by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Summary(
            activities = activities,
            history = history,
            now = now,
        )

        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = activityName,
                onValueChange = { activityName = it },
                modifier = Modifier.weight(1f),
                label = { Text("Новая активность") },
                singleLine = true,
            )

            Spacer(Modifier.width(10.dp))

            Button(
                onClick = {
                    database.addActivity(activityName)
                    activityName = ""
                    onChanged()
                },
                enabled = activityName.isNotBlank(),
            ) {
                Text("Добавить")
            }
        }

        Spacer(Modifier.height(18.dp))

        if (activities.isEmpty()) {
            Text("Активностей пока нет")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 220.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                gridItems(
                    items = activities.sortedWith(
                        compareByDescending<ActivityRow> { it.startedAt != null }
                            .thenBy { it.name.lowercase() },
                    ),
                    key = { it.id },
                ) { activity ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                database.toggle(activity.id)
                                onChanged()
                            },
                        elevation = if (activity.startedAt != null) 8.dp else 2.dp,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                activity.name,
                                style = MaterialTheme.typography.h6,
                                fontWeight = FontWeight.Medium,
                            )

                            Spacer(Modifier.height(10.dp))

                            if (activity.startedAt != null) {
                                Text(
                                    formatDuration(now - activity.startedAt),
                                    style = MaterialTheme.typography.h5,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Запущено ${formatClock(activity.startedAt)}",
                                    style = MaterialTheme.typography.caption,
                                )
                            } else {
                                Text(
                                    "Нажмите, чтобы запустить",
                                    style = MaterialTheme.typography.body2,
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            Text(
                                if (activity.startedAt == null) "▶ Старт" else "■ Стоп",
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(history: List<HistoryRow>) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Сегодня",
            style = MaterialTheme.typography.h5,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            "Всего: ${formatDuration(history.sumOf { it.endedAt - it.startedAt })}",
            style = MaterialTheme.typography.body1,
        )

        Spacer(Modifier.height(16.dp))

        if (history.isEmpty()) {
            Text("Завершённых записей пока нет")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(history, key = { it.id }) { record ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = 1.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    record.activityName,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "${formatClock(record.startedAt)} – ${formatClock(record.endedAt)}",
                                    style = MaterialTheme.typography.caption,
                                )
                            }

                            Text(
                                formatDuration(record.endedAt - record.startedAt),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatisticsScreen(
    activities: List<ActivityRow>,
    history: List<HistoryRow>,
    now: Long,
) {
    val totals = linkedMapOf<String, Long>()

    history.forEach { record ->
        totals[record.activityName] =
            totals.getOrDefault(record.activityName, 0L) +
                (record.endedAt - record.startedAt)
    }

    activities.forEach { activity ->
        val startedAt = activity.startedAt ?: return@forEach
        totals[activity.name] =
            totals.getOrDefault(activity.name, 0L) +
                (now - startedAt)
    }

    val rows = totals.entries.sortedByDescending { it.value }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Статистика за сегодня",
            style = MaterialTheme.typography.h5,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(16.dp))

        if (rows.isEmpty()) {
            Text("Сегодня пока ничего не отслеживалось")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            row.key,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            formatDuration(row.value),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Divider()
                }
            }
        }
    }
}

@Composable
fun DesktopApp(database: DesktopDatabase) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    val activities = remember(revision) { database.activities() }
    val history = remember(revision) { database.historyToday() }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, top = 16.dp, end = 20.dp),
                ) {
                    Text(
                        "Simple Time Tracker",
                        style = MaterialTheme.typography.h5,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Linux · ${database.path}",
                        style = MaterialTheme.typography.caption,
                    )
                }

                Spacer(Modifier.height(12.dp))

                TabRow(selectedTabIndex = selectedTab) {
                    MainTab.entries.forEachIndexed { index, tab ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(tab.title) },
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                ) {
                    when (MainTab.entries[selectedTab]) {
                        MainTab.TRACKER -> TrackerScreen(
                            database = database,
                            activities = activities,
                            history = history,
                            now = now,
                            onChanged = { revision++ },
                        )

                        MainTab.HISTORY -> HistoryScreen(history)

                        MainTab.STATISTICS -> StatisticsScreen(
                            activities = activities,
                            history = history,
                            now = now,
                        )
                    }
                }
            }
        }
    }
}

fun main() = application {
    val database = remember { DesktopDatabase() }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Simple Time Tracker",
        state = WindowState(
            width = 960.dp,
            height = 720.dp,
        ),
    ) {
        DesktopAppV2(database)
    }
}
