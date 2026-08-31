package com.example.util.simpletimetracker.desktop

import dorkbox.systemTray.MenuItem
import dorkbox.systemTray.Menu
import dorkbox.systemTray.Separator
import dorkbox.systemTray.SystemTray
import java.awt.BasicStroke
import java.awt.Color
import java.awt.EventQueue
import java.awt.RenderingHints
import java.awt.event.ActionListener
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

class DesktopTray private constructor(
    private val tray: SystemTray,
    private val onToggle: (Long) -> Unit,
    private val onRepeatPrevious: () -> Unit,
    private val onOpen: () -> Unit,
    private val onExit: () -> Unit,
) {
    private val shutdown = AtomicBoolean(false)
    private val runningMenu = Menu("Запущенные")
    private val pinnedMenu = Menu("Закреплённые")
    private val runningItems = mutableMapOf<Long, MenuItem>()
    private val pinnedItems = mutableMapOf<Long, MenuItem>()
    private val repeatItem = actionItem("Повторить предыдущую", onRepeatPrevious)

    init {
        tray.menu.add(runningMenu)
        tray.menu.add(pinnedMenu)
        tray.menu.add(Separator())
        tray.menu.add(repeatItem)
        tray.menu.add(Separator())
        tray.menu.add(actionItem("Открыть", onOpen))
        tray.menu.add(Separator())
        tray.menu.add(actionItem("Выход", onExit))
    }

    fun shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            tray.shutdown()
        }
    }

    fun update(state: DesktopTrayState) {
        if (shutdown.get()) return
        EventQueue.invokeLater {
            if (!shutdown.get()) updateMenu(state)
        }
    }

    private fun updateMenu(state: DesktopTrayState) {
        syncItems(runningMenu, runningItems, state.running, ::runningLabel)
        syncItems(pinnedMenu, pinnedItems, state.pinned) { activity ->
            val marker = if (activity.startedAt == null) "▶" else "■"
            "$marker ${activity.name}"
        }
        runningMenu.enabled = state.running.isNotEmpty()
        pinnedMenu.enabled = state.pinned.isNotEmpty()
        repeatItem.enabled = state.canRepeatPrevious
    }

    private fun actionItem(text: String, action: () -> Unit): MenuItem =
        MenuItem(text, ActionListener { action() })

    private fun syncItems(
        menu: Menu,
        current: MutableMap<Long, MenuItem>,
        activities: List<TrayActivity>,
        label: (TrayActivity) -> String,
    ) {
        val actualIds = activities.mapTo(mutableSetOf(), TrayActivity::id)
        current.keys.filterNot(actualIds::contains).forEach { id ->
            current.remove(id)?.remove()
        }
        activities.forEach { activity ->
            val text = label(activity)
            val item = current[activity.id]
            if (item == null) {
                current[activity.id] = menu.add(
                    actionItem(text) { onToggle(activity.id) },
                )
            } else if (item.text != text) {
                item.text = text
            }
        }
    }

    companion object {
        fun create(
            initialState: DesktopTrayState,
            onToggle: (Long) -> Unit,
            onRepeatPrevious: () -> Unit,
            onOpen: () -> Unit,
            onExit: () -> Unit,
        ): DesktopTray? {
            SystemTray.DEBUG = false
            SystemTray.PREFER_GTK3 = true
            SystemTray.FORCE_TRAY_TYPE = SystemTray.TrayType.AppIndicator

            var tray: SystemTray? = null

            return try {
                tray = SystemTray.get("SimpleTimeTracker") ?: return null
                tray.menu.setImage(createTrayIcon())

                DesktopTray(
                    tray = tray,
                    onToggle = onToggle,
                    onRepeatPrevious = onRepeatPrevious,
                    onOpen = { EventQueue.invokeLater(onOpen) },
                    onExit = { EventQueue.invokeLater(onExit) },
                ).also { it.updateMenu(initialState) }
            } catch (error: Throwable) {
                System.err.println("Tray initialization failed: ${error.message}")
                tray?.shutdown()
                null
            }
        }
    }
}

private fun runningLabel(activity: TrayActivity): String {
    val started = java.time.Instant.ofEpochMilli(activity.startedAt ?: 0L)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    return "■ ${activity.name} — с $started"
}

private fun createTrayIcon(): java.io.File {
    val cacheHome = System.getenv("XDG_CACHE_HOME")
        ?.takeIf { it.isNotBlank() }
        ?.let(Paths::get)
        ?: Paths.get(System.getProperty("user.home"), ".cache")

    val directory = cacheHome.resolve("simple-time-tracker")
    Files.createDirectories(directory)

    val iconFile = directory.resolve("tray.png").toFile()
    val size = 64
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()

    try {
        graphics.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON,
        )
        graphics.color = Color.WHITE
        graphics.stroke = BasicStroke(5f)
        graphics.drawOval(8, 8, 48, 48)

        graphics.stroke = BasicStroke(
            5f,
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND,
        )
        graphics.drawLine(32, 32, 32, 18)
        graphics.drawLine(32, 32, 43, 38)
    } finally {
        graphics.dispose()
    }

    ImageIO.write(image, "png", iconFile)
    return iconFile
}
