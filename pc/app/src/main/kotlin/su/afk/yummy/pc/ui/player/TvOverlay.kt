// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.afk.yummy.pc.api.SkipRange
import su.afk.yummy.pc.player.MpvPlaybackState
import su.afk.yummy.pc.ui.theme.YummyPalette

/** Панели-попапы поверх видео (как в тестовой Chromium-версии). */
enum class TvPanel { NONE, DUBBING, PLAYER, QUALITY, SETTINGS }

private val Accent = Color(0xFFF08A4B)
private val PanelBg = Color(0xFA14141A)
private val PillBg = Color(0xD1101016)
private val Dim = Color(0xFFB7B7C2)

private fun formatTime(seconds: Double): String {
    if (!seconds.isFinite() || seconds <= 0.0) return "0:00"
    val total = seconds.toLong()
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/**
 * Телевизионный оверлей плеера: плашки тайтла/озвучки, бейдж балансера, большая кнопка Play,
 * прогресс, кнопки серий/пропуска опенинга и эндинга/озвучки/плеера/качества/настроек,
 * громкость, полный экран и закрытие. Всё рисуется внутри окна приложения поверх видео.
 */
@Composable
fun TvPlayerOverlay(
    title: String,
    episode: String,
    dubbing: String,
    playerName: String,
    qualityLabel: String,
    state: MpvPlaybackState,
    volume: Int,
    speed: Float,
    subtitleSize: Int,
    subtitleColor: String,
    subtitleBackground: String,
    controlsVisible: Boolean,
    panel: TvPanel,
    dubbings: List<Pair<String, List<String>>>,
    qualities: List<Pair<String, String>>,
    openingSkip: SkipRange?,
    endingSkip: SkipRange?,
    isFullscreen: Boolean,
    onTogglePause: () -> Unit,
    onSeek: (Float) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSkip: (Double) -> Unit,
    onCancelAutoSkip: (() -> Unit)? = null,
    openingCountdown: Int? = null,
    endingCountdown: Int? = null,
    onTogglePanel: (TvPanel) -> Unit,
    onSelectDubbing: (String) -> Unit,
    onSelectPlayer: (String) -> Unit,
    onSelectQuality: (String) -> Unit,
    onSpeed: (Float) -> Unit,
    onVolume: (Int) -> Unit,
    onMute: () -> Unit,
    onSubtitleSize: (Int) -> Unit,
    onSubtitleColor: (String) -> Unit,
    onSubtitleBackground: (String) -> Unit,
    onFullscreen: () -> Unit,
    onClose: () -> Unit,
) {
    val duration = if (state.durationSec > 0) state.durationSec else 1.0
    val skipOpening = openingSkip?.takeIf { state.positionSec >= it.startSec.toDouble() && state.positionSec < it.endSec.toDouble() }
    val skipEnding = endingSkip?.takeIf { state.positionSec >= it.startSec.toDouble() && state.positionSec < it.endSec.toDouble() }

    Box(Modifier.fillMaxSize()) {
        // Верхние плашки.
        if (controlsVisible) {
            Column(Modifier.align(Alignment.TopStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(text = title + if (episode.isNotBlank()) " • Серия $episode" else "", fontSize = 20)
                Pill(text = dubbing.ifBlank { "Озвучка" }, fontSize = 14, color = Dim)
            }
            Pill(
                text = playerName.uppercase(),
                fontSize = 15,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            )
        }

        // Большая кнопка Play по центру (когда пауза и нет попапа).
        if (state.paused && panel == TvPanel.NONE && state.loaded) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(92.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Accent)
                    .clickable(onClick = onTogglePause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Смотреть", tint = Color(0xFF16110B), modifier = Modifier.size(48.dp))
            }
        }

        // Кнопки пропуска опенинга/эндинга — в правом углу.
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 132.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            skipOpening?.let {
                val label = if (openingCountdown != null && openingCountdown > 0) "Пропустить ($openingCountdown с)" else "Пропустить"
                TvButton(label, accentOutline = true, compact = true) { onSkip(it.endSec.toDouble()) }
            }
            skipEnding?.let {
                val label = if (endingCountdown != null && endingCountdown > 0) "Пропустить ($endingCountdown с)" else "Пропустить"
                TvButton(label, accentOutline = true, compact = true) { onSkip(it.endSec.toDouble()) }
            }
            if (onCancelAutoSkip != null) {
                TvButton("Отменить", accentOutline = false, compact = true) { onCancelAutoSkip() }
            }
        }

        // Нижняя панель.
        if (controlsVisible) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x66000000))
                    .padding(horizontal = 22.dp, vertical = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(formatTime(state.positionSec), color = Dim, fontSize = 15.sp)
                    Slider(
                        value = (state.positionSec / duration).toFloat().coerceIn(0f, 1f),
                        onValueChange = { onSeek((it * duration).toFloat()) },
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatTime(state.durationSec), color = Dim, fontSize = 15.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)).background(Accent).clickable(onClick = onTogglePause),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = "Пауза",
                            tint = Color(0xFF16110B),
                        )
                    }
                    IconButton(onClick = onPrev) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Предыдущая серия", tint = Color.White)
                    }
                    IconButton(onClick = onNext) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Следующая серия", tint = Color.White)
                    }
                    Spacer(Modifier.width(4.dp))
                    TvButton(dubbing.ifBlank { "Озвучка" }, selected = panel == TvPanel.DUBBING) { onTogglePanel(TvPanel.DUBBING) }
                    TvButton(playerName.ifBlank { "Плеер" }, selected = panel == TvPanel.PLAYER) { onTogglePanel(TvPanel.PLAYER) }
                    TvButton(qualityLabel.ifBlank { "Качество" }, selected = panel == TvPanel.QUALITY) { onTogglePanel(TvPanel.QUALITY) }
                    IconButton(onClick = { onTogglePanel(TvPanel.SETTINGS) }, modifier = Modifier.size(50.dp)) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Настройки", tint = if (panel == TvPanel.SETTINGS) Accent else Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onMute) {
                        Icon(
                            if (volume == 0) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            contentDescription = "Звук",
                            tint = Color.White,
                        )
                    }
                    Slider(
                        value = volume.coerceIn(0, 130).toFloat(),
                        onValueChange = { onVolume(it.toInt()) },
                        valueRange = 0f..130f,
                        modifier = Modifier.width(110.dp),
                    )
                    Text("$volume%", color = Dim, fontSize = 13.sp, modifier = Modifier.width(40.dp))
                    IconButton(onClick = onFullscreen) {
                        Icon(
                            if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = "Полный экран",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color(0xFFFF8A65))
                    }
                }
            }
        }

        // Попапы.
        when (panel) {
            TvPanel.DUBBING, TvPanel.PLAYER -> TvPopup(
                title = if (panel == TvPanel.DUBBING) "Озвучка" else "Плеер",
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 22.dp, bottom = 116.dp),
            ) {
                if (panel == TvPanel.DUBBING) {
                    dubbings.forEach { (name, playerList) ->
                        PopupRow(
                            title = name,
                            subtitle = playerList.joinToString(" • "),
                            selected = name == dubbing,
                        ) { onSelectDubbing(name) }
                    }
                } else {
                    dubbings.flatMap { it.second }.distinct().forEach { name ->
                        PopupRow(
                            title = name,
                            subtitle = if (name == playerName) "сейчас" else "Балансер",
                            selected = name == playerName,
                        ) { onSelectPlayer(name) }
                    }
                }
            }

            TvPanel.QUALITY -> TvPopup(
                title = "Качество",
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 116.dp),
            ) {
                qualities.forEach { (label, _) ->
                    PopupRow(
                        title = label,
                        subtitle = if (label == qualityLabel) "сейчас" else "Поток видео",
                        selected = label == qualityLabel,
                    ) { onSelectQuality(label) }
                }
            }

            TvPanel.SETTINGS -> TvPopup(
                title = "Настройки плеера",
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 116.dp),
            ) {
                SettingsRow("Скорость", "${speed}x") {
                    onSpeed(
                        when (speed) {
                            0.75f -> 1.0f
                            1.0f -> 1.25f
                            1.25f -> 1.5f
                            1.5f -> 2.0f
                            else -> 0.75f
                        },
                    )
                }
                SettingsRow("Размер субтитров", subtitleSize.toString()) {
                    onSubtitleSize(when (subtitleSize) { 40 -> 55; 55 -> 70; 70 -> 90; else -> 40 })
                }
                SettingsRow("Цвет субтитров", subtitleColor) {
                    onSubtitleColor(when (subtitleColor) { "#FFFFFF" -> "#FFD54F"; "#FFD54F" -> "#69F0AE"; else -> "#FFFFFF" })
                }
                SettingsRow("Фон субтитров", if (subtitleBackground == "#00000000") "Прозрачный" else "Полупрозрачный") {
                    onSubtitleBackground(if (subtitleBackground == "#00000000") "#80000000" else "#00000000")
                }
                SettingsRow("Стабилизация громкости", "Выкл") { /* визуальная настройка, как в референсе */ }
            }

            TvPanel.NONE -> Unit
        }
    }
}

@Composable
private fun Pill(text: String, fontSize: Int, color: Color = Color.White, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = 640.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PillBg)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun TvButton(
    text: String,
    selected: Boolean = false,
    accentOutline: Boolean = false,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val borderColor = if (selected || accentOutline) Accent else Color.White.copy(alpha = 0.28f)
    val bg = if (selected) Accent else Color(0xE614141A)
    val fg = if (selected) Color(0xFF16110B) else if (accentOutline) Accent else Color.White
    Box(
        Modifier
            .height(if (compact) 38.dp else 50.dp)
            .clip(RoundedCornerShape(if (compact) 10.dp else 12.dp))
            .background(bg)
            .border(1.2.dp, borderColor, RoundedCornerShape(if (compact) 10.dp else 12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 13.dp else 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TvPopup(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .width(430.dp)
            .heightIn(max = 560.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(PanelBg)
            .padding(14.dp),
    ) {
        Text(title, color = Dim, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun PopupRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) Accent else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(
            title,
            color = if (selected) Color(0xFF16110B) else Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                color = if (selected) Color(0xCC16110B) else Dim,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text("$value  ›", color = Dim, fontSize = 15.sp)
    }
}
