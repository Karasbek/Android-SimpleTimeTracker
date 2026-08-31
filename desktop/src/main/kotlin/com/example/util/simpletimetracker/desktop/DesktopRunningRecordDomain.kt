package com.example.util.simpletimetracker.desktop

/** Domain entry point for the Records running-record editor; Compose never writes SQL directly. */
class DesktopRunningRecordService(
    private val database: DesktopDatabase,
) {
    fun update(
        currentActivityId: Long,
        targetActivityId: Long,
        comment: String,
        tags: List<DesktopRecordTag>,
    ): RecordWriteResult {
        if (database.activities().none { it.id == targetActivityId }) return RecordWriteResult.ACTIVITY_UNAVAILABLE
        val availableTags = database.tags().associateBy(DesktopTag::id)
        if (tags.map(DesktopRecordTag::tagId).distinct().size != tags.size ||
            tags.any { tag -> availableTags[tag.tagId]?.let { it.archived || (it.valueType == DesktopTagValueType.NONE && tag.numericValue != null) } != false }
        ) return RecordWriteResult.INVALID_TAG_VALUE
        return if (database.updateRunningRecord(currentActivityId, targetActivityId, comment, tags)) RecordWriteResult.SAVED
        else RecordWriteResult.RECORD_MISSING
    }
}
