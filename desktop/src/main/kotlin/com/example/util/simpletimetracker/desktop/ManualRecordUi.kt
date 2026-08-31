package com.example.util.simpletimetracker.desktop

import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import java.awt.GridLayout
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField

private data class ManualRecordResult(
    val activityId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val comment: String,
)

private val manualDateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun manualDateTimeText(value: Long): String {
    return LocalDateTime.ofInstant(
        Instant.ofEpochMilli(value),
        ZoneId.systemDefault(),
    ).format(manualDateTimeFormatter)
}

private fun manualDateTimeParse(value: String): Long {
    return LocalDateTime.parse(
        value.trim(),
        manualDateTimeFormatter,
    ).atZone(
        ZoneId.systemDefault(),
    ).toInstant().toEpochMilli()
}

private fun showManualRecordDialog(
    activities: List<ActivityRow>,
    date: LocalDate,
): ManualRecordResult? {
    if (activities.isEmpty()) {
        JOptionPane.showMessageDialog(
            null,
            "Сначала создайте активность",
        )
        return null
    }

    val today = LocalDate.now()

    val defaultStart: LocalDateTime
    val defaultEnd: LocalDateTime

    if (date == today) {
        defaultEnd = LocalDateTime.now()
            .withSecond(0)
            .withNano(0)
        defaultStart = defaultEnd.minusMinutes(30)
    } else {
        defaultStart = date.atTime(12, 0)
        defaultEnd = date.atTime(13, 0)
    }

    val activityNames =
        activities.map { it.name }.toTypedArray()

    val activityBox =
        JComboBox(activityNames)

    val startedField = JTextField(
        defaultStart.format(manualDateTimeFormatter),
    )

    val endedField = JTextField(
        defaultEnd.format(manualDateTimeFormatter),
    )

    val commentField = JTextField("")

    val panel = JPanel(
        GridLayout(0, 1, 4, 4),
    )

    panel.add(JLabel("Активность"))
    panel.add(activityBox)

    panel.add(JLabel("Начало"))
    panel.add(startedField)

    panel.add(JLabel("Конец"))
    panel.add(endedField)

    panel.add(JLabel("Комментарий"))
    panel.add(commentField)

    val result = JOptionPane.showConfirmDialog(
        null,
        panel,
        "Добавить запись",
        JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.PLAIN_MESSAGE,
    )

    if (result != JOptionPane.OK_OPTION) {
        return null
    }

    return try {
        val selectedIndex = activityBox.selectedIndex

        check(selectedIndex in activities.indices)

        val startedAt =
            manualDateTimeParse(startedField.text)

        val endedAt =
            manualDateTimeParse(endedField.text)

        ManualRecordResult(
            activityId = activities[selectedIndex].id,
            startedAt = startedAt,
            endedAt = endedAt,
            comment = commentField.text,
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
fun ManualRecordButton(
    recordService: DesktopRecordService,
    activities: List<ActivityRow>,
    date: LocalDate,
    onChanged: () -> Unit,
) {
    Button(
        onClick = {
            val record = showManualRecordDialog(
                activities = activities,
                date = date,
            )

            if (record != null) {
                try {
                    when (recordService.create(
                        DesktopRecordDraft(
                            activityId = record.activityId,
                            startedAt = record.startedAt,
                            endedAt = record.endedAt,
                            comment = record.comment,
                        ),
                    )) {
                        RecordWriteResult.SAVED -> onChanged()
                        RecordWriteResult.ACTIVITY_UNAVAILABLE ->
                            JOptionPane.showMessageDialog(null, "Активность недоступна")
                        RecordWriteResult.RECORD_MISSING -> Unit
                    }
                } catch (e: Throwable) {
                    JOptionPane.showMessageDialog(
                        null,
                        e.message ?: "Не удалось добавить запись",
                    )
                }
            }
        },
    ) {
        Text("+ Запись")
    }
}
