package com.example.util.simpletimetracker.desktop

import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField

fun editDesktopActivityDialog(
    activity: ActivityRow,
    categories: List<DesktopCategory>,
    tags: List<DesktopTag>,
    selectedCategoryIds: Set<Long>,
    selectedAllowedTagIds: Set<Long>,
    selectedDefaultTagIds: Set<Long>,
): DesktopActivityDetailsDraft? {
    val nameField = JTextField(activity.name)
    val durationField = JTextField(activity.defaultDurationSeconds.toString())
    val categoryBoxes = categories.associateWith { category -> JCheckBox(category.name, category.id in selectedCategoryIds) }
    val activeTags = tags.filterNot(DesktopTag::archived)
    val allowedTagBoxes = activeTags.associateWith { tag -> JCheckBox(tag.name, tag.id in selectedAllowedTagIds) }
    val defaultTagBoxes = activeTags.associateWith { tag -> JCheckBox(tag.name, tag.id in selectedDefaultTagIds) }
    val panel = desktopFormPanel()
    panel.add(JLabel("Название"))
    panel.add(nameField)
    panel.add(JLabel("Длительность мгновенной записи в секундах (0 — обычный таймер)"))
    panel.add(durationField)
    panel.add(JLabel("Категории"))
    categoryBoxes.values.forEach(panel::add)
    panel.add(JLabel("Доступные теги"))
    allowedTagBoxes.values.forEach(panel::add)
    panel.add(JLabel("Теги по умолчанию при старте"))
    defaultTagBoxes.values.forEach(panel::add)
    if (showDesktopConfirmDialog(panel, "Изменить активность") != JOptionPane.OK_OPTION
    ) return null
    val duration = durationField.text.trim().toLongOrNull()
    if (nameField.text.isBlank() || duration == null || duration < 0) {
        JOptionPane.showMessageDialog(null, "Укажите название и неотрицательную длительность")
        return null
    }
    return DesktopActivityDetailsDraft(
        name = nameField.text,
        defaultDurationSeconds = duration,
        categoryIds = categoryBoxes.filterValues(JCheckBox::isSelected).keys.mapTo(mutableSetOf(), DesktopCategory::id),
        allowedTagIds = allowedTagBoxes.filterValues(JCheckBox::isSelected).keys.mapTo(mutableSetOf(), DesktopTag::id),
        defaultTagIds = defaultTagBoxes.filterValues(JCheckBox::isSelected).keys.mapTo(mutableSetOf(), DesktopTag::id),
    )
}

fun selectDesktopRecordTagsDialog(
    tags: List<DesktopTag>,
    selected: List<DesktopRecordTag>,
): List<DesktopRecordTag>? {
    val selectedById = selected.associateBy(DesktopRecordTag::tagId)
    val checkBoxes = tags.associateWith { tag -> JCheckBox(tag.name, tag.id in selectedById) }
    val valueFields = tags.filter { it.valueType == DesktopTagValueType.NUMERIC }.associateWith { tag ->
        JTextField(selectedById[tag.id]?.numericValue?.let(::formatDesktopTagValue).orEmpty())
    }
    val panel = desktopFormPanel()
    tags.forEach { tag ->
        panel.add(checkBoxes.getValue(tag))
        valueFields[tag]?.let { field ->
            panel.add(JLabel("Значение ${tag.valueSuffix}".trim()))
            panel.add(field)
        }
    }
    if (showDesktopConfirmDialog(panel, "Теги записи") != JOptionPane.OK_OPTION
    ) return null
    return buildList {
        tags.forEach { tag ->
            if (!checkBoxes.getValue(tag).isSelected) return@forEach
            val value = valueFields[tag]?.text?.trim()
            if (tag.valueType == DesktopTagValueType.NUMERIC && value.isNullOrEmpty()) {
                JOptionPane.showMessageDialog(null, "Для тега ${tag.name} укажите числовое значение")
                return null
            }
            val numericValue = value?.toDoubleOrNull()
            if (tag.valueType == DesktopTagValueType.NUMERIC && numericValue == null) {
                JOptionPane.showMessageDialog(null, "Значение тега ${tag.name} должно быть числом")
                return null
            }
            add(DesktopRecordTag(tag.id, numericValue))
        }
    }
}

fun manageDesktopTagsDialog(service: DesktopTagCategoryService): Boolean {
    val tags = service.tags()
    val choices = tags.map(::tagTitle) + "＋ Создать тег"
    val selected = JOptionPane.showInputDialog(
        null,
        "Теги",
        "Управление тегами",
        JOptionPane.PLAIN_MESSAGE,
        null,
        choices.toTypedArray(),
        choices.last(),
    ) as? String ?: return false
    if (selected == choices.last()) {
        val draft = editDesktopTagDialog(null) ?: return false
        return showTaxonomyResult(service.saveTag(draft = draft).first)
    }
    val tag = tags.firstOrNull { tagTitle(it) == selected } ?: return false
    return when (
        JOptionPane.showOptionDialog(
            null,
            tag.name,
            "Тег",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE,
            null,
            arrayOf("Изменить", if (tag.archived) "Восстановить" else "Архивировать", "Удалить", "Отмена"),
            "Изменить",
        )
    ) {
        0 -> editDesktopTagDialog(tag)?.let { showTaxonomyResult(service.saveTag(tag.id, it).first) } ?: false
        1 -> showTaxonomyResult(if (tag.archived) service.restoreTag(tag.id) else service.archiveTag(tag.id))
        2 -> showTaxonomyResult(service.deleteTag(tag.id))
        else -> false
    }
}

fun manageDesktopCategoriesDialog(service: DesktopTagCategoryService): Boolean {
    val categories = service.categories()
    val choices = categories.map(DesktopCategory::name) + "＋ Создать категорию"
    val selected = JOptionPane.showInputDialog(
        null,
        "Категории",
        "Управление категориями",
        JOptionPane.PLAIN_MESSAGE,
        null,
        choices.toTypedArray(),
        choices.last(),
    ) as? String ?: return false
    if (selected == choices.last()) {
        val draft = editDesktopCategoryDialog(null) ?: return false
        return showTaxonomyResult(service.saveCategory(draft = draft).first)
    }
    val category = categories.firstOrNull { it.name == selected } ?: return false
    return when (
        JOptionPane.showOptionDialog(
            null,
            category.name,
            "Категория",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE,
            null,
            arrayOf("Изменить", "Удалить", "Отмена"),
            "Изменить",
        )
    ) {
        0 -> editDesktopCategoryDialog(category)?.let { showTaxonomyResult(service.saveCategory(category.id, it).first) } ?: false
        1 -> showTaxonomyResult(service.deleteCategory(category.id))
        else -> false
    }
}

private fun editDesktopTagDialog(tag: DesktopTag?): DesktopTagDraft? {
    val nameField = JTextField(tag?.name.orEmpty())
    val typeBox = JComboBox(DesktopTagValueType.entries.toTypedArray())
    typeBox.selectedItem = tag?.valueType ?: DesktopTagValueType.NONE
    val suffixField = JTextField(tag?.valueSuffix.orEmpty())
    val panel = desktopFormPanel()
    panel.add(JLabel("Название"))
    panel.add(nameField)
    panel.add(JLabel("Тип значения"))
    panel.add(typeBox)
    panel.add(JLabel("Суффикс числового значения"))
    panel.add(suffixField)
    if (showDesktopConfirmDialog(panel, if (tag == null) "Создать тег" else "Изменить тег") != JOptionPane.OK_OPTION
    ) return null
    return DesktopTagDraft(
        name = nameField.text,
        valueType = typeBox.selectedItem as DesktopTagValueType,
        valueSuffix = suffixField.text,
    )
}

private fun editDesktopCategoryDialog(category: DesktopCategory?): DesktopCategoryDraft? {
    val nameField = JTextField(category?.name.orEmpty())
    val panel = desktopFormPanel().apply { add(nameField) }
    if (showDesktopConfirmDialog(panel, if (category == null) "Создать категорию" else "Изменить категорию") != JOptionPane.OK_OPTION
    ) return null
    return DesktopCategoryDraft(nameField.text)
}

private fun tagTitle(tag: DesktopTag): String =
    buildString {
        append(tag.name)
        if (tag.valueType == DesktopTagValueType.NUMERIC) append(" (числовой)")
        if (tag.archived) append(" [архив]")
    }

fun formatDesktopTagValue(value: Double): String =
    value.toBigDecimal().stripTrailingZeros().toPlainString()

private fun showTaxonomyResult(result: DesktopTaxonomyWriteResult): Boolean = when (result) {
    DesktopTaxonomyWriteResult.SAVED -> true
    DesktopTaxonomyWriteResult.NAME_CONFLICT -> {
        JOptionPane.showMessageDialog(null, "Такое название уже существует")
        false
    }
    DesktopTaxonomyWriteResult.INVALID_NAME -> {
        JOptionPane.showMessageDialog(null, "Укажите название")
        false
    }
    DesktopTaxonomyWriteResult.INVALID_RELATION -> {
        JOptionPane.showMessageDialog(null, "Выбрана недоступная связь")
        false
    }
    DesktopTaxonomyWriteResult.NOT_FOUND -> {
        JOptionPane.showMessageDialog(null, "Данные не найдены")
        false
    }
}
