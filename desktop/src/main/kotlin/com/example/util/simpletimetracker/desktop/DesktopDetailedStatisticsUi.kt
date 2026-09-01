package com.example.util.simpletimetracker.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Lightweight Compose doughnut chart; bars remain in the detailed list below. */
@androidx.compose.runtime.Composable
internal fun ModernStatisticsPieChart(items: List<DesktopStatisticsBreakdown>) {
    val total = items.sumOf(DesktopStatisticsBreakdown::durationMillis)
    if (total <= 0L) return
    Canvas(Modifier.size(156.dp)) {
        var start = -90f
        items.forEach { item ->
            val sweep = item.durationMillis.toFloat() / total * 360f
            drawArc(
                color = parseDetailedColor(item.color, DesktopUiTokens.Primary),
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(12.dp.toPx(), 12.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(size.width - 24.dp.toPx(), size.height - 24.dp.toPx()),
                style = Stroke(width = 26.dp.toPx(), cap = StrokeCap.Butt),
            )
            start += sweep
        }
    }
}

@androidx.compose.runtime.Composable
internal fun ModernStatisticsDrillDownDialog(
    title: String,
    records: List<DesktopTimelineRecord>,
    range: DesktopTimeRange,
    onDismiss: () -> Unit,
) {
    DesktopDialogSurface("Записи: $title", onDismiss, wide = true) {
        if (records.isEmpty()) Text("Нет записей.", color = DesktopUiTokens.SecondaryText)
        else LazyColumn(modifier = Modifier.widthIn(min = 520.dp).height(360.dp)) {
            items(records, key = DesktopTimelineRecord::id) { record ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(record.activityName, style = MaterialTheme.typography.subtitle1)
                    Text("${modernDateTimeText(record.startedAt)} · ${detailedDuration(range.clippedDuration(record.startedAt, record.endedAt))}", style = MaterialTheme.typography.body2, color = DesktopUiTokens.SecondaryText)
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

private fun parseDetailedColor(value: String, fallback: Color): Color = runCatching {
    value.takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }
        ?.removePrefix("#")?.toLong(16)?.let { Color(0xFF000000L or it) } ?: fallback
}.getOrDefault(fallback)

private fun detailedDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    return "%02d:%02d:%02d".format(seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L)
}
