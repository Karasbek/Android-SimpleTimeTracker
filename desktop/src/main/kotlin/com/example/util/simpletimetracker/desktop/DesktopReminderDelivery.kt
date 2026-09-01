package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.ZoneId
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface DesktopNotificationAdapter { fun show(title: String, text: String): Boolean }
object DesktopLinuxNotificationAdapter : DesktopNotificationAdapter {
    override fun show(title: String, text: String): Boolean = runCatching { ProcessBuilder("notify-send", title, text).start(); true }.getOrElse { error -> System.err.println("Reminder notification launch failed: ${error.javaClass.simpleName}"); false }
}

/** Device-local delivery journal: product definitions stay in SQLite; emitted occurrences do not replay on restart. */
class DesktopReminderDeliveryJournal(private val file: Path = defaultReminderJournalPath()) {
    @Synchronized fun wasDelivered(key: String) = load().containsKey(key)
    @Synchronized fun markDelivered(key: String) { load().apply { setProperty(key, "1") }.also(::save) }
    @Synchronized fun removePrefix(prefix: String) { load().apply { stringPropertyNames().filter { it.startsWith(prefix) }.forEach(::remove) }.also(::save) }
    private fun load() = Properties().also { if (Files.isRegularFile(file)) runCatching { Files.newInputStream(file).use(it::load) } }
    private fun save(value: Properties) { Files.createDirectories(file.parent); val temp = Files.createTempFile(file.parent, "reminder-delivery-", ".tmp"); try { Files.newOutputStream(temp).use { value.store(it, null) }; Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING) } finally { Files.deleteIfExists(temp) } }
}

class DesktopReminderDeliveryService(
    private val database: DesktopDatabase,
    private val timeService: DesktopTimeService,
    private val notifier: DesktopNotificationAdapter = DesktopLinuxNotificationAdapter,
    private val journal: DesktopReminderDeliveryJournal = DesktopReminderDeliveryJournal(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Conservative recovery window: never replay a backlog after long offline/suspend time. */
    fun tick(now: Long = clock()) {
        database.scheduledReminders().filter(DesktopScheduledReminder::enabled).forEach { reminder ->
            runCatching { deliverScheduled(reminder, now) }.onFailure { error -> System.err.println("Reminder tick failed for scheduled id=${reminder.id}: ${error.javaClass.simpleName}") }
        }
        database.runningRecords().forEach { running ->
            runCatching { deliverActivity(running, now) }.onFailure { error -> System.err.println("Reminder tick failed for running id=${running.activityId}: ${error.javaClass.simpleName}") }
        }
    }
    private fun deliverScheduled(reminder: DesktopScheduledReminder, now: Long) {
        val occurrence = DesktopReminderOccurrenceCalculator.next(reminder.schedule, now - DELIVERY_GRACE_MILLIS, zone, catchUpOverdueOneTime = true) ?: return
        if (occurrence.expectedAt > now || now - occurrence.expectedAt > DELIVERY_GRACE_MILLIS) return
        val key = "scheduled:${reminder.id}:${occurrence.expectedAt}"
        if (journal.wasDelivered(key) || !DesktopAutomationService(database).shouldDeliver(reminder, timeService.currentDay())) return
        if (notifier.show("Simple Time Tracker", reminder.text.ifBlank { "Напоминание" })) journal.markDelivered(key)
    }
    private fun deliverActivity(running: DesktopRunningRecord, now: Long) {
        val override = database.activityReminderOverride(running.activityId)
        if (!DesktopActivityReminderPolicy.isDue(running.startedAt, now, override, zone)) return
        val custom = override as? DesktopActivityReminderOverride.Custom ?: return
        val elapsed = (now - running.startedAt).coerceAtLeast(0)
        val ordinal = if (custom.recurrent) elapsed / (custom.durationSeconds * 1_000L) else 1L
        val key = "activity:${running.activityId}:${running.startedAt}:$ordinal"
        if (journal.wasDelivered(key)) return
        val activity = database.activities().firstOrNull { it.id == running.activityId } ?: return
        if (notifier.show("Simple Time Tracker", "${activity.name}: напоминание о текущей активности")) journal.markDelivered(key)
    }
    fun refresh() = tick()
    companion object { const val DELIVERY_GRACE_MILLIS = 5 * 60_000L }
}

/** Lifecycle-owned background adapter; it never touches Compose/EDT and is stopped on normal Exit. */
class DesktopReminderBackgroundAdapter(private val delivery: DesktopReminderDeliveryService) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "reminder-background").apply { isDaemon = true } }
    private val started = AtomicBoolean(false)
    fun start() { if (started.compareAndSet(false, true)) executor.scheduleAtFixedRate({ runCatching { delivery.tick() }.onFailure { error -> System.err.println("Reminder scheduler tick failed: ${error.javaClass.simpleName}") } }, 1, 15, TimeUnit.SECONDS) }
    fun tickNow() = delivery.tick()
    override fun close() { executor.shutdownNow() }
}

private fun defaultReminderJournalPath(): Path = Paths.get(System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank) ?: "${System.getProperty("user.home")}/.config").resolve("simple-time-tracker/reminder-delivery.properties")
