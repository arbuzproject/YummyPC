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
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Прозрачный оверлей управления поверх встроенного mpv в стиле клиента для ТВ.
 *
 * Верх: плашка «Тайтл • Серия N» и озвучка, справа — текущий плеер/балансер.
 * Низ: большая кнопка паузы, полоса перемотки, кнопки озвучки, балансера, качества,
 * настроек и следующей серии, а также громкость.
 *
 * Разметка — абсолютная (null layout), чтобы оверлей полностью повторял ТВ-макет.
 * Окно прозрачное и всегда сверху; показывается при движении мыши, прячется при бездействии.
 */
internal class PlayerOverlayWindow(
    private val owner: Window,
    private val onTogglePause: () -> Unit,
    private val onSeek: (Double) -> Unit,
    private val onSeekBy: (Double) -> Unit,
    private val onVolume: (Int) -> Unit,
    private val onNext: () -> Unit,
    private val onFullscreen: () -> Unit,
    private val onClose: () -> Unit,
    private val onSelectDubbing: (String) -> Unit,
    private val onSelectPlayer: (String) -> Unit,
    private val onSelectQuality: (String, String) -> Unit,
    private val onSpeed: (Float) -> Unit,
    private val onSetting: (String, Any?) -> Unit,
) {
    // --- данные, обновляемые из Compose ---
    private var title = ""
    private var episode = ""
    private var dubbing = ""
    private var playerName = ""
    private var dubbings: List<String> = emptyList()
    private var players: List<String> = emptyList()
    private var qualities: List<Pair<String, String>> = emptyList()
    private var qualityLabel = ""
    private var currentDuration = 0.0
    private var userSeeking = false
    private var volumeValue = 100
    private var muted = false
    private var lastVolume = 100
    private var updatingVolume = false
    private var speedIndex = 2
    private var lastInteractionAt = 0L
    private var suppressed = false
    private var visible = false

    // --- окно ---
    private val window = JWindow(owner)
    private val root = object : JPanel(null) {
        override fun paintComponent(g: Graphics) {
            // Полная очистка прозрачным цветом: на translucent-окне иначе остаются
            // «призрачные» кадры от предыдущих положений элементов.
            val g2 = g.create() as Graphics2D
            g2.composite = java.awt.AlphaComposite.Src
            g2.color = Color(0, 0, 0, 0)
            g2.fillRect(0, 0, width, height)
            g2.dispose()
        }
    }
    private val titlePanel = pill()
    private val titleLabel = JLabel("", SwingConstants.LEFT)
    private val dubbingPill = pill()
    private val dubbingLabel = JLabel("", SwingConstants.LEFT)
    private val playerBadge = pill()
    private val playerLabel = JLabel("", SwingConstants.RIGHT)

    private val playButton = orangeButton("▶")
    private val timeLabel = JLabel("0:00", SwingConstants.RIGHT)
    private val durationLabel = JLabel("0:00", SwingConstants.LEFT)
    private val seek = JSlider(0, 1000, 0)

    private val dubbingButton = tvButton("Озвучка")
    private val playerButton = tvButton("Плеер")
    private val qualityButton = tvButton("Качество")
    private val gearButton = tvButton("⚙")
    private val nextButton = tvButton("▶|")
    private val muteButton = tvButton("🔊")
    private val volumeSlider = JSlider(0, 130, 100)

    private var popup: JWindow? = null
    private var popupCallback: (() -> Unit)? = null
    private var hoverTimer: Timer? = null
    private val disposed = AtomicBoolean(false)

    init {
        window.isAlwaysOnTop = true
        window.type = Window.Type.POPUP
        window.background = Color(0, 0, 0, 0)
        runCatching { window.setFocusableWindowState(false) }
        window.contentPane = root
        root.isOpaque = false

        styleLabel(titleLabel, Font.BOLD, 15f)
        styleLabel(dubbingLabel, Font.PLAIN, 13f)
        styleLabel(playerLabel, Font.BOLD, 13f)
        styleLabel(timeLabel, Font.PLAIN, 12f)
        styleLabel(durationLabel, Font.PLAIN, 12f)

        titlePanel.add(titleLabel)
        dubbingPill.add(dubbingLabel)
        playerBadge.add(playerLabel)
        root.add(titlePanel)
        root.add(dubbingPill)
        root.add(playerBadge)

        styleSeek(seek)
        styleSeek(volumeSlider)
        volumeSlider.maximumSize = Dimension(110, 18)

        playButton.addActionListener { onTogglePause() }
        dubbingButton.addActionListener { openListPopup("Озвучка", dubbings, currentDubbing(), dubbingButton) { onSelectDubbing(it) } }
        playerButton.addActionListener { openListPopup("Плеер", players, playerName, playerButton) { onSelectPlayer(it) } }
        qualityButton.addActionListener {
            val labels = qualities.map { it.first }
            val current = qualities.indexOfFirst { it.second == currentQualityUrl() }.coerceAtLeast(0)
            openListPopup("Качество", labels, labels.getOrElse(current) { qualityLabel }, qualityButton) { label ->
                qualities.firstOrNull { it.first == label }?.let { onSelectQuality(it.second, it.first) }
            }
        }
        gearButton.addActionListener { openSettingsPopup() }
        nextButton.addActionListener { onNext() }
        muteButton.addActionListener { toggleMute() }

        root.add(playButton)
        root.add(timeLabel)
        root.add(seek)
        root.add(durationLabel)
        root.add(dubbingButton)
        root.add(playerButton)
        root.add(qualityButton)
        root.add(gearButton)
        root.add(nextButton)
        root.add(muteButton)
        root.add(volumeSlider)

        seek.addChangeListener {
            if (userSeeking) {
                timeLabel.text = fmt(seek.value / 1000.0 * currentDuration)
            }
        }
        seek.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                userSeeking = true
                lastInteractionAt = System.currentTimeMillis()
            }

            override fun mouseReleased(e: MouseEvent) {
                userSeeking = false
                onSeek(seek.value / 1000.0 * currentDuration)
            }
        })
        volumeSlider.addChangeListener {
            if (updatingVolume) return@addChangeListener
            volumeValue = volumeSlider.value
            muted = volumeValue == 0
            muteButton.text = if (muted) "🔇" else "🔊"
            lastInteractionAt = System.currentTimeMillis()
            onVolume(volumeValue)
        }
        root.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                lastInteractionAt = System.currentTimeMillis()
            }
        })

        val t = Timer(120) { updateVisibility() }
        t.isRepeats = true
        t.start()
        hoverTimer = t
        layoutAll()
    }

    // ---------- публичное API ----------

    fun setTitle(title: String, episode: String) {
        this.title = title
        this.episode = episode
        SwingUtilities.invokeLater { refreshPills() }
    }

    fun setSources(dubbings: List<String>, players: List<String>, dubbing: String, player: String) {
        this.dubbings = dubbings
        this.players = players
        this.dubbing = dubbing
        this.playerName = player
        SwingUtilities.invokeLater { refreshPills() }
    }

    fun setQualities(list: List<Pair<String, String>>, label: String) {
        this.qualities = list
        this.qualityLabel = label
        SwingUtilities.invokeLater {
            qualityButton.text = label.ifBlank { "Качество" }
            layoutAll()
        }
    }

    fun setVolume(value: Int) {
        SwingUtilities.invokeLater {
            updatingVolume = true
            volumeSlider.value = value.coerceIn(0, 130)
            updatingVolume = false
            volumeValue = value
            muted = value == 0
            muteButton.text = if (muted) "🔇" else "🔊"
        }
    }

    fun setPlayback(state: MpvPlaybackState) {
        SwingUtilities.invokeLater {
            currentDuration = state.durationSec
            if (!userSeeking && state.durationSec > 0) {
                seek.value = ((state.positionSec / state.durationSec) * 1000).toInt().coerceIn(0, 1000)
            }
            timeLabel.text = fmt(state.positionSec)
            durationLabel.text = fmt(state.durationSec)
            playButton.text = if (state.paused) "▶" else "⏸"
        }
    }

    fun setSuppressed(value: Boolean) {
        suppressed = value
        if (value) SwingUtilities.invokeLater {
            closePopup()
            window.isVisible = false
        }
    }

    fun dispose() {
        if (disposed.compareAndSet(false, true)) {
            SwingUtilities.invokeLater {
                hoverTimer?.stop()
                closePopup()
                window.isVisible = false
                window.dispose()
            }
        }
    }

    // ---------- показ/скрытие ----------

    private fun updateVisibility() {
        if (disposed.get() || !owner.isShowing) return
        layoutAll()
        val pointer = java.awt.MouseInfo.getPointerInfo()?.location ?: return
        val overOwner = owner.bounds.contains(pointer)
        val recent = System.currentTimeMillis() - lastInteractionAt < 2600
        val popupOpen = popup?.isVisible == true
        val shouldShow = !suppressed && (overOwner || recent || popupOpen)
        if (shouldShow != visible) {
            visible = shouldShow
            window.isVisible = shouldShow
            if (shouldShow) window.toFront()
        }
        if (!shouldShow && window.isVisible) window.isVisible = false
    }

    private fun layoutAll() {
        if (!owner.isShowing) return
        window.size = Dimension(owner.width, owner.height)
        window.setLocation(owner.x, owner.y)
        val w = owner.width
        val h = owner.height
        val pad = 18

        titlePanel.setSize(maxWidth(360, w / 2), 40)
        titlePanel.setLocation(pad, pad)
        titleLabel.setBounds(12, 0, titlePanel.width - 24, 40)
        dubbingPill.setSize(260, 32)
        dubbingPill.setLocation(pad, pad + 48)
        dubbingLabel.setBounds(12, 0, 236, 32)
        playerBadge.setSize(140, 36)
        playerBadge.setLocation(w - pad - 140, pad)
        playerLabel.setBounds(10, 0, 120, 36)
        playerLabel.horizontalAlignment = SwingConstants.RIGHT

        val controlsY = h - 180
        playButton.setSize(74, 74)
        playButton.setLocation(pad + 6, h - 196)

        val barX = pad + 96
        val barW = w - barX - pad - 10
        timeLabel.setBounds(barX, controlsY + 6, 64, 22)
        seek.setBounds(barX + 70, controlsY + 8, barW - 190, 18)
        durationLabel.setBounds(barX + barW - 60, controlsY + 6, 60, 22)

        val buttonsY = controlsY + 48
        var x = barX + 20
        dubbingButton.setSize(240, 48)
        dubbingButton.setLocation(x, buttonsY)
        x += 252
        playerButton.setSize(130, 48)
        playerButton.setLocation(x, buttonsY)
        x += 142
        qualityButton.setSize(130, 48)
        qualityButton.setLocation(x, buttonsY)
        x += 142
        gearButton.setSize(52, 48)
        gearButton.setLocation(x, buttonsY)
        x += 64
        nextButton.setSize(52, 48)
        nextButton.setLocation(x, buttonsY)
        x += 74
        muteButton.setSize(44, 40)
        muteButton.setLocation(w - pad - 44 - 130 - 46, buttonsY + 4)
        volumeSlider.setBounds(w - pad - 130 - 40, buttonsY + 17, 120, 18)

        refreshPills()
        root.repaint()
        if (visible) window.toFront()
    }

    private fun refreshPills() {
        titleLabel.text = buildString {
            append(title)
            if (episode.isNotBlank()) append(" • Серия ").append(episode)
        }.ifBlank { "Воспроизведение" }
        dubbingLabel.text = dubbing.ifBlank { "Озвучка" }
        playerLabel.text = playerName.ifBlank { "" }
        playerBadge.isVisible = playerName.isNotBlank()
        dubbingPill.isVisible = dubbing.isNotBlank()
        dubbingButton.text = dubbing.ifBlank { "Озвучка" }
        playerButton.text = playerName.ifBlank { "Плеер" }
        root.repaint()
    }

    // ---------- попапы списков ----------

    private fun openListPopup(
        header: String,
        items: List<String>,
        selected: String,
        anchor: JButton,
        onChoose: (String) -> Unit,
    ) {
        closePopup()
        if (items.isEmpty()) return
        val pw = 400
        val rowH = 58
        val ph = headerHeight + rowH * items.size + 16
        val pop = popupWindow(pw, ph)
        val content = JPanel(null).apply {
            isOpaque = false
            background = Color(0, 0, 0, 0)
        }
        val bg = popupBackground("")
        bg.setBounds(0, 0, pw, ph)
        content.add(bg)

        val head = JLabel(header)
        head.foreground = Color(0xFFB9B9C6.toInt())
        head.font = head.font.deriveFont(Font.BOLD, 13f)
        head.setBounds(18, 14, pw - 36, 24)
        content.add(head)

        items.forEachIndexed { index, item ->
            val btn = popupRow(item, item == selected)
            btn.setBounds(12, headerHeight + index * rowH, pw - 24, rowH - 8)
            btn.addActionListener {
                closePopup()
                onChoose(item)
            }
            content.add(btn)
        }
        pop.contentPane = content
        pop.setSize(pw, ph)
        val anchorLoc = anchor.locationOnScreen
        var px = anchorLoc.x
        var py = anchorLoc.y - ph - 10
        if (py < 20) py = anchorLoc.y + anchor.height + 10
        if (px + pw > owner.x + owner.width - 10) px = owner.x + owner.width - pw - 10
        pop.setLocation(px, py)
        pop.isVisible = true
        popup = pop
    }

    private fun openSettingsPopup() {
        closePopup()
        val pw = 420
        val rows = listOf<Triple<String, List<String>, Int>>(
            Triple("Скорость", listOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x"), speedIndex),
            Triple("Масштаб", listOf("Оригинальный", "125%", "150%"), 0),
            Triple("Адаптивное качество", listOf("Выкл", "Вкл"), 0),
            Triple("Размер субтитров", listOf("40", "55", "70", "90"), 1),
            Triple("Отступ субтитров", listOf("95", "90", "85"), 0),
            Triple("Цвет субтитров", listOf("#FFFFFF", "#FFD54F", "#69F0AE"), 0),
            Triple("Фон субтитров", listOf("Прозрачный", "Полупрозрачный", "Чёрный"), 1),
            Triple("Обводка субтитров", listOf("Нет", "Тонкая", "Толстая"), 0),
            Triple("Стабилизация громкости", listOf("Выкл", "Вкл"), 0),
        )
        val rowH = 62
        val ph = 60 + rows.size * rowH + 14
        val pop = popupWindow(pw, ph)
        val content = JPanel(null).apply { isOpaque = false }
        val bg = popupBackground("")
        bg.setBounds(0, 0, pw, ph)
        content.add(bg)
        val head = JLabel("Настройки плеера")
        head.foreground = Color(0xFFF2F2F5.toInt())
        head.font = head.font.deriveFont(Font.BOLD, 20f)
        head.setBounds(20, 16, pw - 40, 30)
        content.add(head)
        val current = IntArray(rows.size) { rows[it].third }
        rows.forEachIndexed { index, (label, values, _) ->
            val btn = JButton(label + "     " + values[current[index]])
            styleRowButton(btn, false)
            btn.horizontalAlignment = SwingConstants.LEFT
            btn.setBounds(14, 60 + index * rowH, pw - 28, rowH - 10)
            btn.addActionListener {
                current[index] = (current[index] + 1) % values.size
                btn.text = label + "     " + values[current[index]]
                lastInteractionAt = System.currentTimeMillis()
                applySetting(label, values[current[index]], current[index])
            }
            content.add(btn)
        }
        pop.contentPane = content
        pop.setSize(pw, ph)
        val right = owner.x + owner.width
        pop.setLocation(right - pw - 20, owner.y + (owner.height - ph) / 2)
        pop.isVisible = true
        popup = pop
    }

    private fun applySetting(label: String, value: String, index: Int) {
        when {
            label == "Скорость" -> {
                speedIndex = index
                onSpeed(listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)[index])
            }
            label == "Масштаб" -> onSetting(
                "video-zoom",
                when (index) { 1 -> 0.12; 2 -> 0.25; else -> 0.0 },
            )
            label == "Адаптивное качество" -> onSetting("yummypc-adaptive", index == 1)
            label == "Размер субтитров" -> onSetting("sub-font-size", value.toIntOrNull() ?: 55)
            label == "Отступ субтитров" -> onSetting("sub-pos", value.toIntOrNull() ?: 90)
            label == "Цвет субтитров" -> onSetting("sub-color", value)
            label == "Фон субтитров" -> onSetting(
                "sub-back-color",
                when (index) { 0 -> "#00000000"; 1 -> "#80000000"; else -> "#FF000000" },
            )
            label == "Обводка субтитров" -> onSetting("sub-border-size", when (index) { 0 -> 0.0; 1 -> 2.0; else -> 4.0 })
            label == "Стабилизация громкости" -> onSetting("yummypc-volume-stabilization", index == 1)
        }
    }

    private fun popupWindow(w: Int, h: Int): JWindow {
        val pop = JWindow(owner)
        pop.isAlwaysOnTop = true
        pop.type = Window.Type.POPUP
        pop.background = Color(0, 0, 0, 0)
        pop.size = Dimension(w, h)
        return pop
    }

    private fun popupBackground(text: String): JPanel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(24, 24, 32, 245)
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 20f, 20f))
            g2.color = Color(255, 255, 255, 20)
            g2.stroke = BasicStroke(1f)
            g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 20f, 20f))
            g2.dispose()
        }
    }

    private fun popupRow(label: String, selected: Boolean): JButton =
        JButton(label).apply {
            styleRowButton(this, selected)
            horizontalAlignment = SwingConstants.LEFT
        }

    private fun styleRowButton(button: JButton, selected: Boolean) {
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.isBorderPainted = false
        button.isFocusPainted = false
        button.foreground = Color(0xFFF2F2F5.toInt())
        button.font = button.font.deriveFont(Font.BOLD, 16f)
        button.border = BorderFactory.createEmptyBorder(8, 18, 8, 18)
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.background = if (selected) Color(0xFFF08A4B.toInt()) else Color(40, 40, 50, 220)
        button.isOpaque = true
        button.isContentAreaFilled = true
    }

    private fun closePopup() {
        popup?.isVisible = false
        popup?.dispose()
        popup = null
        popupCallback = null
    }

    // ---------- вспомогательное ----------

    private fun currentDubbing(): String = dubbing
    private fun currentQualityUrl(): String =
        qualities.firstOrNull { it.first == qualityLabel }?.second ?: ""

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

    private fun pill(): JPanel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(18, 18, 24, 215)
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 10f, 10f))
            g2.dispose()
        }
    }

    private fun orangeButton(text: String): JButton = object : JButton(text) {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(0xFFF08A4B.toInt())
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 16f, 16f))
            g2.dispose()
            super.paintComponent(g)
        }
    }.apply {
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        foreground = Color(0xFF16110B.toInt())
        font = font.deriveFont(Font.BOLD, 28f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private fun tvButton(text: String): JButton = object : JButton(text) {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(20, 20, 28, 210)
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 12f, 12f))
            g2.color = Color(255, 255, 255, 40)
            g2.stroke = BasicStroke(1.4f)
            g2.draw(RoundRectangle2D.Float(1f, 1f, width - 2f, height - 2f, 12f, 12f))
            g2.dispose()
            super.paintComponent(g)
        }
    }.apply {
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        foreground = Color(0xFFF2F2F5.toInt())
        font = font.deriveFont(Font.BOLD, 15f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private fun styleLabel(label: JLabel, style: Int, size: Float) {
        label.foreground = Color(0xFFF2F2F5.toInt())
        label.font = label.font.deriveFont(style, size)
    }

    private fun styleSeek(slider: JSlider) {
        slider.isOpaque = false
        slider.foreground = Color(0xFFF08A4B.toInt())
        slider.background = Color(0, 0, 0, 0)
        slider.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private fun maxWidth(min: Int, value: Int): Int = value.coerceAtLeast(min)

    private fun fmt(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private val headerHeight = 44
}
