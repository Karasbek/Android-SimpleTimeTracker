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
    fun addCompletedRecord(
        activityId: Long,
        startedAt: Long,
        endedAt: Long,
        comment: String,
        tagId: Long,
    ): Boolean
    fun completeRunningRecord(activityId: Long, endedAt: Long): Boolean
    fun discardRunningRecord(activityId: Long): Boolean
    fun previousCompletedActivityId(): Long?
}

enum class TimerActionResult {
    STARTED,
    COMPLETED,
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

    val ignoreShortRecordsDurationSeconds: Long
        get() = preferences.ignoreShortRecordsDurationSeconds

    fun setIgnoreShortRecordsDurationSeconds(value: Long) {
        preferences.ignoreShortRecordsDurationSeconds = value.coerceAtLeast(0)
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
        val activity = repository.activities().firstOrNull { it.id == activityId }
        if (activity == null) {
            return TimerActionResult.ACTIVITY_UNAVAILABLE
        }
        if (repository.runningRecords().any { it.activityId == activityId }) {
            return TimerActionResult.ALREADY_RUNNING
        }

        val timestamp = currentTimeMillis()
        if (!preferences.allowMultitasking) {
            repository.runningRecords()
                .filter { it.activityId != activityId }
                .forEach { stopAt(it, timestamp) }
        }

        if (activity.defaultDurationSeconds > 0) {
            val endedAt = timestamp + activity.defaultDurationSeconds * 1000L
            return if (
                repository.addCompletedRecord(activityId, timestamp, endedAt, "", 0)
            ) {
                TimerActionResult.COMPLETED
            } else {
                TimerActionResult.ACTIVITY_UNAVAILABLE
            }
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
        return if (stopAt(running, endedAt)) {
            TimerActionResult.STOPPED
        } else {
            TimerActionResult.NOT_RUNNING
        }
    }

    private fun stopAt(running: DesktopRunningRecord, endedAt: Long): Boolean {
        val actualEndedAt = endedAt.coerceAtLeast(running.startedAt)
        val durationSeconds = (actualEndedAt - running.startedAt) / 1000L
        val ignoreDuration = preferences.ignoreShortRecordsDurationSeconds
        return if (ignoreDuration == 0L || durationSeconds > ignoreDuration) {
            repository.completeRunningRecord(running.activityId, actualEndedAt)
        } else {
            repository.discardRunningRecord(running.activityId)
        }
    }
}
