package com.example.util.simpletimetracker.desktop

/** Record-list actions are kept out of Compose and use transactional database operations. */
class DesktopRecordActionsService(
    private val database: DesktopDatabase,
) {
    fun split(recordId: Long, splitAt: Long, afterActivityId: Long): RecordWriteResult =
        if (database.splitCompletedRecord(recordId, splitAt, afterActivityId)) RecordWriteResult.SAVED
        else RecordWriteResult.RECORD_MISSING
}
