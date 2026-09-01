@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.util.simpletimetracker.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.io.File
import java.nio.file.Path

/** Desktop-facing workflow deliberately separates Analyze from destructive-looking actions. */
@Composable
internal fun ModernBackupDataPage(
    database: DesktopDatabase,
    preferences: DesktopSemanticPreferences,
) {
    val service = remember(database, preferences) { DesktopBackupService(database, preferences, DesktopPomodoroConfigStore()) }
    val importer = remember { AndroidBackupImporter() }
    var status by remember { mutableStateOf("Выберите действие. Текущая рабочая БД не заменяется автоматически.") }
    Column(Modifier.fillMaxSize().padding(DesktopUiTokens.ScreenPadding)) {
        DesktopPageHeader("Данные", "Логические backup, безопасное восстановление, Android import и CSV export")
        Spacer(Modifier.height(DesktopUiTokens.SectionGap))
        DataCard("Desktop backup", "Versioned ZIP с checksum и logical rows. Включает product data; runtime journal и window state не переносятся.") {
            OutlinedButton(onClick = {
                chooseSave("Создать desktop backup", "simple-time-tracker-backup.sttb")?.let { target ->
                    status = service.createBackup(target).readable()
                }
            }) { Text("Создать backup") }
            OutlinedButton(onClick = {
                chooseOpen("Проверить desktop backup")?.let { source -> status = service.analyzeNative(source).readable() }
            }) { Text("Проверить backup") }
            OutlinedButton(onClick = {
                chooseOpen("Выбрать desktop backup")?.let { source ->
                    chooseSave("Восстановить в новую БД", "restored-tracker.sqlite3")?.let { target ->
                        status = service.restoreToNewDatabase(source, target).readable()
                    }
                }
            }) { Text("Восстановить в новую БД") }
        }
        Spacer(Modifier.height(14.dp))
        DataCard("Android Simple Time Tracker", "Официальный text backup сначала анализируется. Импорт сохраняет source IDs и создаёт только новую candidate SQLite DB.") {
            OutlinedButton(onClick = {
                chooseOpen("Анализ Android backup")?.let { source -> status = importer.analyze(source).report.readable() }
            }) { Text("Analyze / Dry run") }
            Button(onClick = {
                chooseOpen("Выбрать Android backup")?.let { source ->
                    val plan = importer.analyze(source)
                    if (!plan.report.successful) status = plan.report.readable()
                    else chooseSave("Импортировать в новую candidate DB", "android-import-candidate.sqlite3")?.let { target -> status = importer.importInto(plan, target).readable() }
                }
            }) { Text("Импортировать в новую БД") }
        }
        Spacer(Modifier.height(14.dp))
        DataCard("Экспорт", "CSV предназначен для чтения в таблицах, а не для lossless restore.") {
            OutlinedButton(onClick = {
                chooseSave("Экспорт CSV", "simple-time-tracker-records.csv")?.let { target -> status = service.exportCsv(target).readable() }
            }) { Text("Экспорт CSV") }
        }
        Spacer(Modifier.height(20.dp))
        Card(modifier = Modifier.fillMaxWidth(), elevation = 0.dp, backgroundColor = DesktopUiTokens.Tag, shape = MaterialTheme.shapes.medium) {
            Text(status, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.body2, color = DesktopUiTokens.Text)
        }
    }
}

@Composable
private fun DataCard(title: String, detail: String, actions: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = 0.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.h6)
            Spacer(Modifier.height(5.dp))
            Text(detail, style = MaterialTheme.typography.body2, color = DesktopUiTokens.SecondaryText)
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
        }
    }
}

private fun chooseOpen(title: String): Path? = chooseFile(title, FileDialog.LOAD, null)
private fun chooseSave(title: String, name: String): Path? = chooseFile(title, FileDialog.SAVE, name)
private fun chooseFile(title: String, mode: Int, file: String?): Path? = runCatching {
    FileDialog(null as java.awt.Frame?, title, mode).apply { this.file = file; isVisible = true }
        .let { dialog -> dialog.file?.let { selected -> File(dialog.directory, selected).toPath() } }
}.getOrNull()

private fun DesktopBackupResult.readable(): String = when (this) {
    is DesktopBackupResult.Success -> "Готово: ${summary.entityCounts.entries.joinToString { "${it.key}: ${it.value}" }}"
    is DesktopBackupResult.Failure -> "Ошибка: $message"
}
private fun AndroidImportReport.readable(): String = buildString {
    append(if (successful) "Анализ готов. " else "Ошибка анализа. ")
    append(counts.entries.joinToString { "${it.key}: ${it.value}" })
    if (warnings.isNotEmpty()) append(". Warnings: ${warnings.size}")
    if (fatalErrors.isNotEmpty()) append(". ${fatalErrors.joinToString()}")
}
