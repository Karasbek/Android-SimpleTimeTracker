package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopRunningRecordServiceTest {
    @Test
    fun runningEditorMovesActivityAndKeepsCommentAndNumericTagsAtomically() {
        val database = DesktopDatabase(Files.createTempDirectory("desktop-running-edit").resolve("tracker.sqlite3"))
        database.addActivity("A")
        database.addActivity("B")
        val ids = database.activities().associateBy(ActivityRow::name)
        val tag = DesktopTagCategoryService(database)
            .saveTag(draft = DesktopTagDraft("Value", DesktopTagValueType.NUMERIC, "kg")).second!!
        DesktopActivityEditorService(database).update(
            ids.getValue("B").id,
            DesktopActivityDetailsDraft("B", 0, emptySet(), setOf(tag), emptySet()),
        )
        assertTrue(database.addRunningRecord(DesktopRunningRecord(ids.getValue("A").id, 100, "old", 0)))

        assertEquals(
            RecordWriteResult.SAVED,
            DesktopRunningRecordService(database).update(
                ids.getValue("A").id,
                ids.getValue("B").id,
                "kept",
                listOf(DesktopRecordTag(tag, 2.5)),
            ),
        )
        val running = database.runningRecords().single()
        assertEquals(ids.getValue("B").id, running.activityId)
        assertEquals(100L, running.startedAt)
        assertEquals("kept", running.comment)
        assertEquals(listOf(DesktopRecordTag(tag, 2.5)), running.tags)
    }
}
