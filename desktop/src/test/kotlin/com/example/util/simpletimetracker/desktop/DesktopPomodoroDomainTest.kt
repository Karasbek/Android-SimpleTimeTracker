package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPomodoroDomainTest {
    @Test
    fun focusBreakAndLongBreakFollowAndroidFullPeriodCadence() {
        var now = 100L
        val service = service { now }.also { it.updateConfiguration(DesktopPomodoroConfig(10, 5, 15, 2)) }
        service.start()
        assertEquals(DesktopPomodoroPhase.FOCUS, service.snapshot().phase)
        now = 110; assertEquals(DesktopPomodoroPhase.BREAK, service.snapshot().phase)
        now = 115; assertEquals(DesktopPomodoroPhase.FOCUS, service.snapshot().phase)
        now = 125; assertEquals(DesktopPomodoroPhase.LONG_BREAK, service.snapshot().phase)
        now = 140; assertEquals(DesktopPomodoroPhase.FOCUS, service.snapshot().phase)
    }

    @Test
    fun pauseResumeResetAndSkipUsePersistedWallClockState() {
        var now = 100L
        val service = service { now }.also { it.updateConfiguration(DesktopPomodoroConfig(10, 5, 0, 0)) }
        service.start(); now = 105; service.pause(); now = 200
        assertEquals(DesktopPomodoroRunState.PAUSED, service.snapshot().runState)
        assertEquals(5, service.snapshot().remainingMillis)
        service.resume(); assertEquals(DesktopPomodoroRunState.RUNNING, service.snapshot().runState)
        service.reset(); assertEquals(10, service.snapshot().remainingMillis)
        service.skip(); assertTrue(service.snapshot().phaseElapsedMillis >= 9)
        service.stop(); assertEquals(DesktopPomodoroRunState.STOPPED, service.snapshot().runState)
    }

    @Test
    fun configurationAndRunningOrPausedRuntimeSurviveReopen() {
        val directory = Files.createTempDirectory("desktop-pomodoro")
        var now = 1_000L
        val configs = DesktopPomodoroConfigStore(directory.resolve("semantic")); val runtime = DesktopPomodoroRuntimeStore(directory.resolve("runtime"))
        val first = DesktopPomodoroService(configs, runtime) { now }
        first.updateConfiguration(DesktopPomodoroConfig(20, 3, 7, 3)); first.start(); now = 1_010
        assertEquals(10, DesktopPomodoroService(configs, runtime) { now }.snapshot().remainingMillis)
        first.pause(); now = 2_000
        assertEquals(DesktopPomodoroRunState.PAUSED, DesktopPomodoroService(configs, runtime) { now }.snapshot().runState)
        assertEquals(20, DesktopPomodoroService(configs, runtime) { now }.configuration().focusMillis)
    }

    private fun service(clock: () -> Long): DesktopPomodoroService {
        val directory = Files.createTempDirectory("desktop-pomodoro")
        return DesktopPomodoroService(DesktopPomodoroConfigStore(directory.resolve("semantic")), DesktopPomodoroRuntimeStore(directory.resolve("runtime")), clock)
    }
}
