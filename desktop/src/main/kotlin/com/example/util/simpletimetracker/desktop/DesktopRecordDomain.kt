package com.example.util.simpletimetracker.desktop

data class DesktopRecordDraft(
    val activityId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val comment: String,
)

interface DesktopRecordRepository {
    fun activities(): List<ActivityRow>
    fun addCompletedRecord(
        activityId: Long,
        startedAt: Long,
        endedAt: Long,
        comment: String,
        tagId: Long,
    ): Boolean
    fun updateCompletedRecord(
        recordId: Long,
        activityId: Long,
        startedAt: Long,
        endedAt: Long,
        comment: String,
    ): Boolean
    fun deleteCompletedRecord(recordId: Long): Boolean
}

enum class RecordWriteResult {
    SAVED,
    ACTIVITY_UNAVAILABLE,
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
        return if (
            repository.addCompletedRecord(
                activityId = normalized.activityId,
                startedAt = normalized.startedAt,
                endedAt = normalized.endedAt,
                comment = normalized.comment,
                tagId = 0,
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
        return if (
            repository.updateCompletedRecord(
                recordId = recordId,
                activityId = normalized.activityId,
                startedAt = normalized.startedAt,
                endedAt = normalized.endedAt,
                comment = normalized.comment,
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
}
