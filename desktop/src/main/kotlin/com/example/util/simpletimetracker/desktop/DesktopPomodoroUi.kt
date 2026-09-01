package com.example.util.simpletimetracker.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun ModernPomodoroPage(service: DesktopPomodoroService) {
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var settingsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(500); tick = System.currentTimeMillis() } }
    val snapshot = service.snapshot(tick)
    val config = service.configuration()
    Column(Modifier.fillMaxSize().padding(DesktopUiTokens.ScreenPadding), horizontalAlignment = Alignment.CenterHorizontally) {
        DesktopPageHeader("Pomodoro", "Цикл продолжает идти, когда окно скрыто в tray.", actions = { OutlinedButton(onClick = { settingsOpen = true }) { Text("Настройки") } })
        Spacer(Modifier.height(DesktopUiTokens.SectionGap))
        Card(Modifier.fillMaxWidth(), elevation = 0.dp, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(42.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(snapshot.phase.readable(), style = MaterialTheme.typography.h5)
                Text(formatPomodoro(snapshot.remainingMillis), style = MaterialTheme.typography.h4, fontWeight = FontWeight.Bold)
                Text(if (snapshot.runState == DesktopPomodoroRunState.PAUSED) "Пауза" else if (snapshot.runState == DesktopPomodoroRunState.RUNNING) "Запущено" else "Готово к старту", color = DesktopUiTokens.SecondaryText)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (snapshot.runState) { DesktopPomodoroRunState.STOPPED -> Button(onClick = service::start) { Text("Старт") }; DesktopPomodoroRunState.RUNNING -> { Button(onClick = service::pause) { Text("Пауза") }; OutlinedButton(onClick = service::stop) { Text("Стоп") } }; DesktopPomodoroRunState.PAUSED -> Button(onClick = service::resume) { Text("Продолжить") } }
                    OutlinedButton(onClick = service::skip, enabled = snapshot.runState == DesktopPomodoroRunState.RUNNING) { Text("Пропустить") }
                    OutlinedButton(onClick = service::reset, enabled = snapshot.runState == DesktopPomodoroRunState.RUNNING) { Text("Сбросить phase") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Фокус ${formatPomodoro(config.focusMillis)} · перерыв ${formatPomodoro(config.breakMillis)} · длинный ${formatPomodoro(config.longBreakMillis)} · через ${config.periodsUntilLongBreak} периода", color = DesktopUiTokens.SecondaryText)
    }
    if (settingsOpen) PomodoroSettingsDialog(config, onDismiss = { settingsOpen = false }, onSave = { service.updateConfiguration(it); settingsOpen = false })
}

@Composable private fun PomodoroSettingsDialog(config: DesktopPomodoroConfig, onDismiss: () -> Unit, onSave: (DesktopPomodoroConfig) -> Unit) {
    var focus by remember { mutableStateOf((config.focusMillis / 60_000).toString()) }; var pause by remember { mutableStateOf((config.breakMillis / 60_000).toString()) }; var longPause by remember { mutableStateOf((config.longBreakMillis / 60_000).toString()) }; var periods by remember { mutableStateOf(config.periodsUntilLongBreak.toString()) }
    DesktopDialogSurface("Настройки Pomodoro", onDismiss) { Text("Минуты; 0 отключает обычный/длинный перерыв.", color = DesktopUiTokens.SecondaryText); OutlinedTextField(focus, { focus = it }, label = { Text("Фокус") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(pause, { pause = it }, label = { Text("Перерыв") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(longPause, { longPause = it }, label = { Text("Длинный перерыв") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(periods, { periods = it }, label = { Text("Периодов до длинного") }, modifier = Modifier.fillMaxWidth()); val next = DesktopPomodoroConfig((focus.toLongOrNull()?.coerceAtLeast(1) ?: 0) * 60_000, (pause.toLongOrNull()?.coerceAtLeast(0) ?: 0) * 60_000, (longPause.toLongOrNull()?.coerceAtLeast(0) ?: 0) * 60_000, periods.toLongOrNull()?.coerceAtLeast(0) ?: 0); DesktopDialogActions(onDismiss, "Сохранить", { onSave(next) }, next.focusMillis > 0) }
}

private fun formatPomodoro(milliseconds: Long): String { val total = (milliseconds.coerceAtLeast(0) / 1000); return "%02d:%02d".format(total / 60, total % 60) }
