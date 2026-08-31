package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopRecordServiceTest {
    @Test
    fun manualCreateAndEditPersistCommentActivityAndTimesAcrossReopen() {
        val database = temporaryDatabase()
        database.addActivity("Focus")
        database.addActivity("Break")
        val ids = database.activities().associate { it.name to it.id }
        val service = DesktopRecordService(database)

        assertEquals(
            RecordWriteResult.SAVED,
            service.create(DesktopRecordDraft(ids.getValue("Focus"), 100, 200, "  first comment  ")),
        )
        val created = storedRecords(database).single()
        assertEquals("  first comment  ", created.comment)
        val recordId = created.id

        assertEquals(
            RecordWriteResult.SAVED,
            service.update(
                recordId,
                DesktopRecordDraft(ids.getValue("Break"), 300, 700, "updated comment"),
            ),
        )

        assertEquals(
            listOf(StoredRecord(recordId, ids.getValue("Break"), 300, 700, "updated comment")),
            storedRecords(DesktopDatabase(database.path)),
        )
    }

    @Test
    fun emptyCommentCanBeClearedAndEndBeforeStartIsNormalizedLikeAndroidEditor() {
        val database = temporaryDatabase()
        database.addActivity("Focus")
        val id = database.activities().single().id
        val service = DesktopRecordService(database)

        assertEquals(
            RecordWriteResult.SAVED,
            service.create(DesktopRecordDraft(id, 1_000, 500, "comment")),
        )
        val recordId = storedRecords(database).single().id
        assertEquals(
            RecordWriteResult.SAVED,
            service.update(recordId, DesktopRecordDraft(id, 1_000, 1_000, "")),
        )

        assertEquals(
            StoredRecord(recordId, id, 1_000, 1_000, ""),
            storedRecords(database).single(),
        )
    }

    @Test
    fun futureTimestampsAndManualRecordsForDefaultDurationActivityAreKeptAsEntered() {
        val database = temporaryDatabase()
        database.addActivity("Preset")
        val id = database.activities().single().id
        database.setActivityDefaultDuration(id, 90)
        val futureStart = System.currentTimeMillis() + 86_400_000
        val futureEnd = futureStart + 12_345

        assertEquals(
            RecordWriteResult.SAVED,
            DesktopRecordService(database).create(
                DesktopRecordDraft(id, futureStart, futureEnd, "manual future"),
            ),
        )
        val created = storedRecords(database).single()

        assertEquals(
            StoredRecord(created.id, id, futureStart, futureEnd, "manual future"),
            created,
        )
    }

    @Test
    fun unavailableActivityAndMissingRecordAreRejected() {
        val database = temporaryDatabase()
        database.addActivity("Available")
        database.addActivity("Archived")
        val ids = database.activities().associate { it.name to it.id }
        val service = DesktopRecordService(database)
        val availableId = ids.getValue("Available")
        val archivedId = ids.getValue("Archived")
        assertEquals(
            RecordWriteResult.SAVED,
            service.create(DesktopRecordDraft(availableId, 100, 200, "saved")),
        )
        val recordId = storedRecords(database).single().id
        database.archiveActivity(archivedId)

        assertEquals(
            RecordWriteResult.ACTIVITY_UNAVAILABLE,
            service.create(DesktopRecordDraft(archivedId, 300, 400, "blocked")),
        )
        assertEquals(
            RecordWriteResult.ACTIVITY_UNAVAILABLE,
            service.update(recordId, DesktopRecordDraft(archivedId, 300, 400, "blocked")),
        )
        assertEquals(
            RecordWriteResult.RECORD_MISSING,
            service.update(Long.MAX_VALUE, DesktopRecordDraft(availableId, 300, 400, "missing")),
        )
    }

    @Test
    fun historyImmediatelyUsesEditedActivityTimeAndComment() {
        val database = temporaryDatabase()
        database.addActivity("Before")
        database.addActivity("After")
        val ids = database.activities().associate { it.name to it.id }
        val date = LocalDate.now()
        val startOfDay = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val service = DesktopRecordService(database)
        assertEquals(
            RecordWriteResult.SAVED,
            service.create(DesktopRecordDraft(ids.getValue("Before"), startOfDay + 1_000, startOfDay + 2_000, "before")),
        )
        val recordId = storedRecords(database).single().id

        assertEquals(
            RecordWriteResult.SAVED,
            service.update(
                recordId,
                DesktopRecordDraft(ids.getValue("After"), startOfDay + 3_000, startOfDay + 9_000, "after"),
            ),
        )

        assertEquals(
            listOf(
                DayRecordRow(
                    id = recordId,
                    activityId = ids.getValue("After"),
                    activityName = "After",
                    startedAt = startOfDay + 3_000,
                    endedAt = startOfDay + 9_000,
                    comment = "after",
                ),
            ),
            database.historyForDate(date),
        )
    }

    @Test
    fun deleteUsesTheSameRecordServiceResultContract() {
        val database = temporaryDatabase()
        database.addActivity("Focus")
        val id = database.activities().single().id
        val service = DesktopRecordService(database)
        assertEquals(RecordWriteResult.SAVED, service.create(DesktopRecordDraft(id, 1, 2, "delete me")))
        val recordId = storedRecords(database).single().id

        assertEquals(RecordWriteResult.SAVED, service.delete(recordId))
        assertEquals(emptyList(), storedRecords(database))
        assertEquals(RecordWriteResult.RECORD_MISSING, service.delete(recordId))
    }

    @Test
    fun activityEditorPersistenceUpdatesNameAndDefaultDurationTogether() {
        val database = temporaryDatabase()
        database.addActivity("Before")
        val id = database.activities().single().id

        database.updateActivity(id, "After", 75)

        assertEquals(
            listOf(ActivityRow(id, "After", null, 75)),
            DesktopDatabase(database.path).activities(),
        )
    }

    private fun temporaryDatabase(): DesktopDatabase = DesktopDatabase(
        Files.createTempDirectory("desktop-record-service-test").resolve("tracker.sqlite3"),
    )

    private fun storedRecords(database: DesktopDatabase): List<StoredRecord> =
        DriverManager.getConnection("jdbc:sqlite:${database.path}").use { connection ->
            connection.prepareStatement(
                "SELECT id, type_id, time_started, time_ended, comment FROM records ORDER BY id",
            ).use { query ->
                query.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                StoredRecord(
                                    id = result.getLong("id"),
                                    activityId = result.getLong("type_id"),
                                    startedAt = result.getLong("time_started"),
                                    endedAt = result.getLong("time_ended"),
                                    comment = result.getString("comment"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private data class StoredRecord(
        val id: Long,
        val activityId: Long,
        val startedAt: Long,
        val endedAt: Long,
        val comment: String,
    )
}
