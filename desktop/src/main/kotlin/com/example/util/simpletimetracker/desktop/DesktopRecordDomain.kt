package com.example.util.simpletimetracker.desktop

data class DesktopRecordDraft(
    val activityId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val comment: String,
    val tags: List<DesktopRecordTag> = emptyList(),
)

interface DesktopRecordRepository {
    fun activities(): List<ActivityRow>
    fun tags(): List<DesktopTag>
    fun addCompletedRecordWithTags(
        activityId: Long,
        startedAt: Long,
        endedAt: Long,
        comment: String,
        tags: List<DesktopRecordTag>,
    ): Boolean
    fun updateCompletedRecord(
        recordId: Long,
        activityId: Long,
        startedAt: Long,
        endedAt: Long,
        comment: String,
        tags: List<DesktopRecordTag>,
    ): Boolean
    fun deleteCompletedRecord(recordId: Long): Boolean
}

enum class RecordWriteResult {
    SAVED,
    ACTIVITY_UNAVAILABLE,
    TAG_UNAVAILABLE,
    INVALID_TAG_VALUE,
    RECORD_MISSING,
}

class DesktopRecordService(
    private val repository: DesktopRecordRepository,
) {
    fun create(draft: DesktopRecordDraft): RecordWriteResult {
        val normalized = normalize(draft)
        if (repository.activities().none { it.id == normalized.activityId }) {
            return RecordWriteResult.ACTIVITY_UNAVAILABLE
        }
        validateTags(normalized.tags)?.let { return it }
        return if (
            repository.addCompletedRecordWithTags(
                activityId = normalized.activityId,
                startedAt = normalized.startedAt,
                endedAt = normalized.endedAt,
                comment = normalized.comment,
                tags = normalized.tags,
            )
        ) {
            RecordWriteResult.SAVED
        } else {
            RecordWriteResult.ACTIVITY_UNAVAILABLE
        }
    }

    fun update(recordId: Long, draft: DesktopRecordDraft): RecordWriteResult {
        val normalized = normalize(draft)
        if (repository.activities().none { it.id == normalized.activityId }) {
            return RecordWriteResult.ACTIVITY_UNAVAILABLE
        }
        validateTags(normalized.tags)?.let { return it }
        return if (
            repository.updateCompletedRecord(
                recordId = recordId,
                activityId = normalized.activityId,
                startedAt = normalized.startedAt,
                endedAt = normalized.endedAt,
                comment = normalized.comment,
                tags = normalized.tags,
            )
        ) {
            RecordWriteResult.SAVED
        } else {
            RecordWriteResult.RECORD_MISSING
        }
    }

    fun delete(recordId: Long): RecordWriteResult =
        if (repository.deleteCompletedRecord(recordId)) {
            RecordWriteResult.SAVED
        } else {
            RecordWriteResult.RECORD_MISSING
        }

    private fun normalize(draft: DesktopRecordDraft): DesktopRecordDraft = draft.copy(
        endedAt = draft.endedAt.coerceAtLeast(draft.startedAt),
    )

    private fun validateTags(tags: List<DesktopRecordTag>): RecordWriteResult? {
        if (tags.map(DesktopRecordTag::tagId).distinct().size != tags.size) {
            return RecordWriteResult.INVALID_TAG_VALUE
        }
        val available = repository.tags().associateBy(DesktopTag::id)
        tags.forEach { recordTag ->
            val tag = available[recordTag.tagId]
                ?: return RecordWriteResult.TAG_UNAVAILABLE
            if (tag.archived) return RecordWriteResult.TAG_UNAVAILABLE
            if (tag.valueType == DesktopTagValueType.NONE && recordTag.numericValue != null) {
                return RecordWriteResult.INVALID_TAG_VALUE
            }
        }
        return null
    }
}
