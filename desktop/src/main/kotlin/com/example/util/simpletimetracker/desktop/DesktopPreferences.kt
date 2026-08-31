package com.example.util.simpletimetracker.desktop

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Properties

interface DesktopSemanticPreferences {
    var allowMultitasking: Boolean
    var ignoreShortRecordsDurationSeconds: Long
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
        private const val ALLOW_MULTITASKING = "allowMultitasking"
        private const val IGNORE_SHORT_RECORDS_DURATION_SECONDS = "ignoreShortRecordsDurationSeconds"
    }
}

private fun defaultSemanticPreferencesPath(): Path {
    val configHome = System.getenv("XDG_CONFIG_HOME")
        ?.takeIf { it.isNotBlank() }
        ?.let(Paths::get)
        ?: Paths.get(System.getProperty("user.home"), ".config")
    return configHome.resolve("simple-time-tracker").resolve("semantic-preferences.properties")
}
