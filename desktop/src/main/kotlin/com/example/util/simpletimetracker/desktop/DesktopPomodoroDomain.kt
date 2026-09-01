package com.example.util.simpletimetracker.desktop

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class DesktopPomodoroPhase { FOCUS, BREAK, LONG_BREAK }
enum class DesktopPomodoroRunState { STOPPED, RUNNING, PAUSED }

data class DesktopPomodoroConfig(
    val focusMillis: Long = 25 * 60_000L,
    val breakMillis: Long = 5 * 60_000L,
    val longBreakMillis: Long = 15 * 60_000L,
    val periodsUntilLongBreak: Long = 4,
)

data class DesktopPomodoroRuntime(val startedAt: Long = 0, val pausedAt: Long = 0)
data class DesktopPomodoroSnapshot(
    val runState: DesktopPomodoroRunState,
    val phase: DesktopPomodoroPhase,
    val phaseElapsedMillis: Long,
    val phaseDurationMillis: Long,
    val remainingMillis: Long,
)

class DesktopPomodoroConfigStore(private val file: Path = pomodoroConfigPath()) {
    @Synchronized fun load(): DesktopPomodoroConfig = properties().let { values ->
        DesktopPomodoroConfig(
            focusMillis = values.long("focus", 25 * 60_000L).coerceAtLeast(1),
            breakMillis = values.long("break", 5 * 60_000L).coerceAtLeast(0),
            longBreakMillis = values.long("longBreak", 15 * 60_000L).coerceAtLeast(0),
            periodsUntilLongBreak = values.long("periods", 4).coerceAtLeast(0),
        )
    }
    @Synchronized fun save(config: DesktopPomodoroConfig) = write(Properties().apply {
        setProperty("focus", config.focusMillis.coerceAtLeast(1).toString()); setProperty("break", config.breakMillis.coerceAtLeast(0).toString()); setProperty("longBreak", config.longBreakMillis.coerceAtLeast(0).toString()); setProperty("periods", config.periodsUntilLongBreak.coerceAtLeast(0).toString())
    })
    private fun properties() = Properties().also { if (Files.isRegularFile(file)) runCatching { Files.newInputStream(file).use(it::load) } }
    private fun Properties.long(key: String, default: Long) = getProperty(key)?.toLongOrNull() ?: default
    private fun write(values: Properties) { Files.createDirectories(file.parent); val temp = Files.createTempFile(file.parent, "pomodoro-", ".tmp"); try { Files.newOutputStream(temp).use { values.store(it, null) }; try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) } catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) } } finally { Files.deleteIfExists(temp) } }
}

class DesktopPomodoroRuntimeStore(private val file: Path = pomodoroRuntimePath()) {
    @Synchronized fun load(): DesktopPomodoroRuntime = properties().let { DesktopPomodoroRuntime(it.long("started"), it.long("paused")) }
    @Synchronized fun save(value: DesktopPomodoroRuntime) = write(Properties().apply { setProperty("started", value.startedAt.toString()); setProperty("paused", value.pausedAt.toString()) })
    private fun properties() = Properties().also { if (Files.isRegularFile(file)) runCatching { Files.newInputStream(file).use(it::load) } }
    private fun Properties.long(key: String) = getProperty(key)?.toLongOrNull() ?: 0L
    private fun write(values: Properties) { Files.createDirectories(file.parent); val temp = Files.createTempFile(file.parent, "pomodoro-runtime-", ".tmp"); try { Files.newOutputStream(temp).use { values.store(it, null) }; try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) } catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) } } finally { Files.deleteIfExists(temp) } }
}

class DesktopPomodoroService(
    private val configs: DesktopPomodoroConfigStore = DesktopPomodoroConfigStore(),
    private val runtime: DesktopPomodoroRuntimeStore = DesktopPomodoroRuntimeStore(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun configuration(): DesktopPomodoroConfig = configs.load()
    fun updateConfiguration(config: DesktopPomodoroConfig) = configs.save(config)
    fun snapshot(): DesktopPomodoroSnapshot = snapshot(now())
    fun snapshot(current: Long): DesktopPomodoroSnapshot {
        val state = runtime.load(); val run = when { state.startedAt == 0L -> DesktopPomodoroRunState.STOPPED; state.pausedAt != 0L -> DesktopPomodoroRunState.PAUSED; else -> DesktopPomodoroRunState.RUNNING }
        val effectiveNow = if (run == DesktopPomodoroRunState.PAUSED) state.pausedAt else current
        return map(run, (effectiveNow - state.startedAt).coerceAtLeast(0), configuration())
    }
    @Synchronized fun start() { runtime.save(DesktopPomodoroRuntime(now(), 0)) }
    @Synchronized fun pause() { val state = runtime.load(); if (state.startedAt != 0L && state.pausedAt == 0L) runtime.save(state.copy(pausedAt = now())) }
    @Synchronized fun resume() { val state = runtime.load(); if (state.pausedAt != 0L) runtime.save(DesktopPomodoroRuntime(state.startedAt + (now() - state.pausedAt).coerceAtLeast(0), 0)) }
    @Synchronized fun reset() { val state = runtime.load(); if (state.startedAt != 0L && state.pausedAt == 0L) runtime.save(state.copy(startedAt = state.startedAt + snapshot().phaseElapsedMillis)) }
    @Synchronized fun skip() { val state = runtime.load(); if (state.startedAt != 0L && state.pausedAt == 0L) runtime.save(state.copy(startedAt = state.startedAt + snapshot().phaseElapsedMillis - snapshot().phaseDurationMillis + 1)) }
    @Synchronized fun stop() { runtime.save(DesktopPomodoroRuntime()) }

    private fun map(run: DesktopPomodoroRunState, elapsed: Long, config: DesktopPomodoroConfig): DesktopPomodoroSnapshot {
        if (run == DesktopPomodoroRunState.STOPPED) return DesktopPomodoroSnapshot(run, DesktopPomodoroPhase.FOCUS, 0, config.focusMillis, config.focusMillis)
        val period = fullPeriod(config)
        if (period <= 0) return DesktopPomodoroSnapshot(run, DesktopPomodoroPhase.FOCUS, 0, config.focusMillis, config.focusMillis)
        val inPeriod = elapsed % period
        val focusBreak = config.focusMillis + config.breakMillis
        val longStarts = period - config.longBreakMillis
        val (phase, used, duration) = when {
            config.periodsUntilLongBreak > 0 && config.longBreakMillis > 0 && inPeriod >= longStarts -> Triple(DesktopPomodoroPhase.LONG_BREAK, inPeriod - longStarts, config.longBreakMillis)
            focusBreak > 0 && inPeriod % focusBreak < config.focusMillis -> Triple(DesktopPomodoroPhase.FOCUS, inPeriod % focusBreak, config.focusMillis)
            config.breakMillis > 0 -> Triple(DesktopPomodoroPhase.BREAK, inPeriod % focusBreak - config.focusMillis, config.breakMillis)
            else -> Triple(DesktopPomodoroPhase.FOCUS, inPeriod % config.focusMillis, config.focusMillis)
        }
        return DesktopPomodoroSnapshot(run, phase, used.coerceAtLeast(0), duration, (duration - used).coerceAtLeast(0))
    }
    private fun fullPeriod(c: DesktopPomodoroConfig) = if (c.periodsUntilLongBreak > 0) c.focusMillis * c.periodsUntilLongBreak + c.breakMillis * (c.periodsUntilLongBreak - 1) + c.longBreakMillis else c.focusMillis + c.breakMillis
}

fun interface DesktopPomodoroNotifier { fun phaseChanged(snapshot: DesktopPomodoroSnapshot) }
object DesktopLinuxPomodoroNotifier : DesktopPomodoroNotifier { override fun phaseChanged(snapshot: DesktopPomodoroSnapshot) { runCatching { ProcessBuilder("notify-send", "Simple Time Tracker", "Pomodoro: ${snapshot.phase.readable()}").start() } } }

/** In-process Linux adapter: wall-clock state persists, so hide/restart needs no foreground service. */
class DesktopPomodoroBackgroundAdapter(private val service: DesktopPomodoroService, private val notifier: DesktopPomodoroNotifier = DesktopLinuxPomodoroNotifier) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "pomodoro-background").apply { isDaemon = true } }
    private var previous: DesktopPomodoroSnapshot? = null
    fun start() { previous = service.snapshot(); executor.scheduleAtFixedRate({ val next = service.snapshot(); if (next.runState == DesktopPomodoroRunState.RUNNING && previous?.phase != next.phase) notifier.phaseChanged(next); previous = next }, 1, 1, TimeUnit.SECONDS) }
    override fun close() { executor.shutdownNow() }
}

internal fun DesktopPomodoroPhase.readable() = when (this) { DesktopPomodoroPhase.FOCUS -> "Фокус"; DesktopPomodoroPhase.BREAK -> "Перерыв"; DesktopPomodoroPhase.LONG_BREAK -> "Длинный перерыв" }
private fun pomodoroConfigPath(): Path = Paths.get(System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank) ?: "${System.getProperty("user.home")}/.local/share").resolve("simple-time-tracker/pomodoro-semantic.properties")
private fun pomodoroRuntimePath(): Path = Paths.get(System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank) ?: "${System.getProperty("user.home")}/.config").resolve("simple-time-tracker/pomodoro-runtime.properties")
