package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopQuickActionsTest {
    @Test
    fun pinnedIdsPersistAndInvalidIdsAreRemoved() {
        val file = Files.createTempDirectory("desktop-pins-test").resolve("pins")
        val store = DesktopPinnedActivitiesStore(file)
        store.save(setOf(1, 2, -3))
        val actions = actions(FakeRepository(activityRows(1)), store)

        assertEquals(listOf(1L), actions.state.pinned.map(TrayActivity::id))
        assertEquals(setOf(1L), DesktopPinnedActivitiesStore(file).load())
    }

    @Test
    fun pinnedClickUsesSharedTimerServiceForStartAndStop() {
        val repository = FakeRepository(activityRows(1))
        val actions = actions(repository)

        actions.toggle(1)
        actions.toggle(1)

        assertEquals(1, repository.startCalls)
        assertEquals(1, repository.completeCalls)
        assertTrue(actions.state.running.isEmpty())
    }

    @Test
    fun repeatUsesSharedStartPathAndDoesNotToggleRunningActivity() {
        val repository = FakeRepository(activityRows(1), previousId = 1)
        val actions = actions(repository)

        assertEquals(RepeatPreviousResult.STARTED, actions.repeatPrevious())
        assertEquals(1, repository.startCalls)
        assertEquals(RepeatPreviousResult.ALREADY_RUNNING, actions.repeatPrevious())
        assertEquals(1, repository.startCalls)
        assertEquals(0, repository.completeCalls)
    }

    @Test
    fun repeatIgnoresMissingOrArchivedPreviousActivity() {
        val repository = FakeRepository(activityRows(1), previousId = 2)
        val actions = actions(repository)

        assertEquals(RepeatPreviousResult.NO_PREVIOUS, actions.repeatPrevious())
        assertEquals(0, repository.startCalls)
        assertFalse(actions.state.canRepeatPrevious)
    }

    @Test
    fun trayStateContainsEveryRunningActivityAndReflectsRenameAndArchive() {
        val repository = FakeRepository(
            listOf(
                ActivityRow(1, "One", 100),
                ActivityRow(2, "Two", 200),
                ActivityRow(3, "Three", null),
            ),
        )
        val store = temporaryStore().also { it.save(setOf(2, 3)) }
        val actions = actions(repository, store)

        assertEquals(listOf(1L, 2L), actions.state.running.map(TrayActivity::id))
        assertEquals(listOf(3L, 2L), actions.state.pinned.map(TrayActivity::id))
        repository.rows = listOf(ActivityRow(1, "Renamed", 100), ActivityRow(3, "Three", null))
        actions.refresh()
        assertEquals(listOf("Renamed"), actions.state.running.map(TrayActivity::name))
        assertEquals(listOf(3L), actions.state.pinned.map(TrayActivity::id))
        assertEquals(setOf(3L), store.load())
    }

    private fun actions(
        repository: FakeRepository,
        store: DesktopPinnedActivitiesStore = temporaryStore(),
    ): DesktopQuickActions = DesktopQuickActions(
        repository,
        DesktopTimerService(repository, MemoryPreferences(true), clock(123, 456, 789)),
        store,
    )

    private fun temporaryStore() = DesktopPinnedActivitiesStore(
        Files.createTempDirectory("desktop-pins-test").resolve("pins"),
    )

    private fun activityRows(vararg ids: Long) = ids.map { ActivityRow(it, "Activity $it", null) }

    private class FakeRepository(
        initialRows: List<ActivityRow>,
        private val previousId: Long? = null,
    ) : DesktopTimerRepository {
        var rows = initialRows
        var startCalls = 0
        var completeCalls = 0

        override fun activities(): List<ActivityRow> = rows
        override fun runningRecords() = rows.mapNotNull { row ->
            row.startedAt?.let { DesktopRunningRecord(row.id, it, "", 0) }
        }

        override fun addRunningRecord(record: DesktopRunningRecord): Boolean {
            if (rows.none { it.id == record.activityId }) return false
            startCalls++
            rows = rows.map {
                if (it.id == record.activityId) it.copy(startedAt = record.startedAt) else it
            }
            return true
        }

        override fun completeRunningRecord(activityId: Long, endedAt: Long): Boolean {
            if (rows.none { it.id == activityId && it.startedAt != null }) return false
            completeCalls++
            rows = rows.map { if (it.id == activityId) it.copy(startedAt = null) else it }
            return true
        }

        override fun previousCompletedActivityId(): Long? = previousId
    }
}
