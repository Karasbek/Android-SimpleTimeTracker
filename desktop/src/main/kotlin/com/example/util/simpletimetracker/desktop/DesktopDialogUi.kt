package com.example.util.simpletimetracker.desktop

import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.UIManager
import javax.swing.plaf.FontUIResource

private const val POPUP_FONT_SCALE = 1.6f
private const val MIN_POPUP_CONTENT_WIDTH = 560
private const val MAX_POPUP_CONTENT_WIDTH = 900
private const val MAX_POPUP_CONTENT_HEIGHT = 520
private val popupUiConfigured = AtomicBoolean(false)

/** Configures Swing only; the Compose main window and system tray are unaffected. */
fun configureDesktopPopupUi() {
    if (!popupUiConfigured.compareAndSet(false, true)) return

    val defaults = UIManager.getDefaults()
    defaults.keys.toList()
        .filterIsInstance<String>()
        .filter { it.endsWith(".font") }
        .forEach { key ->
            val font = defaults[key] as? Font ?: return@forEach
            defaults[key] = FontUIResource(font.deriveFont(font.size2D * POPUP_FONT_SCALE))
        }
    UIManager.put("OptionPane.minimumSize", Dimension(MIN_POPUP_CONTENT_WIDTH, 0))
    UIManager.put("OptionPane.okButtonText", "ОК")
    UIManager.put("OptionPane.cancelButtonText", "Отмена")
    UIManager.put("OptionPane.yesButtonText", "Да")
    UIManager.put("OptionPane.noButtonText", "Нет")
}

fun desktopFormPanel(): JPanel = JPanel(GridLayout(0, 1, 12, 10)).apply {
    border = BorderFactory.createEmptyBorder(16, 20, 16, 20)
}

fun showDesktopConfirmDialog(
    panel: JPanel,
    title: String,
    optionType: Int = JOptionPane.OK_CANCEL_OPTION,
    messageType: Int = JOptionPane.PLAIN_MESSAGE,
): Int {
    val contentSize = panel.preferredSize
    val dialogContentSize = Dimension(
        contentSize.width.coerceIn(MIN_POPUP_CONTENT_WIDTH, MAX_POPUP_CONTENT_WIDTH),
        contentSize.height.coerceAtMost(MAX_POPUP_CONTENT_HEIGHT),
    )
    val content = JScrollPane(panel).apply {
        preferredSize = dialogContentSize
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        border = BorderFactory.createEmptyBorder()
    }
    return JOptionPane.showConfirmDialog(null, content, title, optionType, messageType)
}
