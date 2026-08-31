package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPreferencesTest {
    @Test
    fun allowMultitaskingDefaultsToAndroidDefault() {
        val file = Files.createTempDirectory("desktop-preferences-test").resolve("preferences")
        assertTrue(FileDesktopSemanticPreferences(file).allowMultitasking)
    }

    @Test
    fun allowMultitaskingPersistsBetweenInstances() {
        val file = Files.createTempDirectory("desktop-preferences-test").resolve("preferences")
        FileDesktopSemanticPreferences(file).allowMultitasking = false
        assertFalse(FileDesktopSemanticPreferences(file).allowMultitasking)
    }

    @Test
    fun corruptPreferenceFallsBackToAndroidDefault() {
        val file = Files.createTempDirectory("desktop-preferences-test").resolve("preferences")
        Files.writeString(file, "allowMultitasking=definitely\n")
        assertTrue(FileDesktopSemanticPreferences(file).allowMultitasking)
    }

    @Test
    fun ignoreShortRecordsDefaultsToAndroidDisabledAndPersists() {
        val file = Files.createTempDirectory("desktop-preferences-test").resolve("preferences")
        val first = FileDesktopSemanticPreferences(file)

        assertTrue(first.ignoreShortRecordsDurationSeconds == 0L)
        first.ignoreShortRecordsDurationSeconds = 3

        assertTrue(FileDesktopSemanticPreferences(file).ignoreShortRecordsDurationSeconds == 3L)
    }

    @Test
    fun corruptShortRecordsPreferenceFallsBackToDisabled() {
        val file = Files.createTempDirectory("desktop-preferences-test").resolve("preferences")
        Files.writeString(file, "ignoreShortRecordsDurationSeconds=negative\n")

        assertTrue(FileDesktopSemanticPreferences(file).ignoreShortRecordsDurationSeconds == 0L)
    }

    @Test
    fun timePreferencesDefaultToAndroidCompatibleValuesAndPersist() {
        val file = Files.createTempDirectory("desktop-preferences-test").resolve("preferences")
        val first = FileDesktopSemanticPreferences(file)

        assertEquals(0L, first.startOfDayShiftMillis)
        assertEquals(FileDesktopSemanticPreferences.DEFAULT_FIRST_DAY_OF_WEEK, first.firstDayOfWeek)
        first.startOfDayShiftMillis = -90 * 60_000L
        first.firstDayOfWeek = DayOfWeek.SUNDAY

        val reopened = FileDesktopSemanticPreferences(file)
        assertEquals(-90 * 60_000L, reopened.startOfDayShiftMillis)
        assertEquals(DayOfWeek.SUNDAY, reopened.firstDayOfWeek)
    }

    @Test
    fun corruptTimePreferencesFallBackSafely() {
        val file = Files.createTempDirectory("desktop-preferences-test").resolve("preferences")
        Files.writeString(file, "startOfDayShiftMillis=bad\nfirstDayOfWeek=notaday\n")

        val preferences = FileDesktopSemanticPreferences(file)
        assertEquals(0L, preferences.startOfDayShiftMillis)
        assertEquals(FileDesktopSemanticPreferences.DEFAULT_FIRST_DAY_OF_WEEK, preferences.firstDayOfWeek)
    }

    @Test
    fun timeShiftIsBoundedToAndroidSettingsRange() {
        val file = Files.createTempDirectory("desktop-preferences-test").resolve("preferences")
        FileDesktopSemanticPreferences(file).startOfDayShiftMillis = Long.MAX_VALUE

        assertEquals(
            FileDesktopSemanticPreferences.MAX_START_OF_DAY_SHIFT_MILLIS,
            FileDesktopSemanticPreferences(file).startOfDayShiftMillis,
        )
    }
}
