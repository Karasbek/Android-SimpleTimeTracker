package com.example.util.simpletimetracker.desktop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Composable
internal fun ModernActivityEditorDialog(
    activity: ActivityRow?,
    database: DesktopDatabase,
    activityEditorService: DesktopActivityEditorService,
    tagCategoryService: DesktopTagCategoryService,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val categories = remember { tagCategoryService.categories() }
    val tags = remember { tagCategoryService.tags().filterNot(DesktopTag::archived) }
    val existingCategories = remember(activity?.id) { activity?.let { database.categoryIdsForActivity(it.id) } ?: emptySet() }
    val existingAllowed = remember(activity?.id) { activity?.let { database.allowedTagIdsForActivity(it.id) } ?: emptySet() }
    val existingDefaults = remember(activity?.id) { activity?.let { database.defaultTagIdsForActivity(it.id) } ?: emptySet() }
    var name by remember(activity?.id) { mutableStateOf(activity?.name.orEmpty()) }
    var duration by remember(activity?.id) { mutableStateOf(activity?.defaultDurationSeconds?.toString() ?: "0") }
    var icon by remember(activity?.id) { mutableStateOf(activity?.icon.orEmpty()) }
    var color by remember(activity?.id) { mutableStateOf(activity?.colorInt.orEmpty()) }
    var note by remember(activity?.id) { mutableStateOf(activity?.note.orEmpty()) }
    var selectedCategories by remember(activity?.id) { mutableStateOf(existingCategories) }
    var selectedAllowed by remember(activity?.id) { mutableStateOf(existingAllowed) }
    var selectedDefaults by remember(activity?.id) { mutableStateOf(existingDefaults) }
    var error by remember { mutableStateOf<String?>(null) }

    DesktopDialogSurface(
        title = if (activity == null) "Новая активность" else "Изменить активность",
        onDismiss = onDismiss,
        wide = true,
    ) {
        ModernField("Название", name, { name = it }, "Например, Работа")
        ModernField("Значок", icon, { icon = it }, "Например, 🧠")
        ModernField("Цвет", color, { color = it }, "#37474F")
        ModernField("Заметка", note, { note = it }, "Необязательно", singleLine = false)
        ModernField("Длительность мгновенной записи, сек.", duration, { duration = it }, "0 — обычный таймер")
        ModernCheckGroup("Категории", categories.map { it.id to it.name }, selectedCategories) { selectedCategories = it }
        ModernCheckGroup("Доступные теги", tags.map { it.id to it.name }, selectedAllowed) { selectedAllowed = it }
        ModernCheckGroup("Теги по умолчанию", tags.map { it.id to it.name }, selectedDefaults) { selectedDefaults = it }
        error?.let { ModernFormError(it) }
        DesktopDialogActions(
            onCancel = onDismiss,
            confirmLabel = "Сохранить",
            enabled = name.isNotBlank() && duration.toLongOrNull()?.let { it >= 0 } == true,
            onConfirm = {
                val seconds = duration.toLongOrNull()
                if (seconds == null || seconds < 0 || name.isBlank()) {
                    error = "Укажите название и неотрицательную длительность"
                    return@DesktopDialogActions
                }
                val target = activity ?: run {
                    database.addActivity(name)
                    database.activities().filter { it.name == name.trim() }.maxByOrNull(ActivityRow::id)
                }
                if (target == null) {
                    error = "Не удалось создать активность"
                    return@DesktopDialogActions
                }
                when (
                    activityEditorService.update(
                        target.id,
                        DesktopActivityDetailsDraft(
                            name = name,
                            defaultDurationSeconds = seconds,
                            categoryIds = selectedCategories,
                            allowedTagIds = selectedAllowed + selectedDefaults,
                            defaultTagIds = selectedDefaults,
                            icon = icon,
                            colorInt = color,
                            note = note,
                        ),
                    )
                ) {
                    DesktopTaxonomyWriteResult.SAVED -> onSaved()
                    DesktopTaxonomyWriteResult.INVALID_NAME -> error = "Укажите корректное название"
                    DesktopTaxonomyWriteResult.INVALID_RELATION -> error = "Выбрана недоступная категория или тег"
                    DesktopTaxonomyWriteResult.NOT_FOUND -> error = "Активность не найдена"
                    DesktopTaxonomyWriteResult.NAME_CONFLICT -> error = "Такое название уже существует"
                }
            },
        )
    }
}

@Composable
internal fun ModernRecordEditorDialog(
    record: DesktopTimelineRecord?,
    database: DesktopDatabase,
    recordService: DesktopRecordService,
    activities: List<ActivityRow>,
    selectedDate: LocalDate,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    if (activities.isEmpty()) {
        ModernNoticeDialog("Нет активностей", "Сначала создайте активность.", onDismiss)
        return
    }
    val defaultEnd = if (record == null && selectedDate == LocalDate.now()) LocalDateTime.now().withSecond(0).withNano(0)
    else selectedDate.atTime(13, 0)
    val defaultStart = if (record == null && selectedDate == LocalDate.now()) defaultEnd.minusMinutes(30) else selectedDate.atTime(12, 0)
    var activityId by remember(record?.id) { mutableStateOf(record?.activityId ?: activities.first().id) }
    var startedAt by remember(record?.id) { mutableStateOf(record?.let { modernDateTimeText(it.startedAt) } ?: defaultStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().let(::modernDateTimeText)) }
    var endedAt by remember(record?.id) { mutableStateOf(record?.let { modernDateTimeText(it.endedAt) } ?: defaultEnd.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().let(::modernDateTimeText)) }
    var comment by remember(record?.id) { mutableStateOf(record?.comment.orEmpty()) }
    var selectedTags by remember(record?.id, activityId) {
        mutableStateOf<Set<Long>>(
            record?.tags
                ?.map(DesktopRecordTagView::tagId)
                ?.filter { it in database.selectableTagsForActivity(activityId).map(DesktopTag::id).toSet() }
                ?.toSet()
                ?: emptySet(),
        )
    }
    val numericValues = remember(record?.id, activityId) {
        mutableStateMapOf<Long, String>().apply {
            record?.tags?.forEach { tag -> tag.numericValue?.let { put(tag.tagId, formatDesktopTagValue(it)) } }
        }
    }
    val selectableTags = remember(activityId) { database.selectableTagsForActivity(activityId) }
    var error by remember { mutableStateOf<String?>(null) }

    DesktopDialogSurface(
        title = if (record == null) "Добавить запись" else "Изменить запись",
        onDismiss = onDismiss,
        wide = true,
    ) {
        ModernSelector(
            "Активность",
            activities,
            activities.firstOrNull { it.id == activityId } ?: activities.first(),
            ActivityRow::id,
            ActivityRow::name,
        ) { activityId = it.id }
        ModernField("Начало", startedAt, { startedAt = it }, "гггг-мм-дд чч:мм")
        ModernField("Конец", endedAt, { endedAt = it }, "гггг-мм-дд чч:мм")
        ModernField("Комментарий", comment, { comment = it }, "Необязательно", singleLine = false)
        if (selectableTags.isNotEmpty()) {
            Text("Теги", style = MaterialTheme.typography.subtitle1)
            selectableTags.forEach { tag ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(tag.id in selectedTags, onCheckedChange = { checked ->
                        selectedTags = selectedTags.toMutableSet().apply { if (checked) add(tag.id) else remove(tag.id) }
                    })
                    Text(tag.name, modifier = Modifier.weight(1f))
                    if (tag.valueType == DesktopTagValueType.NUMERIC && tag.id in selectedTags) {
                        OutlinedTextField(
                            value = numericValues[tag.id].orEmpty(),
                            onValueChange = { numericValues[tag.id] = it },
                            label = { Text(tag.valueSuffix.ifBlank { "Значение" }) },
                            modifier = Modifier.width(180.dp),
                            singleLine = true,
                        )
                    }
                }
            }
        }
        error?.let { ModernFormError(it) }
        DesktopDialogActions(
            onCancel = onDismiss,
            confirmLabel = "Сохранить",
            onConfirm = {
                val start = modernDateTimeParse(startedAt)
                val end = modernDateTimeParse(endedAt)
                if (start == null || end == null) {
                    error = "Формат времени: гггг-мм-дд чч:мм"
                    return@DesktopDialogActions
                }
                val tags = buildList {
                    selectableTags.filter { it.id in selectedTags }.forEach { tag ->
                        val numeric = if (tag.valueType == DesktopTagValueType.NUMERIC) numericValues[tag.id]?.trim()?.toDoubleOrNull() else null
                        if (tag.valueType == DesktopTagValueType.NUMERIC && numeric == null) {
                            error = "Для тега ${tag.name} укажите число"
                            return@DesktopDialogActions
                        }
                        add(DesktopRecordTag(tag.id, numeric))
                    }
                }
                val draft = DesktopRecordDraft(activityId, start, end, comment, tags)
                val result = if (record == null) recordService.create(draft) else recordService.update(record.id, draft)
                when (result) {
                    RecordWriteResult.SAVED -> onSaved()
                    RecordWriteResult.ACTIVITY_UNAVAILABLE -> error = "Активность недоступна"
                    RecordWriteResult.TAG_UNAVAILABLE -> error = "Тег недоступен"
                    RecordWriteResult.INVALID_TAG_VALUE -> error = "Некорректное значение тега"
                    RecordWriteResult.RECORD_MISSING -> error = "Запись не найдена"
                }
            },
        )
    }
}

@Composable
internal fun ModernTagsManagerDialog(
    service: DesktopTagCategoryService,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    var revision by remember { mutableStateOf(0) }
    var editor by remember { mutableStateOf<DesktopTag?>(null) }
    var creating by remember { mutableStateOf(false) }
    val tags = remember(revision) { service.tags() }
    if (editor != null || creating) {
        ModernTagEditorDialog(
            tag = editor,
            onDismiss = { editor = null; creating = false },
            onSaved = { draft ->
                if (service.saveTag(editor?.id ?: 0, draft).first == DesktopTaxonomyWriteResult.SAVED) {
                    revision++; onChanged(); editor = null; creating = false
                }
            },
        )
        return
    }
    DesktopDialogSurface("Теги", onDismiss, wide = true) {
        Text("Обычные и числовые теги, используемые в записях.", style = MaterialTheme.typography.body2, color = DesktopUiTokens.SecondaryText)
        Button(onClick = { creating = true }) { Text("+ Тег") }
        Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag ->
                Card(elevation = 0.dp, shape = MaterialTheme.shapes.small) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tag.name, style = MaterialTheme.typography.subtitle1)
                            Text(
                                buildString { append(if (tag.valueType == DesktopTagValueType.NUMERIC) "Числовой ${tag.valueSuffix}" else "Обычный") ; if (tag.archived) append(" · архив") },
                                style = MaterialTheme.typography.caption,
                                color = DesktopUiTokens.SecondaryText,
                            )
                        }
                        TextButton(onClick = { editor = tag }) { Text("Изм.") }
                        TextButton(onClick = {
                            if (tag.archived) service.restoreTag(tag.id) else service.archiveTag(tag.id)
                            revision++; onChanged()
                        }) { Text(if (tag.archived) "Вернуть" else "Архив") }
                        TextButton(onClick = { service.deleteTag(tag.id); revision++; onChanged() }) { Text("Удалить") }
                    }
                }
            }
        }
        DesktopDialogActions(onDismiss, "Готово", onDismiss)
    }
}

@Composable
private fun ModernTagEditorDialog(tag: DesktopTag?, onDismiss: () -> Unit, onSaved: (DesktopTagDraft) -> Unit) {
    var name by remember(tag?.id) { mutableStateOf(tag?.name.orEmpty()) }
    var type by remember(tag?.id) { mutableStateOf(tag?.valueType ?: DesktopTagValueType.NONE) }
    var suffix by remember(tag?.id) { mutableStateOf(tag?.valueSuffix.orEmpty()) }
    DesktopDialogSurface(if (tag == null) "Новый тег" else "Изменить тег", onDismiss) {
        ModernField("Название", name, { name = it })
        ModernSelector("Тип", DesktopTagValueType.entries.toList(), type, { it.name }, { if (it == DesktopTagValueType.NUMERIC) "Числовой" else "Обычный" }) { type = it }
        if (type == DesktopTagValueType.NUMERIC) ModernField("Суффикс", suffix, { suffix = it }, "Например, кг")
        DesktopDialogActions(onDismiss, "Сохранить", { onSaved(DesktopTagDraft(name, type, suffix)) }, name.isNotBlank())
    }
}

@Composable
internal fun ModernCategoriesManagerDialog(
    service: DesktopTagCategoryService,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    var revision by remember { mutableStateOf(0) }
    var editor by remember { mutableStateOf<DesktopCategory?>(null) }
    var creating by remember { mutableStateOf(false) }
    val categories = remember(revision) { service.categories() }
    if (editor != null || creating) {
        ModernCategoryEditorDialog(
            category = editor,
            onDismiss = { editor = null; creating = false },
            onSaved = { draft ->
                if (service.saveCategory(editor?.id ?: 0, draft).first == DesktopTaxonomyWriteResult.SAVED) {
                    revision++; onChanged(); editor = null; creating = false
                }
            },
        )
        return
    }
    DesktopDialogSurface("Категории", onDismiss) {
        Text("Категории группируют активности и остаются необязательными.", style = MaterialTheme.typography.body2, color = DesktopUiTokens.SecondaryText)
        Button(onClick = { creating = true }) { Text("+ Категория") }
        Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { category ->
                Card(elevation = 0.dp, shape = MaterialTheme.shapes.small) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(category.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.subtitle1)
                        if (category.note.isNotBlank()) Text(category.note, style = MaterialTheme.typography.caption, color = DesktopUiTokens.SecondaryText)
                        TextButton(onClick = { editor = category }) { Text("Изм.") }
                        TextButton(onClick = { service.deleteCategory(category.id); revision++; onChanged() }) { Text("Удалить") }
                    }
                }
            }
        }
        DesktopDialogActions(onDismiss, "Готово", onDismiss)
    }
}

@Composable
private fun ModernCategoryEditorDialog(category: DesktopCategory?, onDismiss: () -> Unit, onSaved: (DesktopCategoryDraft) -> Unit) {
    var name by remember(category?.id) { mutableStateOf(category?.name.orEmpty()) }
    var color by remember(category?.id) { mutableStateOf(category?.colorInt.orEmpty()) }
    var note by remember(category?.id) { mutableStateOf(category?.note.orEmpty()) }
    DesktopDialogSurface(if (category == null) "Новая категория" else "Изменить категорию", onDismiss) {
        ModernField("Название", name, { name = it })
        ModernField("Цвет", color, { color = it }, "#37474F")
        ModernField("Заметка", note, { note = it }, "Необязательно", singleLine = false)
        DesktopDialogActions(onDismiss, "Сохранить", { onSaved(DesktopCategoryDraft(name, colorInt = color, note = note)) }, name.isNotEmpty())
    }
}

@Composable
private fun ModernField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String = "",
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = hint.takeIf(String::isNotBlank)?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
    )
}

@Composable
private fun ModernFormError(message: String) {
    Text(message, color = MaterialTheme.colors.error, style = MaterialTheme.typography.body2)
}

@Composable
private fun <T> ModernSelector(
    label: String,
    items: List<T>,
    selected: T,
    key: (T) -> Any,
    title: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.caption, color = DesktopUiTokens.SecondaryText)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(title(selected), modifier = Modifier.weight(1f))
                Text("⌄")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                items.forEach { item ->
                    DropdownMenuItem(onClick = { expanded = false; onSelected(item) }) {
                        Text(title(item))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernCheckGroup(
    title: String,
    entries: List<Pair<Long, String>>,
    selected: Set<Long>,
    onSelectionChanged: (Set<Long>) -> Unit,
) {
    if (entries.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.subtitle1)
        entries.forEach { (id, name) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(id in selected, onCheckedChange = { checked ->
                    onSelectionChanged(selected.toMutableSet().apply {
                        if (checked) add(id) else remove(id)
                    })
                })
                Text(name, style = MaterialTheme.typography.body2)
            }
        }
    }
}

@Composable
private fun ModernNoticeDialog(title: String, message: String, onDismiss: () -> Unit) {
    DesktopDialogSurface(title, onDismiss) {
        Text(message, style = MaterialTheme.typography.body1)
        DesktopDialogActions(onDismiss, "Понятно", onDismiss)
    }
}

@Composable
internal fun ModernFilterEditorDialog(
    initial: DesktopRecordFilter,
    activities: List<ActivityRow>,
    tagCategoryService: DesktopTagCategoryService,
    onDismiss: () -> Unit,
    onApply: (DesktopRecordFilter) -> Unit,
) {
    val categories = remember { tagCategoryService.categories() }
    val tags = remember { tagCategoryService.tags() }
    var includedActivities by remember { mutableStateOf(initial.includedActivityIds) }
    var excludedActivities by remember { mutableStateOf(initial.excludedActivityIds) }
    var includedCategories by remember { mutableStateOf(initial.includedCategoryIds) }
    var excludedCategories by remember { mutableStateOf(initial.excludedCategoryIds) }
    var includedTags by remember { mutableStateOf(initial.includedTagIds) }
    var excludedTags by remember { mutableStateOf(initial.excludedTagIds) }
    var includeUncategorized by remember { mutableStateOf(initial.includeUncategorized) }
    var excludeUncategorized by remember { mutableStateOf(initial.excludeUncategorized) }
    var includeUntagged by remember { mutableStateOf(initial.includeUntagged) }
    var excludeUntagged by remember { mutableStateOf(initial.excludeUntagged) }
    fun result() = DesktopRecordFilter(
        includedActivityIds = includedActivities,
        excludedActivityIds = excludedActivities,
        includedCategoryIds = includedCategories,
        excludedCategoryIds = excludedCategories,
        includeUncategorized = includeUncategorized,
        excludeUncategorized = excludeUncategorized,
        includedTagIds = includedTags,
        excludedTagIds = excludedTags,
        includeUntagged = includeUntagged,
        excludeUntagged = excludeUntagged,
    )
    DesktopDialogSurface("Фильтр записей", onDismiss, wide = true) {
        Text("Включённые условия выбирают подходящие записи; исключения всегда убирают их из результата.", style = MaterialTheme.typography.body2, color = DesktopUiTokens.SecondaryText)
        ModernCheckGroup("Включить активности", activities.map { it.id to it.name }, includedActivities) { includedActivities = it }
        ModernCheckGroup("Исключить активности", activities.map { it.id to it.name }, excludedActivities) { excludedActivities = it }
        ModernCheckGroup("Включить категории", categories.map { it.id to it.name }, includedCategories) { includedCategories = it }
        ModernCheckGroup("Исключить категории", categories.map { it.id to it.name }, excludedCategories) { excludedCategories = it }
        ModernBooleanOption("Включить без категории", includeUncategorized) { includeUncategorized = it }
        ModernBooleanOption("Исключить без категории", excludeUncategorized) { excludeUncategorized = it }
        ModernCheckGroup("Включить теги", tags.map { it.id to it.name }, includedTags) { includedTags = it }
        ModernCheckGroup("Исключить теги", tags.map { it.id to it.name }, excludedTags) { excludedTags = it }
        ModernBooleanOption("Включить без тегов", includeUntagged) { includeUntagged = it }
        ModernBooleanOption("Исключить без тегов", excludeUntagged) { excludeUntagged = it }
        DesktopDialogActions(onDismiss, "Применить", { onApply(result()) })
    }
}

@Composable
private fun ModernBooleanOption(label: String, value: Boolean, onChanged: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, onCheckedChange = onChanged)
        Text(label, style = MaterialTheme.typography.body2)
    }
}

@Composable
internal fun ModernSaveFilterDialog(
    service: DesktopSavedFilterService,
    filter: DesktopRecordFilter,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    DesktopDialogSurface("Сохранить фильтр", onDismiss) {
        ModernField("Название", name, { name = it }, "Например, Рабочий день")
        error?.let { ModernFormError(it) }
        DesktopDialogActions(onDismiss, "Сохранить", {
            when (service.save(name = name, filter = filter).first) {
                DesktopSavedFilterResult.SAVED -> onSaved()
                DesktopSavedFilterResult.INVALID_NAME -> error = "Укажите название"
                DesktopSavedFilterResult.NAME_CONFLICT -> error = "Такой фильтр уже существует"
                DesktopSavedFilterResult.NOT_FOUND -> error = "Не удалось сохранить фильтр"
            }
        }, name.isNotBlank())
    }
}

@Composable
internal fun ModernSavedFiltersDialog(
    service: DesktopSavedFilterService,
    activities: List<ActivityRow>,
    tagCategoryService: DesktopTagCategoryService,
    onDismiss: () -> Unit,
    onApply: (DesktopRecordFilter) -> Unit,
    onChanged: () -> Unit,
) {
    var revision by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<DesktopSavedRecordFilter?>(null) }
    val filters = remember(revision) { service.all() }
    if (editing != null) {
        ModernSavedFilterEditorDialog(
            existing = editing!!,
            service = service,
            activities = activities,
            tagCategoryService = tagCategoryService,
            onDismiss = { editing = null },
            onSaved = { revision++; editing = null; onChanged() },
        )
        return
    }
    DesktopDialogSurface("Сохранённые фильтры", onDismiss, wide = true) {
        if (filters.isEmpty()) Text("Пока нет сохранённых фильтров.", color = DesktopUiTokens.SecondaryText)
        filters.forEach { item ->
            Card(elevation = 0.dp, shape = MaterialTheme.shapes.small) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.subtitle1)
                    TextButton(onClick = { onApply(item.filter); onDismiss() }) { Text("Выбрать") }
                    TextButton(onClick = { editing = item }) { Text("Изм.") }
                    TextButton(onClick = { service.delete(item.id); revision++; onChanged() }) { Text("Удалить") }
                }
            }
        }
        DesktopDialogActions(onDismiss, "Готово", onDismiss)
    }
}

@Composable
private fun ModernSavedFilterEditorDialog(
    existing: DesktopSavedRecordFilter,
    service: DesktopSavedFilterService,
    activities: List<ActivityRow>,
    tagCategoryService: DesktopTagCategoryService,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val categories = remember { tagCategoryService.categories() }
    val tags = remember { tagCategoryService.tags() }
    var name by remember { mutableStateOf(existing.name) }
    var includedActivities by remember { mutableStateOf(existing.filter.includedActivityIds) }
    var excludedActivities by remember { mutableStateOf(existing.filter.excludedActivityIds) }
    var includedCategories by remember { mutableStateOf(existing.filter.includedCategoryIds) }
    var excludedCategories by remember { mutableStateOf(existing.filter.excludedCategoryIds) }
    var includedTags by remember { mutableStateOf(existing.filter.includedTagIds) }
    var excludedTags by remember { mutableStateOf(existing.filter.excludedTagIds) }
    var includeUncategorized by remember { mutableStateOf(existing.filter.includeUncategorized) }
    var excludeUncategorized by remember { mutableStateOf(existing.filter.excludeUncategorized) }
    var includeUntagged by remember { mutableStateOf(existing.filter.includeUntagged) }
    var excludeUntagged by remember { mutableStateOf(existing.filter.excludeUntagged) }
    var error by remember { mutableStateOf<String?>(null) }
    DesktopDialogSurface("Изменить фильтр", onDismiss, wide = true) {
        ModernField("Название", name, { name = it })
        ModernCheckGroup("Включить активности", activities.map { it.id to it.name }, includedActivities) { includedActivities = it }
        ModernCheckGroup("Исключить активности", activities.map { it.id to it.name }, excludedActivities) { excludedActivities = it }
        ModernCheckGroup("Включить категории", categories.map { it.id to it.name }, includedCategories) { includedCategories = it }
        ModernCheckGroup("Исключить категории", categories.map { it.id to it.name }, excludedCategories) { excludedCategories = it }
        ModernBooleanOption("Включить без категории", includeUncategorized) { includeUncategorized = it }
        ModernBooleanOption("Исключить без категории", excludeUncategorized) { excludeUncategorized = it }
        ModernCheckGroup("Включить теги", tags.map { it.id to it.name }, includedTags) { includedTags = it }
        ModernCheckGroup("Исключить теги", tags.map { it.id to it.name }, excludedTags) { excludedTags = it }
        ModernBooleanOption("Включить без тегов", includeUntagged) { includeUntagged = it }
        ModernBooleanOption("Исключить без тегов", excludeUntagged) { excludeUntagged = it }
        error?.let { ModernFormError(it) }
        DesktopDialogActions(onDismiss, "Сохранить", {
            val filter = DesktopRecordFilter(
                includedActivityIds = includedActivities,
                excludedActivityIds = excludedActivities,
                includedCategoryIds = includedCategories,
                excludedCategoryIds = excludedCategories,
                includeUncategorized = includeUncategorized,
                excludeUncategorized = excludeUncategorized,
                includedTagIds = includedTags,
                excludedTagIds = excludedTags,
                includeUntagged = includeUntagged,
                excludeUntagged = excludeUntagged,
            )
            when (service.save(existing.id, name, filter).first) {
                DesktopSavedFilterResult.SAVED -> onSaved()
                DesktopSavedFilterResult.INVALID_NAME -> error = "Укажите название"
                DesktopSavedFilterResult.NAME_CONFLICT -> error = "Такой фильтр уже существует"
                DesktopSavedFilterResult.NOT_FOUND -> error = "Фильтр не найден"
            }
        })
    }
}

@Composable
internal fun ModernTimeSettingsDialog(
    preferences: DesktopSemanticPreferences,
    quickActions: DesktopQuickActions,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var shiftMinutes by remember { mutableStateOf((preferences.startOfDayShiftMillis / 60_000L).toString()) }
    var firstDay by remember { mutableStateOf(preferences.firstDayOfWeek) }
    var ignoreShortSeconds by remember { mutableStateOf(quickActions.ignoreShortRecordsDurationSeconds.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    DesktopDialogSurface("Время и недели", onDismiss) {
        Text("Эти настройки изменяют границы пользовательских дней и недель во всей истории и статистике.", style = MaterialTheme.typography.body2, color = DesktopUiTokens.SecondaryText)
        ModernField("Начало дня, минут от полуночи", shiftMinutes, { shiftMinutes = it }, "0")
        ModernSelector("Первый день недели", DayOfWeek.entries.toList(), firstDay, { it.value }, { dayOfWeekTitle(it) }) { firstDay = it }
        ModernField("Игнорировать записи короче, сек.", ignoreShortSeconds, { ignoreShortSeconds = it }, "0 — сохранять все")
        error?.let { ModernFormError(it) }
        DesktopDialogActions(onDismiss, "Сохранить", {
            val minutes = shiftMinutes.toLongOrNull()
            val seconds = ignoreShortSeconds.toLongOrNull()
            if (minutes == null || minutes !in -1439L..1439L || seconds == null || seconds < 0) {
                error = "Укажите начало дня от −1439 до 1439 минут и неотрицательный порог"
            } else {
                preferences.startOfDayShiftMillis = minutes * 60_000L
                preferences.firstDayOfWeek = firstDay
                quickActions.setIgnoreShortRecordsDurationSeconds(seconds)
                onSaved()
            }
        })
    }
}

private fun dayOfWeekTitle(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "Понедельник"
    DayOfWeek.TUESDAY -> "Вторник"
    DayOfWeek.WEDNESDAY -> "Среда"
    DayOfWeek.THURSDAY -> "Четверг"
    DayOfWeek.FRIDAY -> "Пятница"
    DayOfWeek.SATURDAY -> "Суббота"
    DayOfWeek.SUNDAY -> "Воскресенье"
}
