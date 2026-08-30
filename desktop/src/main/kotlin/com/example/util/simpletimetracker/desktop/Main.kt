package com.example.util.simpletimetracker.desktop

import androidx.compose.desktop.ui.tooling.preview.Preview
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
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun formatDuration(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1000)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, rest)
}

private fun formatClock(milliseconds: Long): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(milliseconds))
}

@Composable
@Preview
fun DesktopApp(database: DesktopDatabase) {
    var activityName by remember { mutableStateOf("") }
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
            ) {
                Text(
                    text = "Simple Time Tracker — Linux",
                    style = MaterialTheme.typography.h5,
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = "DB: ${database.path}",
                    style = MaterialTheme.typography.caption,
                )

                Spacer(Modifier.height(16.dp))

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
                            revision++
                        },
                        enabled = activityName.isNotBlank(),
                    ) {
                        Text("Добавить")
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text("Активности", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(activities, key = { it.id }) { activity ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(activity.name)
                                if (activity.startedAt != null) {
                                    Text(
                                        text = formatDuration(now - activity.startedAt),
                                        style = MaterialTheme.typography.caption,
                                    )
                                } else {
                                    Text(
                                        text = "Не запущено",
                                        style = MaterialTheme.typography.caption,
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    database.toggle(activity.id)
                                    revision++
                                },
                            ) {
                                Text(if (activity.startedAt == null) "Старт" else "Стоп")
                            }
                        }
                    }
                }

                Divider()
                Spacer(Modifier.height(12.dp))

                Text("Сегодня", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(6.dp))

                if (history.isEmpty()) {
                    Text("Завершённых записей пока нет")
                } else {
                    history.take(10).forEach { record ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = record.activityName,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${formatClock(record.startedAt)}–${formatClock(record.endedAt)}",
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(formatDuration(record.endedAt - record.startedAt))
                        }
                        Spacer(Modifier.height(4.dp))
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
    ) {
        DesktopApp(database)
    }
}
