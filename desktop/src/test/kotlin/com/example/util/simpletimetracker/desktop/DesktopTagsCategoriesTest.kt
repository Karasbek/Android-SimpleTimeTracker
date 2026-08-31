package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopTagsCategoriesTest {
    @Test
    fun activityAndCategoryIdentityPersistWithoutChangingRelations() {
        val database = database()
        database.addActivity(" Work ")
        val activity = database.activities().single()
        val categoryId = DesktopTagCategoryService(database).saveCategory(
            draft = DesktopCategoryDraft("Projects", colorInt = "#336699", note = "visible group"),
        ).second!!
        val result = DesktopActivityEditorService(database).update(
            activity.id,
            DesktopActivityDetailsDraft(
                name = " Work ",
                defaultDurationSeconds = 0,
                categoryIds = setOf(categoryId),
                allowedTagIds = emptySet(),
                defaultTagIds = emptySet(),
                icon = "🧠",
                colorInt = "#AA2244",
                note = "identity note",
            ),
        )

        assertEquals(DesktopTaxonomyWriteResult.SAVED, result)
        val reopened = DesktopDatabase(database.path)
        assertEquals(" Work ", reopened.activities().single().name)
        assertEquals("🧠", reopened.activities().single().icon)
        assertEquals("#AA2244", reopened.activities().single().colorInt)
        assertEquals("identity note", reopened.activities().single().note)
        assertEquals(DesktopCategory(categoryId, "Projects", 0, "#336699", "visible group"), reopened.categories().single())
        assertEquals(setOf(categoryId), reopened.categoryIdsForActivity(activity.id))
    }

    @Test
    fun tagCreateRenameAndPersistenceFollowNameAndValueTypeSemantics() {
        val database = database()
        val service = DesktopTagCategoryService(database)
        val created = service.saveTag(
            draft = DesktopTagDraft("Weight", DesktopTagValueType.NUMERIC, "kg"),
        )
        val id = created.second!!

        assertEquals(DesktopTaxonomyWriteResult.SAVED, created.first)
        assertEquals(
            DesktopTaxonomyWriteResult.NAME_CONFLICT,
            service.saveTag(draft = DesktopTagDraft("Weight", DesktopTagValueType.NONE, "")).first,
        )
        assertEquals(
            DesktopTaxonomyWriteResult.SAVED,
            service.saveTag(id, DesktopTagDraft("Mass", DesktopTagValueType.NUMERIC, "kg")).first,
        )
        assertEquals(
            listOf(DesktopTag(id, "Mass", false, DesktopTagValueType.NUMERIC, "kg")),
            DesktopDatabase(database.path).tags(),
        )
    }

    @Test
    fun recordCreateAndUpdateReplaceTagRelationsAtomicallyWithNumericValues() {
        val database = database()
        database.addActivity("Focus")
        val activityId = database.activities().single().id
        val taxonomy = DesktopTagCategoryService(database)
        val plainId = taxonomy.saveTag(draft = DesktopTagDraft("Home", DesktopTagValueType.NONE, "")).second!!
        val numericId = taxonomy.saveTag(
            draft = DesktopTagDraft("Weight", DesktopTagValueType.NUMERIC, "kg"),
        ).second!!
        val records = DesktopRecordService(database)

        assertEquals(
            RecordWriteResult.SAVED,
            records.create(
                DesktopRecordDraft(
                    activityId = activityId,
                    startedAt = 100,
                    endedAt = 200,
                    comment = "tagged",
                    tags = listOf(DesktopRecordTag(plainId, null), DesktopRecordTag(numericId, -2.5)),
                ),
            ),
        )
        val recordId = recordId(database)
        assertEquals(
            listOf(
                DesktopRecordTagView(plainId, "Home", DesktopTagValueType.NONE, "", null),
                DesktopRecordTagView(numericId, "Weight", DesktopTagValueType.NUMERIC, "kg", -2.5),
            ),
            database.recordTagViews(recordId),
        )

        assertEquals(
            RecordWriteResult.SAVED,
            records.update(
                recordId,
                DesktopRecordDraft(activityId, 300, 500, "updated", listOf(DesktopRecordTag(numericId, 2.3))),
            ),
        )
        assertEquals(
            listOf(DesktopRecordTagView(numericId, "Weight", DesktopTagValueType.NUMERIC, "kg", 2.3)),
            database.recordTagViews(recordId),
        )
        assertEquals("updated", recordComment(database, recordId))
    }

    @Test
    fun invalidTagUpdateLeavesExistingRecordAndRelationsUntouched() {
        val database = database()
        database.addActivity("Focus")
        val activityId = database.activities().single().id
        val tagId = DesktopTagCategoryService(database)
            .saveTag(draft = DesktopTagDraft("Known", DesktopTagValueType.NONE, "")).second!!
        val records = DesktopRecordService(database)
        records.create(DesktopRecordDraft(activityId, 100, 200, "before", listOf(DesktopRecordTag(tagId, null))))
        val recordId = recordId(database)

        assertEquals(
            RecordWriteResult.TAG_UNAVAILABLE,
            records.update(
                recordId,
                DesktopRecordDraft(activityId, 300, 400, "must not save", listOf(DesktopRecordTag(999, null))),
            ),
        )
        assertEquals("before", recordComment(database, recordId))
        assertEquals(listOf(tagId), database.recordTagViews(recordId).map(DesktopRecordTagView::tagId))
    }

    @Test
    fun plainTagsRejectNumericValuesWhileNumericTagsKeepNegativeAndFractionalValues() {
        val database = database()
        database.addActivity("Focus")
        val activityId = database.activities().single().id
        val taxonomy = DesktopTagCategoryService(database)
        val plainTag = taxonomy.saveTag(draft = DesktopTagDraft("Plain", DesktopTagValueType.NONE, "")).second!!
        val numericTag = taxonomy.saveTag(draft = DesktopTagDraft("Amount", DesktopTagValueType.NUMERIC, "h")).second!!
        val records = DesktopRecordService(database)

        assertEquals(
            RecordWriteResult.INVALID_TAG_VALUE,
            records.create(DesktopRecordDraft(activityId, 100, 200, "", listOf(DesktopRecordTag(plainTag, 1.0)))),
        )
        assertEquals(
            RecordWriteResult.SAVED,
            records.create(DesktopRecordDraft(activityId, 100, 200, "", listOf(DesktopRecordTag(numericTag, -0.75)))),
        )
        assertEquals(-0.75, database.recordTagViews(recordId(database)).single().numericValue)
    }

    @Test
    fun archivePreservesHistoricalTagAndDeleteExplicitlyRemovesAllRelations() {
        val database = database()
        database.addActivity("Focus")
        val activityId = database.activities().single().id
        val taxonomy = DesktopTagCategoryService(database)
        val tagId = taxonomy.saveTag(draft = DesktopTagDraft("Keep", DesktopTagValueType.NONE, "")).second!!
        val records = DesktopRecordService(database)
        records.create(DesktopRecordDraft(activityId, 100, 200, "", listOf(DesktopRecordTag(tagId, null))))
        val recordId = recordId(database)

        assertEquals(DesktopTaxonomyWriteResult.SAVED, taxonomy.archiveTag(tagId))
        assertEquals(listOf(tagId), database.recordTagViews(recordId).map(DesktopRecordTagView::tagId))
        assertEquals(DesktopTaxonomyWriteResult.SAVED, taxonomy.deleteTag(tagId))
        assertTrue(database.recordTagViews(recordId).isEmpty())
        assertTrue(database.tags().isEmpty())
    }

    @Test
    fun categoriesAreManyToManyAndDeleteMakesActivitiesUncategorized() {
        val database = database()
        database.addActivity("Focus")
        val activity = database.activities().single()
        val taxonomy = DesktopTagCategoryService(database)
        val first = taxonomy.saveCategory(draft = DesktopCategoryDraft("Work")).second!!
        val second = taxonomy.saveCategory(draft = DesktopCategoryDraft("Client")).second!!
        val activityEditor = DesktopActivityEditorService(database)

        assertEquals(
            DesktopTaxonomyWriteResult.SAVED,
            activityEditor.update(
                activity.id,
                DesktopActivityDetailsDraft(activity.name, 0, setOf(first, second), emptySet(), emptySet()),
            ),
        )
        assertEquals(setOf(first, second), database.categoryIdsForActivity(activity.id))
        assertEquals(
            setOf(first, second),
            DesktopDatabase(database.path).categoryIdsForActivity(activity.id),
        )
        assertEquals(DesktopTaxonomyWriteResult.SAVED, taxonomy.deleteCategory(first))
        assertEquals(setOf(second), database.categoryIdsForActivity(activity.id))
        assertEquals(DesktopTaxonomyWriteResult.SAVED, taxonomy.deleteCategory(second))
        assertTrue(database.categoryIdsForActivity(activity.id).isEmpty())
    }

    @Test
    fun activityTagAssignmentsRestrictSelectionButUnassignedTagsRemainGlobal() {
        val database = database()
        database.addActivity("Focus")
        database.addActivity("Break")
        val (focus, breakActivity) = database.activities()
        val taxonomy = DesktopTagCategoryService(database)
        val global = taxonomy.saveTag(draft = DesktopTagDraft("Global", DesktopTagValueType.NONE, "")).second!!
        val focusOnly = taxonomy.saveTag(draft = DesktopTagDraft("Focus only", DesktopTagValueType.NONE, "")).second!!
        val editor = DesktopActivityEditorService(database)

        assertEquals(
            DesktopTaxonomyWriteResult.SAVED,
            editor.update(
                focus.id,
                DesktopActivityDetailsDraft(focus.name, 0, emptySet(), setOf(focusOnly), emptySet()),
            ),
        )

        assertEquals(setOf(global, focusOnly), database.selectableTagsForActivity(focus.id).map(DesktopTag::id).toSet())
        assertEquals(setOf(global), database.selectableTagsForActivity(breakActivity.id).map(DesktopTag::id).toSet())
    }

    @Test
    fun defaultTagsMoveThroughRunningStopAndRepeatUsesPreviousTagsAndComment() {
        val database = database()
        database.addActivity("Focus")
        val activity = database.activities().single()
        val taxonomy = DesktopTagCategoryService(database)
        val previousTag = taxonomy.saveTag(
            draft = DesktopTagDraft("Previous", DesktopTagValueType.NUMERIC, "kg"),
        ).second!!
        val defaultTag = taxonomy.saveTag(draft = DesktopTagDraft("Default", DesktopTagValueType.NONE, "")).second!!
        val activityEditor = DesktopActivityEditorService(database)
        activityEditor.update(
            activity.id,
            DesktopActivityDetailsDraft(activity.name, 0, emptySet(), setOf(previousTag), setOf(defaultTag)),
        )
        val recordService = DesktopRecordService(database)
        val startOfDay = today().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        recordService.create(
            DesktopRecordDraft(
                activity.id,
                startOfDay + 10,
                startOfDay + 20,
                "repeat comment",
                listOf(DesktopRecordTag(previousTag, -1.25)),
            ),
        )
        val timer = DesktopTimerService(database, MemoryPreferences(true), clock(startOfDay + 100, startOfDay + 200))
        val actions = DesktopQuickActions(
            database,
            timer,
            DesktopPinnedActivitiesStore(Files.createTempDirectory("desktop-tag-repeat").resolve("pins")),
        )

        assertEquals(RepeatPreviousResult.STARTED, actions.repeatPrevious())
        assertEquals("repeat comment", database.runningRecords().single().comment)
        assertEquals(
            setOf(DesktopRecordTag(previousTag, -1.25), DesktopRecordTag(defaultTag, null)),
            database.runningRecords().single().tags.toSet(),
        )
        assertEquals(TimerActionResult.STOPPED, timer.stop(activity.id))
        val latest = database.historyForDate(today()).maxBy { it.endedAt }
        assertEquals(
            setOf(previousTag, defaultTag),
            latest.tags.map(DesktopRecordTagView::tagId).toSet(),
        )
    }

    @Test
    fun relationTablesEnforceForeignKeys() {
        val database = database()
        DriverManager.getConnection("jdbc:sqlite:${database.path}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
            assertFailsWith<SQLException> {
                connection.createStatement().use { statement ->
                    statement.executeUpdate("INSERT INTO record_to_tag(record_id, tag_id, numeric_value) VALUES (1, 2, NULL)")
                }
            }
        }
    }

    private fun database(): DesktopDatabase = DesktopDatabase(
        Files.createTempDirectory("desktop-tags-categories-test").resolve("tracker.sqlite3"),
    )

    private fun recordId(database: DesktopDatabase): Long = DriverManager.getConnection("jdbc:sqlite:${database.path}").use { db ->
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT id FROM records ORDER BY id LIMIT 1").use { result ->
                result.next()
                result.getLong(1)
            }
        }
    }

    private fun recordComment(database: DesktopDatabase, recordId: Long): String =
        DriverManager.getConnection("jdbc:sqlite:${database.path}").use { db ->
            db.prepareStatement("SELECT comment FROM records WHERE id = ?").use { query ->
                query.setLong(1, recordId)
                query.executeQuery().use { result ->
                    result.next()
                    result.getString(1)
                }
            }
        }

    private fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())
}
