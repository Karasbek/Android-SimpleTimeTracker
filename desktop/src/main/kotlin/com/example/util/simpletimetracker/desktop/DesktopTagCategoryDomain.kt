package com.example.util.simpletimetracker.desktop

enum class DesktopTagValueType {
    NONE,
    NUMERIC,
}

data class DesktopTag(
    val id: Long,
    val name: String,
    val archived: Boolean,
    val valueType: DesktopTagValueType,
    val valueSuffix: String,
)

data class DesktopRecordTag(
    val tagId: Long,
    val numericValue: Double?,
)

data class DesktopRecordTagView(
    val tagId: Long,
    val name: String,
    val valueType: DesktopTagValueType,
    val valueSuffix: String,
    val numericValue: Double?,
)

data class DesktopCategory(
    val id: Long,
    val name: String,
)

data class DesktopTagDraft(
    val name: String,
    val valueType: DesktopTagValueType,
    val valueSuffix: String,
)

data class DesktopCategoryDraft(
    val name: String,
)

data class DesktopActivityDetailsDraft(
    val name: String,
    val defaultDurationSeconds: Long,
    val categoryIds: Set<Long>,
    val allowedTagIds: Set<Long>,
    val defaultTagIds: Set<Long>,
)

interface DesktopTagCategoryRepository {
    fun tags(): List<DesktopTag>
    fun categories(): List<DesktopCategory>
    fun saveTag(tagId: Long, draft: DesktopTagDraft): Long
    fun setTagArchived(tagId: Long, archived: Boolean): Boolean
    fun deleteTag(tagId: Long): Boolean
    fun saveCategory(categoryId: Long, draft: DesktopCategoryDraft): Long
    fun deleteCategory(categoryId: Long): Boolean
    fun categoryIdsForActivity(activityId: Long): Set<Long>
    fun allowedTagIdsForActivity(activityId: Long): Set<Long>
    fun defaultTagIdsForActivity(activityId: Long): Set<Long>
    fun updateActivityDetails(activityId: Long, draft: DesktopActivityDetailsDraft): Boolean
}

enum class DesktopTaxonomyWriteResult {
    SAVED,
    NAME_CONFLICT,
    INVALID_NAME,
    NOT_FOUND,
    INVALID_RELATION,
}

class DesktopTagCategoryService(
    private val repository: DesktopTagCategoryRepository,
) {
    fun tags(): List<DesktopTag> = repository.tags()

    fun categories(): List<DesktopCategory> = repository.categories()

    fun saveTag(tagId: Long = 0, draft: DesktopTagDraft): Pair<DesktopTaxonomyWriteResult, Long?> {
        val normalized = draft.copy(name = draft.name.trim())
        if (normalized.name.isEmpty()) return DesktopTaxonomyWriteResult.INVALID_NAME to null
        if (repository.tags().any { it.id != tagId && it.name == normalized.name }) {
            return DesktopTaxonomyWriteResult.NAME_CONFLICT to null
        }
        return DesktopTaxonomyWriteResult.SAVED to repository.saveTag(tagId, normalized)
    }

    fun archiveTag(tagId: Long): DesktopTaxonomyWriteResult =
        if (repository.setTagArchived(tagId, true)) DesktopTaxonomyWriteResult.SAVED
        else DesktopTaxonomyWriteResult.NOT_FOUND

    fun restoreTag(tagId: Long): DesktopTaxonomyWriteResult =
        if (repository.setTagArchived(tagId, false)) DesktopTaxonomyWriteResult.SAVED
        else DesktopTaxonomyWriteResult.NOT_FOUND

    fun deleteTag(tagId: Long): DesktopTaxonomyWriteResult =
        if (repository.deleteTag(tagId)) DesktopTaxonomyWriteResult.SAVED
        else DesktopTaxonomyWriteResult.NOT_FOUND

    fun saveCategory(
        categoryId: Long = 0,
        draft: DesktopCategoryDraft,
    ): Pair<DesktopTaxonomyWriteResult, Long?> {
        val normalized = draft.copy(name = draft.name.trim())
        if (normalized.name.isEmpty()) return DesktopTaxonomyWriteResult.INVALID_NAME to null
        if (repository.categories().any { it.id != categoryId && it.name == normalized.name }) {
            return DesktopTaxonomyWriteResult.NAME_CONFLICT to null
        }
        return DesktopTaxonomyWriteResult.SAVED to repository.saveCategory(categoryId, normalized)
    }

    fun deleteCategory(categoryId: Long): DesktopTaxonomyWriteResult =
        if (repository.deleteCategory(categoryId)) DesktopTaxonomyWriteResult.SAVED
        else DesktopTaxonomyWriteResult.NOT_FOUND
}

class DesktopActivityEditorService(
    private val repository: DesktopTagCategoryRepository,
) {
    fun update(activityId: Long, draft: DesktopActivityDetailsDraft): DesktopTaxonomyWriteResult {
        val normalized = draft.copy(name = draft.name.trim())
        if (normalized.name.isEmpty() || normalized.defaultDurationSeconds < 0) {
            return DesktopTaxonomyWriteResult.INVALID_NAME
        }
        if (repository.categories().map(DesktopCategory::id).containsAll(normalized.categoryIds).not()) {
            return DesktopTaxonomyWriteResult.INVALID_RELATION
        }
        val tags = repository.tags().associateBy(DesktopTag::id)
        val tagIds = normalized.allowedTagIds + normalized.defaultTagIds
        if (tagIds.any { tags[it]?.archived != false }) {
            return DesktopTaxonomyWriteResult.INVALID_RELATION
        }
        return if (repository.updateActivityDetails(activityId, normalized)) {
            DesktopTaxonomyWriteResult.SAVED
        } else {
            DesktopTaxonomyWriteResult.NOT_FOUND
        }
    }
}
