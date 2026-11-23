// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.player

import su.afk.yummy.pc.player.MpvPlaybackState
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Прозрачный оверлей управления поверх встроенного mpv.
 *
 * mpv рисует видео в нативном дочернем окне, поэтому Compose-панель оказывается под ним.
 * Оверлей — отдельное всегда-сверху окно ОС с прозрачным фоном: виден только сам полупрозрачный
 * пульт, он появляется при наведении в нижнюю область видео и прячется через пару секунд.
 *
 * Управляет паузой, перемоткой, громкостью, качеством, озвучкой, серией и закрытием.
 */
internal class PlayerOverlayWindow(
    private val owner: Window,
    private val onTogglePause: () -> Unit,
    private val onSeek: (Double) -> Unit,
    private val onSeekBy: (Double) -> Unit,
    private val onVolume: (Int) -> Unit,
    private val onQuality: () -> Unit,
    private val onSources: () -> Unit,
    private val onNext: () -> Unit,
    private val onFullscreen: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val window = JWindow(owner)
    private val seek = JSlider(0, 1000, 0)
    private val volumeSlider = JSlider(0, 130, 100)
    private val playButton = flatButton("⏸")
    private val muteButton = flatButton("🔊")
    private val qualityButton = flatButton("Качество: auto")
    private val timeLabel = JLabel("0:00 / 0:00")
    private val titleLabel = JLabel("")

    private var userSeeking = false
    private var currentDuration = 0.0
    private var lastVolume = 100
    private var muted = false
    private var lastInteractionAt = 0L
    private var visible = false
    private var updatingVolume = false
    private var suppressed = false
    private var hoverTimer: Timer? = null
    private val disposed = AtomicBoolean(false)

    private val panel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(16, 16, 22, 225)
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 18f, 18f))
            g2.color = Color(255, 255, 255, 26)
            g2.stroke = BasicStroke(1f)
            g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 18f, 18f))
            g2.dispose()
        }
    }

    init {
        window.isAlwaysOnTop = true
        window.type = Window.Type.POPUP
        window.background = Color(0, 0, 0, 0)
        runCatching { window.setFocusableWindowState(false) }
        panel.isOpaque = false
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(8, 16, 8, 16)

        styleSlider(seek, width = 400)
        styleSlider(volumeSlider, width = 110)
        timeLabel.foreground = Color(0xFFD8D8E0.toInt())
        timeLabel.font = timeLabel.font.deriveFont(11f)
        titleLabel.foreground = Color(0xFFF2F2F5.toInt())
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 12f)
        playButton.addActionListener { onTogglePause() }
        muteButton.addActionListener { toggleMute() }
        qualityButton.addActionListener { onQuality() }

        val titleRow = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(titleLabel)
            add(Box.createHorizontalGlue())
            add(timeLabel)
        }
        panel.add(titleRow)

        val seekRow = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(seek)
        }
        panel.add(seekRow)

        val backButton = flatButton("−10").apply { addActionListener { onSeekBy(-10.0) } }
        val forwardButton = flatButton("+10").apply { addActionListener { onSeekBy(10.0) } }
        val sourcesButton = flatButton("Озвучка").apply { addActionListener { onSources() } }
        val nextButton = flatButton("Серия →").apply { addActionListener { onNext() } }
        val fullscreenButton = flatButton("⛶").apply { addActionListener { onFullscreen() } }
        val closeButton = flatButton("✕").apply { addActionListener { onClose() } }
        val buttons = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(playButton)
            add(backButton)
            add(forwardButton)
            add(Box.createHorizontalStrut(8))
            add(qualityButton)
            add(Box.createHorizontalStrut(8))
            add(sourcesButton)
            add(nextButton)
            add(Box.createHorizontalGlue())
            add(muteButton)
            add(Box.createHorizontalStrut(4))
            add(volumeSlider)
            add(Box.createHorizontalStrut(8))
            add(fullscreenButton)
            add(closeButton)
        }
        panel.add(buttons)

        window.contentPane = panel

        seek.addChangeListener {
            if (userSeeking) {
                timeLabel.text = "${fmt(seek.value / 1000.0 * currentDuration)} / ${fmt(currentDuration)}"
            }
        }
        seek.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                userSeeking = true
                lastInteractionAt = System.currentTimeMillis()
            }

            override fun mouseReleased(e: MouseEvent) {
                userSeeking = false
                onSeek((seek.value / 1000.0) * currentDuration)
            }
        })
        volumeSlider.addChangeListener {
            if (updatingVolume) return@addChangeListener
            val value = volumeSlider.value
            lastInteractionAt = System.currentTimeMillis()
            muted = value == 0
            muteButton.text = if (muted) "🔇" else "🔊"
            onVolume(value)
        }
        panel.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                lastInteractionAt = System.currentTimeMillis()
            }
        })

        val timer = Timer(120) { updateVisibility() }
        timer.isRepeats = true
        timer.start()
        hoverTimer = timer

        updateBounds()
    }

    fun setVolume(value: Int) {
        SwingUtilities.invokeLater {
            updatingVolume = true
            volumeSlider.value = value.coerceIn(0, 130)
            updatingVolume = false
            muted = value == 0
            muteButton.text = if (muted) "🔇" else "🔊"
        }
    }

    fun setQuality(label: String) {
        SwingUtilities.invokeLater {
            qualityButton.text = "Качество: ${label.ifBlank { "auto" }}"
        }
    }

    fun setTitle(text: String) {
        SwingUtilities.invokeLater { titleLabel.text = text }
    }

    fun setPlayback(state: MpvPlaybackState) {
        SwingUtilities.invokeLater {
            currentDuration = state.durationSec
            if (!userSeeking && state.durationSec > 0) {
                seek.value = ((state.positionSec / state.durationSec) * 1000).toInt().coerceIn(0, 1000)
            }
            timeLabel.text = "${fmt(state.positionSec)} / ${fmt(state.durationSec)}"
            playButton.text = if (state.paused) "▶" else "⏸"
        }
    }

    /** Прятать оверлей, пока открыты панели/ошибка. */
    fun setSuppressed(value: Boolean) {
        suppressed = value
        if (value) SwingUtilities.invokeLater { window.isVisible = false }
    }

    private fun updateVisibility() {
        if (disposed.get() || !owner.isShowing) return
        updateBounds()
        val pointer = java.awt.MouseInfo.getPointerInfo()?.location ?: return
        val withinOwner = owner.bounds.contains(pointer)
        val nearBottom = withinOwner && pointer.y >= owner.y + owner.height - 210
        val overOverlay = window.bounds.contains(pointer)
        val recentlyActive = System.currentTimeMillis() - lastInteractionAt < 2200
        val shouldShow = !suppressed && (nearBottom || overOverlay || recentlyActive)
        if (shouldShow != visible) {
            visible = shouldShow
            window.isVisible = shouldShow
        }
    }

    private fun updateBounds() {
        val width = (owner.width - 48).coerceAtLeast(420)
        val height = 92
        window.size = Dimension(width, height)
        window.setLocation(owner.x + 24, owner.y + owner.height - height - 18)
    }

    private fun toggleMute() {
        lastInteractionAt = System.currentTimeMillis()
        if (muted) {
            muted = false
            val restore = if (lastVolume > 0) lastVolume else 100
            updatingVolume = true
            volumeSlider.value = restore
            updatingVolume = false
            muteButton.text = "🔊"
            onVolume(restore)
        } else {
            lastVolume = volumeSlider.value
            muted = true
            updatingVolume = true
            volumeSlider.value = 0
            updatingVolume = false
            muteButton.text = "🔇"
            onVolume(0)
        }
    }

    fun dispose() {
        if (disposed.compareAndSet(false, true)) {
            SwingUtilities.invokeLater {
                hoverTimer?.stop()
                window.isVisible = false
                window.dispose()
            }
        }
    }

    private fun styleSlider(slider: JSlider, width: Int) {
        slider.isOpaque = false
        slider.foreground = Color(0xFFF08A4B.toInt())
        slider.background = Color(0, 0, 0, 0)
        slider.maximumSize = Dimension(width, 18)
        slider.preferredSize = Dimension(width, 18)
        slider.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private fun styleButton(button: JButton) {
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.isBorderPainted = false
        button.isFocusPainted = false
        button.foreground = Color(0xFFF2F2F5.toInt())
        button.font = button.font.deriveFont(Font.BOLD, 12f)
        button.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private fun flatButton(text: String): JButton = JButton(text).also(::styleButton)

    private fun fmt(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }
}
