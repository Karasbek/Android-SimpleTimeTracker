package com.example.util.simpletimetracker.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopTimerServiceTest {
    @Test
    fun multitaskingEnabledKeepsAAndBRunning() {
        val repository = Repository(activityRows(1, 2))
        val service = DesktopTimerService(repository, MemoryPreferences(true), clock(100, 200))
        service.start(1)
        service.start(2)
        assertEquals(setOf(1L, 2L), repository.runningRecords().map { it.activityId }.toSet())
        assertEquals(emptyList(), repository.completed)
    }

    @Test
    fun multitaskingDisabledStopsAllPreviousAtNewStartTime() {
        val repository = Repository(activityRows(1, 2, 3))
        val service = DesktopTimerService(repository, MemoryPreferences(true), clock(100, 150, 200))
        service.start(1)
        service.start(2)
        service.setAllowMultitasking(false)
        service.start(3)
        assertEquals(listOf(3L), repository.runningRecords().map { it.activityId })
        assertEquals(listOf(1L to 200L, 2L to 200L), repository.completed)
    }

    @Test
    fun toggleRunningActivityStopsAndCreatesCompletedRecord() {
        val repository = Repository(activityRows(1))
        val service = DesktopTimerService(repository, MemoryPreferences(true), clock(100, 250))
        assertEquals(TimerActionResult.STARTED, service.toggle(1))
        assertEquals(TimerActionResult.STOPPED, service.toggle(1))
        assertEquals(listOf(1L to 250L), repository.completed)
        assertEquals(emptyList(), repository.runningRecords())
    }

    @Test
    fun archiveOrMissingActivityIsRejected() {
        val repository = Repository(activityRows(1))
        val service = DesktopTimerService(repository, MemoryPreferences(true), clock(100))
        assertEquals(TimerActionResult.ACTIVITY_UNAVAILABLE, service.start(2))
        assertEquals(emptyList(), repository.runningRecords())
    }

    private fun activityRows(vararg ids: Long) = ids.map { ActivityRow(it, "Activity $it", null) }

    private class Repository(private val active: List<ActivityRow>) : DesktopTimerRepository {
        private val running = linkedMapOf<Long, DesktopRunningRecord>()
        val completed = mutableListOf<Pair<Long, Long>>()

        override fun activities() = active.map { it.copy(startedAt = running[it.id]?.startedAt) }
        override fun runningRecords() = running.values.toList()
        override fun addRunningRecord(record: DesktopRunningRecord): Boolean {
            if (active.none { it.id == record.activityId } || record.activityId in running) return false
            running[record.activityId] = record
            return true
        }
        override fun addCompletedRecord(
            activityId: Long,
            startedAt: Long,
            endedAt: Long,
            comment: String,
            tagId: Long,
        ): Boolean {
            if (active.none { it.id == activityId }) return false
            completed += activityId to endedAt
            return true
        }
        override fun completeRunningRecord(activityId: Long, endedAt: Long): Boolean {
            if (running.remove(activityId) == null) return false
            completed += activityId to endedAt
            return true
        }
        override fun discardRunningRecord(activityId: Long): Boolean = running.remove(activityId) != null
        override fun previousCompletedActivityId(): Long? = completed.lastOrNull()?.first
    }
}
