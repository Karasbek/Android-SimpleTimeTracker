package com.example.util.simpletimetracker.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class DesktopModernTab(val title: String, val mark: String) {
    TRACKER("Трекер", "Т"),
    HISTORY("Записи", "З"),
    STATISTICS("Статистика", "С"),
    GOALS("Цели", "Ц"),
    AUTOMATION("Автоматизация", "А"),
    ARCHIVE("Архив", "А"),
}

private val modernDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal fun modernDateTimeText(value: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneId.systemDefault())
        .format(modernDateTimeFormatter)

internal fun modernDateTimeParse(value: String): Long? =
    runCatching {
        LocalDateTime.parse(value.trim(), modernDateTimeFormatter)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

private fun durationText(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return "%02d:%02d:%02d".format(seconds / 3_600L, (seconds % 3_600L) / 60L, seconds % 60L)
}

private fun clockText(milliseconds: Long): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(milliseconds))

private fun identityColor(value: String, fallback: Color): Color =
    runCatching {
        value.takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }
            ?.removePrefix("#")
            ?.toLong(16)
            ?.let { Color(0xFF000000L or it) }
            ?: fallback
    }.getOrDefault(fallback)

@Composable
fun DesktopModernApp(
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
    var selectedTab by remember { mutableStateOf(DesktopModernTab.TRACKER) }
    var selectedDate by remember { mutableStateOf(timeService.userDate()) }
    var historyCustomRange by remember { mutableStateOf<DesktopTimeRange?>(null) }
    var historyAllRecords by remember { mutableStateOf(false) }
    var calendarOpen by remember { mutableStateOf(false) }
    var customRangeOpen by remember { mutableStateOf(false) }
    var statisticsCustomRange by remember { mutableStateOf<DesktopTimeRange?>(null) }
    var statisticsCustomRangeOpen by remember { mutableStateOf(false) }
    var rangeLength by remember { mutableStateOf(DesktopRangeLength.DAY) }
    var activeFilter by remember { mutableStateOf(DesktopRecordFilter.EMPTY) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var activityEditor by remember { mutableStateOf<ActivityRow?>(null) }
    var createActivity by remember { mutableStateOf(false) }
    var recordEditor by remember { mutableStateOf<DesktopTimelineRecord?>(null) }
    var runningRecordEditor by remember { mutableStateOf<DesktopTimelineRecord?>(null) }
    var splitRecordEditor by remember { mutableStateOf<DesktopTimelineRecord?>(null) }
    var createManualRecord by remember { mutableStateOf(false) }
    var tagManagerOpen by remember { mutableStateOf(false) }
    var categoryManagerOpen by remember { mutableStateOf(false) }
    var filterEditorOpen by remember { mutableStateOf(false) }
    var savedFiltersOpen by remember { mutableStateOf(false) }
    var timeSettingsOpen by remember { mutableStateOf(false) }
    var saveFilterOpen by remember { mutableStateOf(false) }
    var numericTagRequest by remember { mutableStateOf<Pair<Long, Set<Long>>?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    val today = timeService.userDate(now)
    val activities = remember(revision) { database.activities() }
    val archived = remember(revision) { database.archivedActivities() }
    val todayRange = remember(revision, today) { timeService.day(today) }
    val historyRange = remember(revision, selectedDate, historyCustomRange, historyAllRecords) {
        when {
            historyAllRecords -> DesktopTimeRange(0, Long.MAX_VALUE)
            historyCustomRange != null -> historyCustomRange!!
            else -> timeService.day(selectedDate)
        }
    }
    val statisticsRange = remember(revision, selectedDate, rangeLength, statisticsCustomRange) {
        statisticsCustomRange ?: timeService.range(rangeLength, selectedDate)
    }
    val trackerRecords = remember(revision, today, now) { recordsRangeService.get(todayRange) }
    val historyRecords = remember(revision, selectedDate, activeFilter, now, historyCustomRange, historyAllRecords, semanticPreferences.showUntrackedInRecords) {
        recordsRangeService.get(historyRange, activeFilter, semanticPreferences.showUntrackedInRecords || activeFilter.recordKind == DesktopRecordKindFilter.UNTRACKED)
    }
    val statisticsRecords = remember(revision, selectedDate, rangeLength, statisticsCustomRange, activeFilter, now) {
        recordsRangeService.get(statisticsRange, activeFilter, activeFilter.recordKind == DesktopRecordKindFilter.UNTRACKED)
    }

    SimpleTimeTrackerDesktopTheme {
        Row(modifier = Modifier.fillMaxSize().background(DesktopUiTokens.Background)) {
            DesktopSidebar(selectedTab = selectedTab, onSelect = { selectedTab = it })
            // The sidebar is fixed; the content must be measured from the remaining
            // width, not from the Row's whole max width.
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (selectedTab) {
                    DesktopModernTab.TRACKER -> ModernTrackerPage(
                        database = database,
                        activityEditorService = activityEditorService,
                        tagCategoryService = tagCategoryService,
                        quickActions = quickActions,
                        activities = activities,
                        records = trackerRecords,
                        range = todayRange,
                        now = now,
                        onCreateActivity = { createActivity = true },
                        onEditActivity = { activityEditor = it },
                        onManageTags = { tagManagerOpen = true },
                        onManageCategories = { categoryManagerOpen = true },
                        onTimeSettings = { timeSettingsOpen = true },
                        onNumericTagsRequired = { activityId -> numericTagRequest = activityId to quickActions.requestedNumericTagIds(activityId) },
                        onChanged = onDataChanged,
                    )

                    DesktopModernTab.HISTORY -> ModernHistoryPage(
                        database = database,
                        recordService = recordService,
                        tagCategoryService = tagCategoryService,
                        quickActions = quickActions,
                        activities = activities,
                        records = historyRecords,
                        range = historyRange,
                        date = selectedDate,
                        today = today,
                        filter = activeFilter,
                        onDateChange = { selectedDate = it },
                        onOpenCalendar = { calendarOpen = true },
                        onOpenCustomRange = { customRangeOpen = true },
                        allRecords = historyAllRecords,
                        onAllRecordsChange = { enabled -> historyAllRecords = enabled; if (enabled) historyCustomRange = null },
                        onOpenFilters = { filterEditorOpen = true },
                        onOpenSavedFilters = { savedFiltersOpen = true },
                        onSaveFilter = { saveFilterOpen = true },
                        onClearFilter = { activeFilter = DesktopRecordFilter.EMPTY },
                        onFilterChange = { activeFilter = it },
                        onCreateManual = { createManualRecord = true },
                        onEditRecord = { recordEditor = it },
                        onEditRunningRecord = { runningRecordEditor = it },
                        onSplitRecord = { splitRecordEditor = it },
                        onChanged = onDataChanged,
                    )

                    DesktopModernTab.STATISTICS -> ModernStatisticsPage(
                        database = database,
                        tagCategoryService = tagCategoryService,
                        records = statisticsRecords,
                        range = statisticsRange,
                        rangeLength = rangeLength,
                        date = selectedDate,
                        today = today,
                        filter = activeFilter,
                        onDateChange = { selectedDate = it },
                        onRangeLengthChange = { rangeLength = it },
                        onOpenCustomRange = { statisticsCustomRangeOpen = true },
                        customRangeActive = statisticsCustomRange != null,
                        onClearCustomRange = { statisticsCustomRange = null },
                        onOpenFilters = { filterEditorOpen = true },
                        onOpenSavedFilters = { savedFiltersOpen = true },
                        onSaveFilter = { saveFilterOpen = true },
                        onClearFilter = { activeFilter = DesktopRecordFilter.EMPTY },
                    )

                    DesktopModernTab.GOALS -> ModernGoalsPage(
                        database = database,
                        timeService = timeService,
                        recordsRangeService = recordsRangeService,
                        date = selectedDate,
                        onDateChange = { selectedDate = it },
                        revision = revision,
                        onChanged = onDataChanged,
                    )

                    DesktopModernTab.AUTOMATION -> ModernAutomationPage(
                        database = database,
                        timeService = timeService,
                        activities = activities,
                        revision = revision,
                        onChanged = onDataChanged,
                    )

                    DesktopModernTab.ARCHIVE -> ModernArchivePage(
                        database = database,
                        activities = archived,
                        onChanged = onDataChanged,
                    )
                }
            }
        }

        if (createActivity || activityEditor != null) {
            ModernActivityEditorDialog(
                activity = activityEditor,
                database = database,
                activityEditorService = activityEditorService,
                tagCategoryService = tagCategoryService,
                onDismiss = { createActivity = false; activityEditor = null },
                onSaved = { createActivity = false; activityEditor = null; onDataChanged() },
            )
        }
        if (createManualRecord || recordEditor != null) {
            ModernRecordEditorDialog(
                record = recordEditor,
                database = database,
                recordService = recordService,
                activities = activities,
                selectedDate = selectedDate,
                onDismiss = { createManualRecord = false; recordEditor = null },
                onSaved = { createManualRecord = false; recordEditor = null; onDataChanged() },
            )
        }
        runningRecordEditor?.let { record ->
            ModernRunningRecordEditorDialog(
                record = record,
                database = database,
                service = remember { DesktopRunningRecordService(database) },
                activities = activities,
                onDismiss = { runningRecordEditor = null },
                onSaved = { runningRecordEditor = null; onDataChanged() },
            )
        }
        numericTagRequest?.let { (activityId, tagIds) ->
            ModernRuleNumericTagsDialog(
                tags = database.tags().filter { it.id in tagIds },
                onDismiss = { numericTagRequest = null },
                onConfirm = { values ->
                    quickActions.startWithTags(activityId, values)
                    numericTagRequest = null
                    onDataChanged()
                },
            )
        }
        splitRecordEditor?.let { record ->
            ModernSplitRecordDialog(
                record = record,
                activities = activities,
                service = remember { DesktopRecordActionsService(database) },
                onDismiss = { splitRecordEditor = null },
                onSaved = { splitRecordEditor = null; onDataChanged() },
            )
        }
        if (tagManagerOpen) {
            ModernTagsManagerDialog(tagCategoryService, onDismiss = { tagManagerOpen = false }, onChanged = onDataChanged)
        }
        if (categoryManagerOpen) {
            ModernCategoriesManagerDialog(tagCategoryService, onDismiss = { categoryManagerOpen = false }, onChanged = onDataChanged)
        }
        if (filterEditorOpen) {
            ModernFilterEditorDialog(
                initial = activeFilter,
                activities = activities + archived,
                tagCategoryService = tagCategoryService,
                onDismiss = { filterEditorOpen = false },
                onApply = { activeFilter = it; filterEditorOpen = false },
            )
        }
        if (savedFiltersOpen) {
            ModernSavedFiltersDialog(
                service = savedFilterService,
                activities = activities + archived,
                tagCategoryService = tagCategoryService,
                onDismiss = { savedFiltersOpen = false },
                onApply = { activeFilter = it },
                onChanged = onDataChanged,
            )
        }
        if (saveFilterOpen) {
            ModernSaveFilterDialog(
                service = savedFilterService,
                filter = activeFilter,
                onDismiss = { saveFilterOpen = false },
                onSaved = { saveFilterOpen = false; onDataChanged() },
            )
        }
        if (timeSettingsOpen) {
            ModernTimeSettingsDialog(
                preferences = semanticPreferences,
                quickActions = quickActions,
                onDismiss = { timeSettingsOpen = false },
                onSaved = { timeSettingsOpen = false; onDataChanged() },
            )
        }
        if (calendarOpen) {
            ModernCalendarDialog(
                selected = selectedDate,
                onDismiss = { calendarOpen = false },
                onSelected = { selectedDate = it; historyAllRecords = false; historyCustomRange = null; calendarOpen = false },
            )
        }
        if (customRangeOpen) {
            ModernCustomRangeDialog(
                initial = historyCustomRange,
                onDismiss = { customRangeOpen = false },
                onApply = { historyCustomRange = it; historyAllRecords = false; customRangeOpen = false },
            )
        }
        if (statisticsCustomRangeOpen) {
            ModernCustomRangeDialog(
                initial = statisticsCustomRange,
                onDismiss = { statisticsCustomRangeOpen = false },
                onApply = { statisticsCustomRange = it; statisticsCustomRangeOpen = false },
            )
        }
    }
}

@Composable
private fun DesktopSidebar(
    selectedTab: DesktopModernTab,
    onSelect: (DesktopModernTab) -> Unit,
) {
    Column(
        modifier = Modifier.width(232.dp).fillMaxHeight().background(DesktopUiTokens.Sidebar).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Simple", color = Color.White, style = MaterialTheme.typography.h5)
        Text("Time Tracker", color = Color(0xFFC9D4D8), style = MaterialTheme.typography.subtitle1)
        Spacer(Modifier.height(30.dp))
        DesktopModernTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(if (selected) DesktopUiTokens.SidebarSelected else Color.Transparent, MaterialTheme.shapes.small)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tab.mark, color = if (selected) Color.White else Color(0xFFB0BEC5), style = MaterialTheme.typography.subtitle1)
                Spacer(Modifier.width(14.dp))
                Text(tab.title, color = if (selected) Color.White else Color(0xFFD7E0E3), style = MaterialTheme.typography.subtitle1)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("Linux desktop", color = Color(0xFF90A4AE), style = MaterialTheme.typography.caption)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModernTrackerPage(
    database: DesktopDatabase,
    activityEditorService: DesktopActivityEditorService,
    tagCategoryService: DesktopTagCategoryService,
    quickActions: DesktopQuickActions,
    activities: List<ActivityRow>,
    records: List<DesktopTimelineRecord>,
    range: DesktopTimeRange,
    now: Long,
    onCreateActivity: () -> Unit,
    onEditActivity: (ActivityRow) -> Unit,
    onManageTags: () -> Unit,
    onManageCategories: () -> Unit,
    onTimeSettings: () -> Unit,
    onNumericTagsRequired: (Long) -> Unit,
    onChanged: () -> Unit,
) {
    val completed = records.filterNot(DesktopTimelineRecord::isRunning)
    val completedByActivity = completed.groupBy(DesktopTimelineRecord::activityId)
        .mapValues { (_, list) -> list.sumOf { range.clippedDuration(it.startedAt, it.endedAt) } }
    val total = records.sumOf { range.clippedDuration(it.startedAt, it.endedAt) }
    val runningCount = activities.count { it.startedAt != null }
    val suggestionIds = remember(records, activities) {
        DesktopAutomationService(database).suggestionsFor(
            running = activities.filter { it.startedAt != null }.mapTo(mutableSetOf(), ActivityRow::id),
            previous = database.previousCompletedRecord()?.activityId,
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(DesktopUiTokens.ScreenPadding)) {
        DesktopPageHeader(
            title = "Трекер",
            subtitle = "Быстрый запуск активностей и текущий пользовательский день",
            actions = {
                TextButton(onClick = onTimeSettings) { Text("Время и недели") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onManageTags) { Text("Теги") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onManageCategories) { Text("Категории") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onCreateActivity) { Text("+ Активность") }
            },
        )
        Spacer(Modifier.height(DesktopUiTokens.SectionGap))
        Card(elevation = 0.dp, shape = MaterialTheme.shapes.medium) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(48.dp),
            ) {
                DesktopMetric("Сегодня", durationText(total))
                DesktopMetric("Запущено", runningCount.toString())
                DesktopMetric("Записей", records.size.toString())
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(quickActions.allowMultitasking, quickActions::setAllowMultitasking)
                        Text("Несколько таймеров", style = MaterialTheme.typography.body2)
                    }
                    Text(
                        if (quickActions.ignoreShortRecordsDurationSeconds == 0L) "Короткие записи: сохранять"
                        else "Игнорировать ≤ ${quickActions.ignoreShortRecordsDurationSeconds} сек.",
                        style = MaterialTheme.typography.caption,
                        color = DesktopUiTokens.SecondaryText,
                    )
                }
            }
        }
        Spacer(Modifier.height(DesktopUiTokens.SectionGap))
        if (suggestionIds.isNotEmpty()) {
            DesktopSectionTitle("Подсказки")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                activities.filter { it.id in suggestionIds }.forEach { suggested ->
                    OutlinedButton(onClick = { if (quickActions.toggle(suggested.id) == TimerActionResult.TAG_VALUE_REQUIRED) onNumericTagsRequired(suggested.id) }) {
                        Text(listOf(suggested.icon, suggested.name).filter(String::isNotBlank).joinToString(" "))
                    }
                }
            }
            Spacer(Modifier.height(DesktopUiTokens.SectionGap))
        }
        DesktopSectionTitle("Активности")
        Spacer(Modifier.height(12.dp))
        if (activities.isEmpty()) {
            ModernEmptyState("Создайте первую активность, чтобы начать отслеживание времени.", onCreateActivity)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(260.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                gridItems(activities, key = ActivityRow::id) { activity ->
                    ModernActivityCard(
                        activity = activity,
                        duration = (completedByActivity[activity.id] ?: 0L) +
                            (activity.startedAt?.let { range.clippedDuration(it, now) } ?: 0L),
                        categories = tagCategoryService.categories()
                            .filter { it.id in database.categoryIdsForActivity(activity.id) },
                        pinned = activity.id in quickActions.state.pinned.map(TrayActivity::id),
                        onToggle = { if (quickActions.toggle(activity.id) == TimerActionResult.TAG_VALUE_REQUIRED) onNumericTagsRequired(activity.id) },
                        onEdit = { onEditActivity(activity) },
                        onArchive = { database.archiveActivity(activity.id); onChanged() },
                        onPin = { quickActions.setPinned(activity.id, !it); onChanged() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernActivityCard(
    activity: ActivityRow,
    duration: Long,
    categories: List<DesktopCategory>,
    pinned: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onPin: (Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val running = activity.startedAt != null
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        backgroundColor = if (running) DesktopUiTokens.Running else identityColor(activity.colorInt, DesktopUiTokens.Active),
        contentColor = Color.White,
        elevation = if (running) 8.dp else 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(listOf(activity.icon, activity.name).filter(String::isNotBlank).joinToString(" "), style = MaterialTheme.typography.h6, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (running) "● Запущено ${clockText(activity.startedAt!!)}" else "Нажмите, чтобы начать",
                        style = MaterialTheme.typography.body2,
                        color = Color(0xFFE3ECEF),
                    )
                }
                Box {
                    TextButton(onClick = { menuOpen = true }) { Text("⋮", color = Color.White, style = MaterialTheme.typography.h5) }
                    DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(onClick = { menuOpen = false; onEdit() }) { Text("Изменить") }
                        DropdownMenuItem(onClick = { menuOpen = false; onPin(pinned) }) {
                            Text(if (pinned) "Убрать из tray" else "Закрепить в tray")
                        }
                        DropdownMenuItem(onClick = { menuOpen = false; onArchive() }) { Text("В архив") }
                    }
                }
            }
            if (categories.isNotEmpty()) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { DesktopTagChip(it.name, background = identityColor(it.colorInt, DesktopUiTokens.Tag)) }
                }
            }
            Text(durationText(duration), style = MaterialTheme.typography.h4, fontWeight = FontWeight.Bold)
            Text(
                if (activity.defaultDurationSeconds > 0) "Мгновенная запись · ${durationText(activity.defaultDurationSeconds * 1_000L)}"
                else if (running) "Нажмите, чтобы остановить" else "▶ Старт",
                style = MaterialTheme.typography.body2,
                color = Color(0xFFE3ECEF),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModernHistoryPage(
    database: DesktopDatabase,
    recordService: DesktopRecordService,
    tagCategoryService: DesktopTagCategoryService,
    quickActions: DesktopQuickActions,
    activities: List<ActivityRow>,
    records: List<DesktopTimelineRecord>,
    range: DesktopTimeRange,
    date: LocalDate,
    today: LocalDate,
    filter: DesktopRecordFilter,
    onDateChange: (LocalDate) -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenCustomRange: () -> Unit,
    allRecords: Boolean,
    onAllRecordsChange: (Boolean) -> Unit,
    onOpenFilters: () -> Unit,
    onOpenSavedFilters: () -> Unit,
    onSaveFilter: () -> Unit,
    onClearFilter: () -> Unit,
    onFilterChange: (DesktopRecordFilter) -> Unit,
    onCreateManual: () -> Unit,
    onEditRecord: (DesktopTimelineRecord) -> Unit,
    onEditRunningRecord: (DesktopTimelineRecord) -> Unit,
    onSplitRecord: (DesktopTimelineRecord) -> Unit,
    onChanged: () -> Unit,
) {
    var selectedRecordIds by remember(date, range, filter) { mutableStateOf(emptySet<Long>()) }
    Column(modifier = Modifier.fillMaxSize().padding(DesktopUiTokens.ScreenPadding)) {
        // Keep the heading independent from the action toolbar. Previously both
        // competed in one Row and the toolbar could reduce the heading to one letter.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Записи", style = MaterialTheme.typography.h4, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${date.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))} · ${durationText(records.sumOf { range.clippedDuration(it.startedAt, it.endedAt) })}",
                    style = MaterialTheme.typography.body2,
                    color = DesktopUiTokens.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onDateChange(date.minusDays(1)) }) { Text("←") }
                TextButton(onClick = { onDateChange(today) }, enabled = date != today) { Text("Сегодня") }
                OutlinedButton(onClick = { onDateChange(date.plusDays(1)) }, enabled = date < today) { Text("→") }
            }
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
                OutlinedButton(onClick = onOpenCalendar) { Text("Календарь") }
                OutlinedButton(onClick = onOpenCustomRange) { Text("Диапазон") }
                OutlinedButton(onClick = { onAllRecordsChange(!allRecords) }) {
                    Text(if (allRecords) "По дням" else "Все записи")
                }
                OutlinedButton(onClick = onOpenFilters) { Text("Фильтр") }
                TextButton(onClick = onOpenSavedFilters) { Text("Сохранённые") }
                Button(onClick = onCreateManual) { Text("+ Запись") }
        }
        if (filter != DesktopRecordFilter.EMPTY) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DesktopTagChip("Фильтр активен")
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onClearFilter) { Text("Сбросить") }
                TextButton(onClick = onSaveFilter) { Text("Сохранить как…") }
            }
        }
        if (selectedRecordIds.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DesktopTagChip("Выбрано: ${selectedRecordIds.size}")
                TextButton(onClick = { selectedRecordIds = emptySet() }) { Text("Снять выбор") }
                TextButton(onClick = {
                    onFilterChange(filter.copy(manuallyExcludedRecordIds = filter.manuallyExcludedRecordIds + selectedRecordIds))
                    selectedRecordIds = emptySet()
                }) { Text("Скрыть выбранные") }
                TextButton(onClick = {
                    selectedRecordIds.forEach { recordService.delete(it) }
                    selectedRecordIds = emptySet()
                    onChanged()
                }) { Text("Удалить выбранные") }
            }
        }
        Spacer(Modifier.height(DesktopUiTokens.SectionGap))
        if (records.isEmpty()) {
            ModernEmptyState("В выбранный пользовательский день записей нет.", onCreateManual)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                items(records, key = { "${it.isRunning}-${it.id}" }) { record ->
                    ModernRecordCard(
                        record = record,
                        duration = range.clippedDuration(record.startedAt, record.endedAt),
                        onEdit = { if (!record.isUntracked) onEditRecord(record) },
                        onEditRunning = { onEditRunningRecord(record) },
                        selected = record.id in selectedRecordIds,
                        onSelectionChange = { selected ->
                            if (!record.isRunning && !record.isUntracked) {
                                selectedRecordIds = selectedRecordIds.toMutableSet().apply {
                                    if (selected) add(record.id) else remove(record.id)
                                }
                            }
                        },
                        onDuplicate = {
                            if (recordService.create(DesktopRecordDraft(record.activityId, record.startedAt, record.endedAt, record.comment, record.tags.map { DesktopRecordTag(it.tagId, it.numericValue) })) == RecordWriteResult.SAVED) onChanged()
                        },
                        onSplit = { onSplitRecord(record) },
                        onContinue = { quickActions.repeatRecord(record); onChanged() },
                        onDelete = {
                            if (!record.isUntracked && recordService.delete(record.id) == RecordWriteResult.SAVED) onChanged()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernRecordCard(
    record: DesktopTimelineRecord,
    duration: Long,
    onEdit: () -> Unit,
    onEditRunning: () -> Unit,
    selected: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onSplit: () -> Unit,
    onContinue: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = when {
            record.isUntracked -> DesktopUiTokens.Divider
            record.isRunning -> DesktopUiTokens.Running
            else -> identityColor(record.colorInt, DesktopUiTokens.Active)
        },
        contentColor = Color.White,
        elevation = if (record.isRunning) 5.dp else 1.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!record.isRunning && !record.isUntracked) {
                Checkbox(selected, onCheckedChange = onSelectionChange)
                Spacer(Modifier.width(6.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(listOf(record.icon, record.activityName).filter(String::isNotBlank).joinToString(" "), style = MaterialTheme.typography.h6)
                Text(
                    if (record.isRunning) "${clockText(record.startedAt)} · запущено" else "${clockText(record.startedAt)} — ${clockText(record.endedAt)}",
                    style = MaterialTheme.typography.body2,
                    color = Color(0xFFE1E9EC),
                )
                if (record.comment.isNotBlank()) Text(record.comment, style = MaterialTheme.typography.body2, color = Color(0xFFF4F7F8))
                if (record.tags.isNotEmpty()) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        record.tags.forEach { tag ->
                            DesktopTagChip(
                                buildString {
                                    append(tag.name)
                                    tag.numericValue?.let { append(" ${formatDesktopTagValue(it)}${tag.valueSuffix.takeIf(String::isNotBlank)?.let { suffix -> " $suffix" }.orEmpty()}") }
                                },
                            )
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(durationText(duration), style = MaterialTheme.typography.h6)
                if (record.isRunning) {
                    TextButton(onClick = onEditRunning) { Text("Изменить", color = Color.White) }
                } else if (!record.isUntracked) {
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("⋮", color = Color.White, style = MaterialTheme.typography.h5) }
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(onClick = { menuOpen = false; onEdit() }) { Text("Изменить") }
                            DropdownMenuItem(onClick = { menuOpen = false; onDuplicate() }) { Text("Дублировать") }
                            DropdownMenuItem(onClick = { menuOpen = false; onSplit() }) { Text("Разделить") }
                            DropdownMenuItem(onClick = { menuOpen = false; onContinue() }) { Text("Продолжить") }
                            DropdownMenuItem(onClick = { menuOpen = false; onDelete() }) { Text("Удалить") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernStatisticsPage(
    database: DesktopDatabase,
    tagCategoryService: DesktopTagCategoryService,
    records: List<DesktopTimelineRecord>,
    range: DesktopTimeRange,
    rangeLength: DesktopRangeLength,
    date: LocalDate,
    today: LocalDate,
    filter: DesktopRecordFilter,
    onDateChange: (LocalDate) -> Unit,
    onRangeLengthChange: (DesktopRangeLength) -> Unit,
    onOpenCustomRange: () -> Unit,
    customRangeActive: Boolean,
    onClearCustomRange: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenSavedFilters: () -> Unit,
    onSaveFilter: () -> Unit,
    onClearFilter: () -> Unit,
) {
    var grouping by remember { mutableStateOf(DesktopStatisticsGrouping.ACTIVITY) }
    var drillDown by remember { mutableStateOf<DesktopStatisticsBreakdown?>(null) }
    val totals = remember(records, range, grouping) {
        DesktopDetailedStatisticsService(database).breakdown(records, range, grouping)
    }
    Column(modifier = Modifier.fillMaxSize().padding(DesktopUiTokens.ScreenPadding)) {
        DesktopPageHeader(
            title = "Статистика",
            subtitle = "${date.format(DateTimeFormatter.ofPattern("LLLL yyyy"))} · ${durationText(totals.sumOf(DesktopStatisticsBreakdown::durationMillis))}",
            actions = {
                OutlinedButton(onClick = { onDateChange(date.minusDays(1)) }) { Text("←") }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { onDateChange(today) }, enabled = date != today) { Text("Сегодня") }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { onDateChange(date.plusDays(1)) }, enabled = date < today) { Text("→") }
            },
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DesktopRangeLength.entries.forEach { item ->
                val title = when (item) {
                    DesktopRangeLength.DAY -> "День"
                    DesktopRangeLength.WEEK -> "Неделя"
                    DesktopRangeLength.MONTH -> "Месяц"
                }
                if (item == rangeLength) Button(onClick = {}) { Text(title) }
                else OutlinedButton(onClick = { onRangeLengthChange(item) }) { Text(title) }
            }
            OutlinedButton(onClick = onOpenCustomRange) { Text("Диапазон") }
            if (customRangeActive) TextButton(onClick = onClearCustomRange) { Text("Сбросить диапазон") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onOpenFilters) { Text("Фильтр") }
            TextButton(onClick = onOpenSavedFilters) { Text("Сохранённые") }
            if (filter != DesktopRecordFilter.EMPTY) {
                DesktopTagChip("Фильтр активен")
                TextButton(onClick = onClearFilter) { Text("Сбросить") }
                TextButton(onClick = onSaveFilter) { Text("Сохранить") }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopStatisticsGrouping.entries.forEach { item ->
                val label = when (item) {
                    DesktopStatisticsGrouping.ACTIVITY -> "Активности"
                    DesktopStatisticsGrouping.CATEGORY -> "Категории"
                    DesktopStatisticsGrouping.TAG -> "Теги"
                }
                if (item == grouping) Button(onClick = {}) { Text(label) }
                else OutlinedButton(onClick = { grouping = item }) { Text(label) }
            }
        }
        if (totals.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ModernStatisticsPieChart(totals)
        }
        Spacer(Modifier.height(DesktopUiTokens.SectionGap))
        if (totals.isEmpty()) {
            ModernEmptyState("За выбранный диапазон нет данных.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                items(totals, key = DesktopStatisticsBreakdown::id) { item ->
                    val share = item.durationMillis.toFloat() / totals.sumOf(DesktopStatisticsBreakdown::durationMillis).coerceAtLeast(1L)
                    Card(modifier = Modifier.fillMaxWidth().clickable { drillDown = item }, elevation = 0.dp, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(listOf(item.icon, item.name).filter(String::isNotBlank).joinToString(" "), modifier = Modifier.weight(1f), style = MaterialTheme.typography.h6)
                                Text(durationText(item.durationMillis), style = MaterialTheme.typography.h6)
                            }
                            Box(Modifier.fillMaxWidth().height(7.dp).background(DesktopUiTokens.Divider, RoundedCornerShape(8.dp))) {
                                Box(Modifier.fillMaxWidth(share).height(7.dp).background(identityColor(item.color, DesktopUiTokens.Primary), RoundedCornerShape(8.dp)))
                            }
                            item.numericValueSum?.let { sum ->
                                Text("Сумма числовых значений: ${formatDesktopTagValue(sum)} · ${item.numericValueCount}", style = MaterialTheme.typography.body2, color = DesktopUiTokens.SecondaryText)
                            }
                        }
                    }
                }
            }
        }
    }
    drillDown?.let { selected ->
        val detailRecords = records.filter { record ->
            !record.isUntracked && when (grouping) {
                DesktopStatisticsGrouping.ACTIVITY -> record.activityId == selected.id
                DesktopStatisticsGrouping.CATEGORY -> if (selected.id == 0L) record.categoryIds.isEmpty() else selected.id in record.categoryIds
                DesktopStatisticsGrouping.TAG -> if (selected.id == 0L) record.tags.isEmpty() else record.tags.any { it.tagId == selected.id }
            }
        }
        ModernStatisticsDrillDownDialog(selected.name, detailRecords, range) { drillDown = null }
    }
}

@Composable
private fun ModernArchivePage(database: DesktopDatabase, activities: List<ActivityRow>, onChanged: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(DesktopUiTokens.ScreenPadding)) {
        DesktopPageHeader("Архив", "История записей архивированных активностей сохраняется")
        Spacer(Modifier.height(DesktopUiTokens.SectionGap))
        if (activities.isEmpty()) ModernEmptyState("Архив пуст.") else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(activities, key = ActivityRow::id) { activity ->
                Card(elevation = 0.dp, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(activity.name, style = MaterialTheme.typography.h6)
                            Text("Архивирована", style = MaterialTheme.typography.body2, color = DesktopUiTokens.SecondaryText)
                        }
                        OutlinedButton(onClick = { database.restoreActivity(activity.id); onChanged() }) { Text("Восстановить") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernEmptyState(message: String, onAction: (() -> Unit)? = null) {
    Card(elevation = 0.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, style = MaterialTheme.typography.body1, color = DesktopUiTokens.SecondaryText)
            onAction?.let { Button(onClick = it) { Text("Добавить") } }
        }
    }
}
