package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopQuickActionsTest {

    @Test
    fun pinnedIdsPersistAndInvalidIdsAreRemoved() {
        val directory = Files.createTempDirectory("desktop-pins-test")
        val file = directory.resolve("pins")
        val store = DesktopPinnedActivitiesStore(file)
        store.save(setOf(1, 2, -3))

        val actions = DesktopQuickActions(
            FakeRepository(activityRows(1)),
            DesktopPinnedActivitiesStore(file),
        )

        assertEquals(listOf(1L), actions.state.pinned.map(TrayActivity::id))
        assertEquals(setOf(1L), DesktopPinnedActivitiesStore(file).load())
    }

    @Test
    fun pinnedClickUsesTheSameToggleCommandForStartAndStop() {
        val repository = FakeRepository(activityRows(1))
        val actions = actions(repository)

        actions.toggle(1)
        actions.toggle(1)

        assertEquals(listOf(1L, 1L), repository.toggleCalls)
        assertTrue(actions.state.running.isEmpty())
    }

    @Test
    fun repeatStartsPreviousButDoesNotToggleItWhenAlreadyRunning() {
        val repository = FakeRepository(activityRows(1), previousId = 1)
        val actions = actions(repository)

        assertEquals(RepeatPreviousResult.STARTED, actions.repeatPrevious())
        assertEquals(listOf(1L), repository.toggleCalls)
        assertEquals(RepeatPreviousResult.ALREADY_RUNNING, actions.repeatPrevious())
        assertEquals(listOf(1L), repository.toggleCalls)
    }

    @Test
    fun repeatIgnoresMissingOrArchivedPreviousActivity() {
        val repository = FakeRepository(activityRows(1), previousId = 2)
        val actions = actions(repository)

        assertEquals(RepeatPreviousResult.NO_PREVIOUS, actions.repeatPrevious())
        assertTrue(repository.toggleCalls.isEmpty())
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
        val actions = DesktopQuickActions(repository, store)

        assertEquals(listOf(1L, 2L), actions.state.running.map(TrayActivity::id))
        assertEquals(listOf(3L, 2L), actions.state.pinned.map(TrayActivity::id))

        repository.rows = listOf(
            ActivityRow(1, "Renamed", 100),
            ActivityRow(3, "Three", null),
        )
        actions.refresh()

        assertEquals(listOf("Renamed"), actions.state.running.map(TrayActivity::name))
        assertEquals(listOf(3L), actions.state.pinned.map(TrayActivity::id))
        assertEquals(setOf(3L), store.load())
    }

    private fun actions(repository: FakeRepository): DesktopQuickActions =
        DesktopQuickActions(repository, temporaryStore())

    private fun temporaryStore(): DesktopPinnedActivitiesStore =
        DesktopPinnedActivitiesStore(
            Files.createTempDirectory("desktop-pins-test").resolve("pins"),
        )

    private fun activityRows(vararg ids: Long): List<ActivityRow> =
        ids.map { ActivityRow(it, "Activity $it", null) }

    private class FakeRepository(
        initialRows: List<ActivityRow>,
        private val previousId: Long? = null,
    ) : DesktopTimerRepository {
        var rows = initialRows
        val toggleCalls = mutableListOf<Long>()

        override fun activities(): List<ActivityRow> = rows

        override fun toggle(activityId: Long) {
            toggleCalls += activityId
            rows = rows.map { row ->
                if (row.id == activityId) {
                    row.copy(startedAt = if (row.startedAt == null) 123L else null)
                } else {
                    row
                }
            }
        }

        override fun previousCompletedActivityId(): Long? = previousId
    }
}
