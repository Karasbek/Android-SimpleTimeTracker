package com.example.util.simpletimetracker.desktop

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Properties

interface DesktopSemanticPreferences {
    var allowMultitasking: Boolean
}

class FileDesktopSemanticPreferences(
    private val file: Path = defaultSemanticPreferencesPath(),
) : DesktopSemanticPreferences {

    override var allowMultitasking: Boolean
        @Synchronized get() = loadBoolean(ALLOW_MULTITASKING, DEFAULT_ALLOW_MULTITASKING)
        @Synchronized set(value) = update(ALLOW_MULTITASKING, value.toString())

    private fun loadBoolean(key: String, default: Boolean): Boolean {
        val value = load().getProperty(key)?.trim()?.lowercase()
        return when (value) {
            "true" -> true
            "false" -> false
            else -> default
        }
    }

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
        private const val ALLOW_MULTITASKING = "allowMultitasking"
    }
}

private fun defaultSemanticPreferencesPath(): Path {
    val configHome = System.getenv("XDG_CONFIG_HOME")
        ?.takeIf { it.isNotBlank() }
        ?.let(Paths::get)
        ?: Paths.get(System.getProperty("user.home"), ".config")
    return configHome.resolve("simple-time-tracker").resolve("semantic-preferences.properties")
}
