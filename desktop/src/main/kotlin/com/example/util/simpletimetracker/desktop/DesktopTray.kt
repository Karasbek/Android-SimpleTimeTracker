package com.example.util.simpletimetracker.desktop

import dorkbox.systemTray.MenuItem
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
) {
    private val shutdown = AtomicBoolean(false)

    fun shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            tray.shutdown()
        }
    }

    companion object {
        fun create(
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
                tray.setStatus("Simple Time Tracker")

                tray.menu.add(
                    MenuItem(
                        "Открыть",
                        ActionListener {
                            EventQueue.invokeLater(onOpen)
                        },
                    ),
                )

                tray.menu.add(Separator())

                tray.menu.add(
                    MenuItem(
                        "Выход",
                        ActionListener {
                            EventQueue.invokeLater(onExit)
                        },
                    ),
                )

                DesktopTray(tray)
            } catch (error: Throwable) {
                System.err.println("Tray initialization failed: ${error.message}")
                tray?.shutdown()
                null
            }
        }
    }
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
