package com.example.util.simpletimetracker.desktop

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.time.DayOfWeek
import java.util.Calendar

interface DesktopSemanticPreferences {
    var allowMultitasking: Boolean
    var ignoreShortRecordsDurationSeconds: Long
    var startOfDayShiftMillis: Long
    var firstDayOfWeek: DayOfWeek
    /** Android's `ignoreShortUntrackedDuration`, in seconds; 0 disables the cutoff. */
    var ignoreShortUntrackedDurationSeconds: Long
    var showUntrackedInRecords: Boolean
}

class FileDesktopSemanticPreferences(
    private val file: Path = defaultSemanticPreferencesPath(),
) : DesktopSemanticPreferences {

    override var allowMultitasking: Boolean
        @Synchronized get() = loadBoolean(ALLOW_MULTITASKING, DEFAULT_ALLOW_MULTITASKING)
        @Synchronized set(value) = update(ALLOW_MULTITASKING, value.toString())

    override var ignoreShortRecordsDurationSeconds: Long
        @Synchronized get() = loadLong(
            IGNORE_SHORT_RECORDS_DURATION_SECONDS,
            DEFAULT_IGNORE_SHORT_RECORDS_DURATION_SECONDS,
        )
        @Synchronized set(value) = update(
            IGNORE_SHORT_RECORDS_DURATION_SECONDS,
            value.coerceAtLeast(0).toString(),
        )

    override var startOfDayShiftMillis: Long
        @Synchronized get() = loadSignedLong(START_OF_DAY_SHIFT_MILLIS, DEFAULT_START_OF_DAY_SHIFT_MILLIS)
            .coerceIn(-MAX_START_OF_DAY_SHIFT_MILLIS, MAX_START_OF_DAY_SHIFT_MILLIS)
        @Synchronized set(value) = update(
            START_OF_DAY_SHIFT_MILLIS,
            value.coerceIn(-MAX_START_OF_DAY_SHIFT_MILLIS, MAX_START_OF_DAY_SHIFT_MILLIS).toString(),
        )

    override var firstDayOfWeek: DayOfWeek
        @Synchronized get() = loadDayOfWeek(FIRST_DAY_OF_WEEK, DEFAULT_FIRST_DAY_OF_WEEK)
        @Synchronized set(value) = update(FIRST_DAY_OF_WEEK, value.name)

    override var ignoreShortUntrackedDurationSeconds: Long
        @Synchronized get() = loadLong(
            IGNORE_SHORT_UNTRACKED_DURATION_SECONDS,
            DEFAULT_IGNORE_SHORT_UNTRACKED_DURATION_SECONDS,
        )
        @Synchronized set(value) = update(
            IGNORE_SHORT_UNTRACKED_DURATION_SECONDS,
            value.coerceAtLeast(0).toString(),
        )

    override var showUntrackedInRecords: Boolean
        @Synchronized get() = loadBoolean(SHOW_UNTRACKED_IN_RECORDS, DEFAULT_SHOW_UNTRACKED_IN_RECORDS)
        @Synchronized set(value) = update(SHOW_UNTRACKED_IN_RECORDS, value.toString())

    private fun loadBoolean(key: String, default: Boolean): Boolean {
        val value = load().getProperty(key)?.trim()?.lowercase()
        return when (value) {
            "true" -> true
            "false" -> false
            else -> default
        }
    }

    private fun loadLong(key: String, default: Long): Long =
        load().getProperty(key)?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: default

    private fun loadSignedLong(key: String, default: Long): Long =
        load().getProperty(key)?.trim()?.toLongOrNull() ?: default

    private fun loadDayOfWeek(key: String, default: DayOfWeek): DayOfWeek =
        load().getProperty(key)?.trim()?.uppercase()?.let { value ->
            runCatching { DayOfWeek.valueOf(value) }.getOrNull()
        } ?: default

    private fun update(key: String, value: String) {
        val properties = load().apply { setProperty(key, value) }
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "semantic-preferences-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { properties.store(it, null) }
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

    private fun load(): Properties = Properties().apply {
        if (Files.isRegularFile(file)) {
            runCatching {
                Files.newInputStream(file).use(::load)
            }.onFailure {
                System.err.println("Failed to read desktop semantic preferences: ${it.message}")
            }
        }
    }

    companion object {
        const val DEFAULT_ALLOW_MULTITASKING = true
        const val DEFAULT_IGNORE_SHORT_RECORDS_DURATION_SECONDS = 0L
        const val DEFAULT_START_OF_DAY_SHIFT_MILLIS = 0L
        const val MAX_START_OF_DAY_SHIFT_MILLIS = 24 * 60 * 60 * 1000L - 60 * 1000L
        val DEFAULT_FIRST_DAY_OF_WEEK: DayOfWeek =
            DayOfWeek.of(((Calendar.getInstance().firstDayOfWeek + 5) % 7) + 1)
        // PrefsRepoImpl: 60 seconds, while 0 explicitly disables filtering.
        const val DEFAULT_IGNORE_SHORT_UNTRACKED_DURATION_SECONDS = 60L
        const val DEFAULT_SHOW_UNTRACKED_IN_RECORDS = false
        private const val ALLOW_MULTITASKING = "allowMultitasking"
        private const val IGNORE_SHORT_RECORDS_DURATION_SECONDS = "ignoreShortRecordsDurationSeconds"
        private const val START_OF_DAY_SHIFT_MILLIS = "startOfDayShiftMillis"
        private const val FIRST_DAY_OF_WEEK = "firstDayOfWeek"
        private const val IGNORE_SHORT_UNTRACKED_DURATION_SECONDS = "ignoreShortUntrackedDurationSeconds"
        private const val SHOW_UNTRACKED_IN_RECORDS = "showUntrackedInRecords"
    }
}

private fun defaultSemanticPreferencesPath(): Path {
    val configHome = System.getenv("XDG_CONFIG_HOME")
        ?.takeIf { it.isNotBlank() }
        ?.let(Paths::get)
        ?: Paths.get(System.getProperty("user.home"), ".config")
    return configHome.resolve("simple-time-tracker").resolve("semantic-preferences.properties")
}
