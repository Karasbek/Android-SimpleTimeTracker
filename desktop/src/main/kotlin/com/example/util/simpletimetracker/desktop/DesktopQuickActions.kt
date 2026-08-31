package com.example.util.simpletimetracker.desktop

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList

interface DesktopTimerRepository {
    fun activities(): List<ActivityRow>
    fun toggle(activityId: Long)
    fun previousCompletedActivityId(): Long?
}

data class TrayActivity(
    val id: Long,
    val name: String,
    val startedAt: Long?,
)

data class DesktopTrayState(
    val running: List<TrayActivity>,
    val pinned: List<TrayActivity>,
    val canRepeatPrevious: Boolean,
)

enum class RepeatPreviousResult {
    STARTED,
    NO_PREVIOUS,
    ALREADY_RUNNING,
}

class DesktopPinnedActivitiesStore(
    private val file: Path = defaultPinnedActivitiesPath(),
) {
    @Synchronized
    fun load(): Set<Long> {
        if (!Files.isRegularFile(file)) return emptySet()
        return Files.readString(file)
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0L }
            .toSet()
    }

    @Synchronized
    fun save(ids: Set<Long>) {
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "pinned-", ".tmp")
        try {
            Files.writeString(temporary, ids.filter { it > 0L }.sorted().joinToString(","))
            try {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

class DesktopQuickActions(
    private val repository: DesktopTimerRepository,
    private val pinnedStore: DesktopPinnedActivitiesStore,
) {
    private val listeners = CopyOnWriteArrayList<(DesktopTrayState) -> Unit>()

    @Volatile
    var state: DesktopTrayState = buildState()
        private set

    fun addListener(listener: (DesktopTrayState) -> Unit): AutoCloseable {
        listeners += listener
        listener(state)
        return AutoCloseable { listeners -= listener }
    }

    @Synchronized
    fun refresh() {
        publish(buildState())
    }

    @Synchronized
    fun toggle(activityId: Long) {
        if (repository.activities().none { it.id == activityId }) {
            publish(buildState())
            return
        }
        repository.toggle(activityId)
        publish(buildState())
    }

    @Synchronized
    fun setPinned(activityId: Long, pinned: Boolean) {
        val activeIds = repository.activities().mapTo(mutableSetOf(), ActivityRow::id)
        val updated = pinnedStore.load().toMutableSet()
        if (pinned && activityId in activeIds) updated += activityId else updated -= activityId
        pinnedStore.save(updated)
        publish(buildState())
    }

    @Synchronized
    fun repeatPrevious(): RepeatPreviousResult {
        val activityId = repository.previousCompletedActivityId()
            ?: return RepeatPreviousResult.NO_PREVIOUS
        val activity = repository.activities().firstOrNull { it.id == activityId }
            ?: return RepeatPreviousResult.NO_PREVIOUS
        if (activity.startedAt != null) return RepeatPreviousResult.ALREADY_RUNNING

        repository.toggle(activityId)
        publish(buildState())
        return RepeatPreviousResult.STARTED
    }

    private fun buildState(): DesktopTrayState {
        val activities = repository.activities()
        val byId = activities.associateBy(ActivityRow::id)
        val storedIds = pinnedStore.load()
        val validPinnedIds = storedIds.filterTo(mutableSetOf()) { it in byId }
        if (validPinnedIds != storedIds) pinnedStore.save(validPinnedIds)

        fun ActivityRow.toTrayActivity() = TrayActivity(id, name, startedAt)

        return DesktopTrayState(
            running = activities.filter { it.startedAt != null }.map { it.toTrayActivity() },
            pinned = validPinnedIds.mapNotNull(byId::get).sortedBy { it.name.lowercase() }
                .map { it.toTrayActivity() },
            canRepeatPrevious = repository.previousCompletedActivityId() in byId,
        )
    }

    private fun publish(newState: DesktopTrayState) {
        state = newState
        listeners.forEach { it(newState) }
    }
}

private fun defaultPinnedActivitiesPath(): Path {
    val configHome = System.getenv("XDG_CONFIG_HOME")
        ?.takeIf { it.isNotBlank() }
        ?.let(Paths::get)
        ?: Paths.get(System.getProperty("user.home"), ".config")
    return configHome.resolve("simple-time-tracker").resolve("desktop-pinned-activities")
}
