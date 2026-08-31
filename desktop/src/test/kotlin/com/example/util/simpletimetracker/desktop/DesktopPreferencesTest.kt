package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import kotlin.test.Test
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
}
