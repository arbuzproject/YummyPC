// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.player

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * Плавающая плашка «Загрузка видеопотока…» поверх окна плеера.
 *
 * mpv рисует видео в нативном дочернем окне, которое перекрывает Compose-слой основного окна,
 * поэтому Compose-индикатор загрузки не виден на чёрном окне mpv. Эта плашка — отдельное
 * всегда-сверху окно ОС, она видна до первого кадра и прячется по событию `loaded`.
 *
 * Создаётся/показывается на UI-потоке AWT; окно не забирает фокус и клики (неактивируемое).
 */
internal class PlayerLoadingOverlay(private val anchor: Window?) {

    private val window = JWindow(anchor)
    private val label = JLabel("Загрузка видеопотока…")
    private val progress = JProgressBar().apply {
        isIndeterminate = true
        background = Color(0xFF1D1D24.toInt())
        foreground = Color(0xFFF08A4B.toInt())
        preferredSize = Dimension(260, 6)
        maximumSize = Dimension(Int.MAX_VALUE, 6)
    }
    private var anchorListener: ComponentAdapter? = null

    init {
        window.isAlwaysOnTop = true
        window.type = Window.Type.POPUP
        window.background = Color(0xFF14141A.toInt())
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color(0xFF14141A.toInt())
            border = javax.swing.BorderFactory.createEmptyBorder(16, 20, 16, 20)
        }
        label.foreground = Color(0xFFF2F2F5.toInt())
        label.font = label.font.deriveFont(Font.BOLD, 14f)
        label.alignmentX = JLabel.CENTER_ALIGNMENT
        progress.alignmentX = JProgressBar.CENTER_ALIGNMENT
        panel.add(label)
        panel.add(Box.createVerticalStrut(10))
        panel.add(progress)
        window.contentPane = panel
        window.pack()
        window.size = Dimension(340, 96)
    }

    fun show(message: String = "Загрузка видеопотока…") {
        SwingUtilities.invokeLater {
            label.text = message
            attachAnchorListener()
            positionOverAnchor()
            if (!window.isVisible) window.isVisible = true
            window.toFront()
        }
    }

    fun setMessage(message: String) {
        SwingUtilities.invokeLater { label.text = message }
    }

    fun hide() {
        SwingUtilities.invokeLater { window.isVisible = false }
    }

    fun dispose() {
        SwingUtilities.invokeLater {
            anchorListener?.let { anchor?.removeComponentListener(it) }
            anchorListener = null
            window.isVisible = false
            window.dispose()
        }
    }

    private fun attachAnchorListener() {
        val owner = anchor ?: return
        if (anchorListener == null) {
            anchorListener = object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent?) = positionOverAnchor()
                override fun componentResized(e: ComponentEvent?) = positionOverAnchor()
            }.also { owner.addComponentListener(it) }
        }
    }

    private fun positionOverAnchor() {
        val owner = anchor ?: return
        if (!owner.isShowing || !window.isDisplayable) return
        val ownerLocation = owner.locationOnScreen
        val x = ownerLocation.x + (owner.width - window.width) / 2
        val y = ownerLocation.y + (owner.height - window.height) / 2 - 40
        window.setLocation(x, y)
    }
}
