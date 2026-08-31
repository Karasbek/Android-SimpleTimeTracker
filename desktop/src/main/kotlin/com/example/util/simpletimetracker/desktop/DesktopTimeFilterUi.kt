package com.example.util.simpletimetracker.desktop

import java.time.DayOfWeek
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JTextField

fun editDesktopTimeSettingsDialog(preferences: DesktopSemanticPreferences): Boolean {
    val shiftMinutes = JTextField((preferences.startOfDayShiftMillis / 60_000L).toString())
    val firstDay = JComboBox(DayOfWeek.entries.toTypedArray()).apply { selectedItem = preferences.firstDayOfWeek }
    val panel = desktopFormPanel().apply {
        add(JLabel("Начало пользовательского дня, минут после полуночи"))
        add(shiftMinutes)
        add(JLabel("Первый день недели"))
        add(firstDay)
    }
    if (showDesktopConfirmDialog(panel, "Время и недели") != JOptionPane.OK_OPTION) return false
    val minutes = shiftMinutes.text.trim().toLongOrNull()?.takeIf {
        it in -1_439L..1_439L
    } ?: run {
        JOptionPane.showMessageDialog(null, "Укажите число минут от -1439 до 1439")
        return false
    }
    preferences.startOfDayShiftMillis = minutes * 60_000L
    preferences.firstDayOfWeek = firstDay.selectedItem as DayOfWeek
    return true
}

fun editDesktopRecordFilterDialog(
    initial: DesktopRecordFilter,
    activities: List<ActivityRow>,
    tags: List<DesktopTag>,
    categories: List<DesktopCategory>,
): DesktopRecordFilter? {
    val includedActivities = activities.associateWith { JCheckBox(it.name, it.id in initial.includedActivityIds) }
    val excludedActivities = activities.associateWith { JCheckBox(it.name, it.id in initial.excludedActivityIds) }
    val includedTags = tags.associateWith { JCheckBox(it.name, it.id in initial.includedTagIds) }
    val excludedTags = tags.associateWith { JCheckBox(it.name, it.id in initial.excludedTagIds) }
    val includedCategories = categories.associateWith { JCheckBox(it.name, it.id in initial.includedCategoryIds) }
    val excludedCategories = categories.associateWith { JCheckBox(it.name, it.id in initial.excludedCategoryIds) }
    val includeUncategorized = JCheckBox("Без категории", initial.includeUncategorized)
    val excludeUncategorized = JCheckBox("Исключить без категории", initial.excludeUncategorized)
    val includeUntagged = JCheckBox("Без тегов", initial.includeUntagged)
    val excludeUntagged = JCheckBox("Исключить без тегов", initial.excludeUntagged)
    val panel = desktopFormPanel().apply {
        add(JLabel("Включить активности (любая из отмеченных)"))
        includedActivities.values.forEach(::add)
        add(JLabel("Исключить активности"))
        excludedActivities.values.forEach(::add)
        add(JLabel("Включить категории (добавляются к выбранным активностям)"))
        includedCategories.values.forEach(::add)
        add(includeUncategorized)
        add(JLabel("Исключить категории"))
        excludedCategories.values.forEach(::add)
        add(excludeUncategorized)
        add(JLabel("Включить теги (любой из отмеченных)"))
        includedTags.values.forEach(::add)
        add(includeUntagged)
        add(JLabel("Исключить теги"))
        excludedTags.values.forEach(::add)
        add(excludeUntagged)
    }
    if (showDesktopConfirmDialog(panel, "Фильтр записей") != JOptionPane.OK_OPTION) return null
    return DesktopRecordFilter(
        includedActivityIds = includedActivities.filterValues(JCheckBox::isSelected).keys.mapTo(mutableSetOf(), ActivityRow::id),
        excludedActivityIds = excludedActivities.filterValues(JCheckBox::isSelected).keys.mapTo(mutableSetOf(), ActivityRow::id),
        includedCategoryIds = includedCategories.filterValues(JCheckBox::isSelected).keys.mapTo(mutableSetOf(), DesktopCategory::id),
        excludedCategoryIds = excludedCategories.filterValues(JCheckBox::isSelected).keys.mapTo(mutableSetOf(), DesktopCategory::id),
        includeUncategorized = includeUncategorized.isSelected,
        excludeUncategorized = excludeUncategorized.isSelected,
        includedTagIds = includedTags.filterValues(JCheckBox::isSelected).keys.mapTo(mutableSetOf(), DesktopTag::id),
        excludedTagIds = excludedTags.filterValues(JCheckBox::isSelected).keys.mapTo(mutableSetOf(), DesktopTag::id),
        includeUntagged = includeUntagged.isSelected,
        excludeUntagged = excludeUntagged.isSelected,
    )
}

fun askDesktopSavedFilterName(current: String = ""): String? {
    val name = JTextField(current)
    val panel = desktopFormPanel().apply {
        add(JLabel("Название сохранённого фильтра"))
        add(name)
    }
    return if (showDesktopConfirmDialog(panel, "Сохранить фильтр") == JOptionPane.OK_OPTION) name.text else null
}

fun chooseDesktopSavedFilterDialog(filters: List<DesktopSavedRecordFilter>): DesktopSavedRecordFilter? {
    if (filters.isEmpty()) {
        JOptionPane.showMessageDialog(null, "Сохранённых фильтров нет")
        return null
    }
    val box = JComboBox(filters.map(DesktopSavedRecordFilter::name).toTypedArray())
    val panel = desktopFormPanel().apply {
        add(JLabel("Сохранённый фильтр"))
        add(box)
    }
    if (showDesktopConfirmDialog(panel, "Сохранённые фильтры") != JOptionPane.OK_OPTION) return null
    return filters[box.selectedIndex]
}
