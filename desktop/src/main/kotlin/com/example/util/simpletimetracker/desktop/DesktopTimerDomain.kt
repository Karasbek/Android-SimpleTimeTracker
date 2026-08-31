package com.example.util.simpletimetracker.desktop

data class DesktopRunningRecord(
    val activityId: Long,
    val startedAt: Long,
    val comment: String,
    val tagId: Long,
)

interface DesktopTimerRepository {
    fun activities(): List<ActivityRow>
    fun runningRecords(): List<DesktopRunningRecord>
    fun addRunningRecord(record: DesktopRunningRecord): Boolean
    fun completeRunningRecord(activityId: Long, endedAt: Long): Boolean
    fun previousCompletedActivityId(): Long?
}

enum class TimerActionResult {
    STARTED,
    STOPPED,
    ALREADY_RUNNING,
    NOT_RUNNING,
    ACTIVITY_UNAVAILABLE,
}

class DesktopTimerService(
    private val repository: DesktopTimerRepository,
    private val preferences: DesktopSemanticPreferences,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {

    val allowMultitasking: Boolean
        get() = preferences.allowMultitasking

    fun setAllowMultitasking(value: Boolean) {
        preferences.allowMultitasking = value
    }

    @Synchronized
    fun toggle(activityId: Long): TimerActionResult {
        return if (repository.runningRecords().any { it.activityId == activityId }) {
            stop(activityId)
        } else {
            start(activityId)
        }
    }

    @Synchronized
    fun start(activityId: Long): TimerActionResult {
        if (repository.activities().none { it.id == activityId }) {
            return TimerActionResult.ACTIVITY_UNAVAILABLE
        }
        if (repository.runningRecords().any { it.activityId == activityId }) {
            return TimerActionResult.ALREADY_RUNNING
        }

        val timestamp = currentTimeMillis()
        if (!preferences.allowMultitasking) {
            repository.runningRecords()
                .filter { it.activityId != activityId }
                .forEach { repository.completeRunningRecord(it.activityId, timestamp) }
        }

        return if (
            repository.addRunningRecord(
                DesktopRunningRecord(
                    activityId = activityId,
                    startedAt = timestamp,
                    comment = "",
                    tagId = 0,
                ),
            )
        ) {
            TimerActionResult.STARTED
        } else {
            TimerActionResult.ACTIVITY_UNAVAILABLE
        }
    }

    @Synchronized
    fun stop(activityId: Long): TimerActionResult {
        val running = repository.runningRecords().firstOrNull { it.activityId == activityId }
            ?: return TimerActionResult.NOT_RUNNING
        val endedAt = currentTimeMillis().coerceAtLeast(running.startedAt)
        return if (repository.completeRunningRecord(activityId, endedAt)) {
            TimerActionResult.STOPPED
        } else {
            TimerActionResult.NOT_RUNNING
        }
    }
}
