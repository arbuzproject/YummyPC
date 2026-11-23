// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerMoveFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import su.afk.yummy.pc.api.EpisodeCard
import su.afk.yummy.pc.api.ExternalQuality
import su.afk.yummy.pc.api.ExternalSource
import su.afk.yummy.pc.api.ExternalSourcesApi
import su.afk.yummy.pc.api.VideoRef
import su.afk.yummy.pc.api.YaniVideoApi
import su.afk.yummy.pc.api.YummyConfig
import su.afk.yummy.pc.player.LibmpvRenderPlayer
import su.afk.yummy.pc.player.MpvPlaybackState
import su.afk.yummy.pc.player.PlaybackRequest
import su.afk.yummy.pc.player.PlayerActions
import su.afk.yummy.pc.player.rememberChoice
import su.afk.yummy.pc.ui.LocalWindowControl
import su.afk.yummy.pc.ui.theme.YummyPalette
import java.awt.Canvas
import kotlin.math.max
import kotlin.math.min

private enum class PlayerPanel { NONE, SOURCES, SETTINGS }

/**
 * Фоновые best-effort отчёты о просмотре. При `onClose` композиция плеера уничтожается,
 * а `rememberCoroutineScope` отменяется вместе с ней, поэтому финальный PUT уходит сюда.
 */
private val watchReportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Синхронизация просмотра с сайтом: `PUT /video/{videoId}` (best-effort, не блокирует UI).
 * Гость и видео без id (например, внешний балансер) пропускаются — сервер ответил бы 401/403.
 */
private fun reportWatchProgress(
    videoApi: YaniVideoApi,
    videoId: Int,
    positionSec: Double,
    durationSec: Double,
) {
    // Длительность обязательна: без неё сервер не пересчитает «просмотрено» (как в YummyTV),
    // а прогресс с нулевой длительностью может обнулить запись «Продолжить просмотр».
    if (videoId <= 0 || durationSec <= 0.0 || !YummyConfig.isAuthorized) return
    watchReportScope.launch {
        runCatching { videoApi.markWatched(videoId, positionSec.toInt(), durationSec.toInt()) }
    }
}

/** Карточка следующей серии в плейлисте (по номеру текущей). */
private fun PlaybackRequest.previousEpisode(): EpisodeCard? {
    val index = episodes.indexOfFirst { it.number == episodeNumber }
    if (index <= 0) return null
    return episodes.getOrNull(index - 1)
}
private fun PlaybackRequest.nextEpisode(): EpisodeCard? {
    val index = episodes.indexOfFirst { it.number == episodeNumber }
    if (index < 0) return null
    return episodes.getOrNull(index + 1)
}

/** Видео серии на той же озвучке, иначе первый доступный балансер. */
private fun PlaybackRequest.videoForEpisode(episode: EpisodeCard): VideoRef? =
    episode.videos.firstOrNull { it.dubbing == dubbingName } ?: episode.videos.firstOrNull()

/** Видео следующей серии — для предзагрузки и автоперехода. */
private fun PlaybackRequest.nextEpisodeVideo(): VideoRef? =
    nextEpisode()?.let { videoForEpisode(it) }

/**
 * Встроенный плеер: mpv рисует видео в дочернем окне AWT-канвы.
 * Панель управления прячется при бездействии и возвращается при движении мыши.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    active: PlaybackRequest,
    onClose: () -> Unit,
    initialPanel: String = "",
) {
    val windowControl = LocalWindowControl.current
    var request by remember { mutableStateOf(active) }
    var panel by remember {
        mutableStateOf(
            when (initialPanel) {
                "sources" -> PlayerPanel.SOURCES
                else -> PlayerPanel.NONE
            },
        )
    }
    var state by remember { mutableStateOf(MpvPlaybackState()) }
    var error by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableStateOf(1.0f) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    var switchStatus by remember { mutableStateOf<String?>(null) }
    // Внешние балансеры (сервер): null — ещё не запрашивали. Грузятся лениво, при первом
    // открытии панели «Озвучка и балансер», и кэшируются до смены тайтла/серии.
    var externalSources by remember(request.animeId, request.episodeNumber) {
        mutableStateOf<List<ExternalSource>?>(null)
    }
    var externalStatus by remember(request.animeId, request.episodeNumber) {
        mutableStateOf<String?>(null)
    }
    var volume by remember { mutableStateOf(YummyConfig.volume) }
    var lastVolume by remember { mutableStateOf(YummyConfig.volume.takeIf { it > 0 } ?: 100) }
    var subtitleSize by remember { mutableStateOf(55) }
    var subtitleColor by remember { mutableStateOf("#FFFFFF") }
    var subtitleBackground by remember { mutableStateOf("#80000000") }
    // URL, для которого просмотр уже отправлен на сайт: не спамим повторными PUT.
    var reportedForUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val videoApi = remember { YaniVideoApi() }

    var player by remember { mutableStateOf<LibmpvRenderPlayer?>(null) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var videoSize by remember { mutableStateOf(IntSize(1280, 720)) }

    LaunchedEffect(request.url) {
        player?.dispose()
        player = null
        frame = null
        // Сбрасываем состояние прошлого потока: отчёт о просмотре не срабатывает
        // по старой позиции на новом URL.
        state = MpvPlaybackState()
        error = null
        runCatching {
            val instance = LibmpvRenderPlayer()
            instance.setVideoSize(videoSize.width, videoSize.height)
            player = instance
            instance.start(request.url, request.headers, request.referer)
                .onFailure { error = it.message ?: it.toString() }
            instance.setVolume(volume)
        }.onFailure { error = it.message ?: it.toString() }
        // Запоминаем выбранную озвучку/балансер для быстрого запуска с главной.
        runCatching { rememberChoice(request) }

        // Пока смотрится текущая серия, заранее разрешаем поток следующей — автопереход
        // и кнопка «Серия →» откроются из кэша PlayerActions, без сетевого ожидания.
        request.nextEpisodeVideo()?.let { next ->
            scope.launch { PlayerActions.prefetch(next) }
        }
    }
    LaunchedEffect(player) {
        player?.videoFrames?.collect { frame = it }
    }
    LaunchedEffect(player) { player?.state?.collect { state = it } }
    DisposableEffect(Unit) {
        onDispose {
            player?.dispose()
            player = null
        }
    }

    // Внешние балансеры (сервер): AniLibria и другие. Запрос уходит только когда панель
    // реально открыта, а не на старте плеера: старт больше не ждёт чужой сервер.
    LaunchedEffect(panel, request.animeId, request.episodeNumber) {
        if (panel != PlayerPanel.SOURCES || externalSources != null) return@LaunchedEffect
        val episode = request.episodeNumber.toIntOrNull()
        if (request.animeId <= 0 || episode == null) {
            externalSources = emptyList()
            return@LaunchedEffect
        }
        externalStatus = "Ищу внешние балансеры…"
        runCatching { ExternalSourcesApi().sources(request.animeId, episode) }.fold(
            onSuccess = { found ->
                externalSources = found
                externalStatus = if (found.isEmpty()) "AniLibria: релиз не найден" else null
            },
            onFailure = {
                externalSources = emptyList()
                externalStatus = "Сервер внешних балансеров недоступен"
            },
        )
    }

    // Автопропуск: независимые задержки для опенинга/эндинга, отсчёт и отмена без перемотки.
    var cancelledSkipZone by remember { mutableStateOf<String?>(null) }
    var skippedSkipZone by remember { mutableStateOf<String?>(null) }
    val openingZone = request.openingSkip
    val endingZone = request.endingSkip
    val openingCountdown = openingZone?.let { zone ->
        if (YummyConfig.autoSkipOpening && cancelledSkipZone != "opening" &&
            state.positionSec >= zone.startSec && state.positionSec < zone.endSec
        ) {
            kotlin.math.ceil(zone.startSec + YummyConfig.openingSkipDelaySec - state.positionSec).toInt().coerceAtLeast(0)
        } else null
    }
    val endingCountdown = endingZone?.let { zone ->
        if (YummyConfig.autoSkipEnding && cancelledSkipZone != "ending" &&
            state.positionSec >= zone.startSec && state.positionSec < zone.endSec
        ) {
            kotlin.math.ceil(zone.startSec + YummyConfig.endingSkipDelaySec - state.positionSec).toInt().coerceAtLeast(0)
        } else null
    }
    LaunchedEffect(state.positionSec, request.openingSkip, request.endingSkip, cancelledSkipZone) {
        if (!state.loaded || state.paused || state.ended) return@LaunchedEffect
        val pos = state.positionSec
        val opening = request.openingSkip
        val ending = request.endingSkip
        if (opening != null && YummyConfig.autoSkipOpening && cancelledSkipZone != "opening" && skippedSkipZone != "opening" &&
            pos >= opening.startSec + YummyConfig.openingSkipDelaySec && pos < opening.endSec - 2.0
        ) {
            player?.seekTo(opening.endSec.toDouble())
            skippedSkipZone = "opening"
        } else if (ending != null && YummyConfig.autoSkipEnding && cancelledSkipZone != "ending" && skippedSkipZone != "ending" &&
            pos >= ending.startSec + YummyConfig.endingSkipDelaySec && pos < ending.endSec - 2.0
        ) {
            player?.seekTo(ending.endSec.toDouble())
            skippedSkipZone = "ending"
        }
        // За пределами зоны сбрасываем отмену и отметку пропуска.
        if (opening != null && (pos < opening.startSec || pos >= opening.endSec)) {
            if (cancelledSkipZone == "opening") cancelledSkipZone = null
            if (skippedSkipZone == "opening" && pos >= opening.endSec) skippedSkipZone = null
        }
        if (ending != null && (pos < ending.startSec || pos >= ending.endSec)) {
            if (cancelledSkipZone == "ending") cancelledSkipZone = null
            if (skippedSkipZone == "ending" && pos >= ending.endSec) skippedSkipZone = null
        }
    }
    // Синхронизация просмотра с сайтом: один PUT на текущий поток, когда серия реально
    // смотрится — 30 секунд или 10% длительности (порог «Продолжить просмотр» yani).
    LaunchedEffect(request.url, request.videoId, state.loaded, state.positionSec, state.durationSec) {
        if (!state.loaded || error != null) return@LaunchedEffect
        if (request.videoId <= 0 || reportedForUrl == request.url) return@LaunchedEffect
        val duration = state.durationSec
        if (duration <= 0.0) return@LaunchedEffect
        if (state.positionSec < min(30.0, duration * 0.1)) return@LaunchedEffect
        reportedForUrl = request.url
        reportWatchProgress(videoApi, request.videoId, state.positionSec, duration)
    }

    // Панель управления больше не рисуется Compose-слоем (его перекрывает окно mpv):
    // управление вынесено в прозрачный оверлей-окно PlayerOverlayWindow.

    fun switchTo(video: VideoRef) {
        switchStatus = "Переключаю на «${video.dubbing} · ${video.player}»…"
        panel = PlayerPanel.NONE
        reportedForUrl = null
        scope.launch {
            PlayerActions.resolve(video, request.pageTitle, request.animeId, request.episodeVideos, request.episodes).fold(
                onSuccess = { request = it; switchStatus = null },
                onFailure = { switchStatus = "Не удалось: ${it.message ?: it::class.simpleName}" },
            )
        }
    }

    fun switchToExternal(source: ExternalSource, qualityUrl: String, qualityLabel: String?) {
        request = request.copy(
            url = qualityUrl,
            headers = emptyMap(),
            referer = null,
            subtitle = "AniLibria (сервер) · ${qualityLabel ?: "auto"}",
            playerName = "AniLibria (сервер)",
            dubbingName = "AniLibria (сервер)",
            // Внешний балансер не привязан к видео yani — прогресс на сайт не отправляем.
            videoId = 0,
        )
        panel = PlayerPanel.NONE
        externalStatus = null
        reportedForUrl = null
    }

    fun playNextEpisode() {
        val next = request.nextEpisode() ?: return
        val video = request.videoForEpisode(next) ?: return
        switchStatus = "Открываю серию ${next.number}…"
        reportedForUrl = null
        scope.launch {
            PlayerActions.resolve(video, request.pageTitle, request.animeId, next.videos, request.episodes).fold(
                onSuccess = { request = it; switchStatus = null },
                onFailure = { switchStatus = "Не удалось: ${it.message ?: it::class.simpleName}" },
            )
        }
    }

    LaunchedEffect(state.ended) {
        if (state.ended && YummyConfig.autoplayNextEpisode) playNextEpisode()
    }

    // Горячие клавиши: пробел — пауза, ←/→ — ±10 секунд, ↑/↓ — ±5% громкости.
    DisposableEffect(Unit) {
        val dispatcher = java.awt.KeyEventDispatcher { event ->
            if (event.id != java.awt.event.KeyEvent.KEY_PRESSED) {
                false
            } else {
                when (event.keyCode) {
                    java.awt.event.KeyEvent.VK_SPACE -> {
                        player?.togglePause()
                        true
                    }
                    java.awt.event.KeyEvent.VK_LEFT -> {
                        player?.seekTo((state.positionSec - 10.0).coerceAtLeast(0.0))
                        true
                    }
                    java.awt.event.KeyEvent.VK_RIGHT -> {
                        val max = if (state.durationSec > 0) state.durationSec else state.positionSec + 10.0
                        player?.seekTo((state.positionSec + 10.0).coerceAtMost(max))
                        true
                    }
                    java.awt.event.KeyEvent.VK_UP -> {
                        volume = (volume + 5).coerceAtMost(130)
                        player?.setVolume(volume)
                        YummyConfig.updatePreferences(volume = volume)
                        true
                    }
                    java.awt.event.KeyEvent.VK_DOWN -> {
                        volume = (volume - 5).coerceAtLeast(0)
                        player?.setVolume(volume)
                        YummyConfig.updatePreferences(volume = volume)
                        true
                    }
                    else -> false
                }
            }
        }
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        onDispose {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
        }
    }

    fun playPreviousEpisode() {
        val previous = request.previousEpisode() ?: return
        val video = request.videoForEpisode(previous) ?: return
        switchStatus = "Открываю серию ${previous.number}…"
        reportedForUrl = null
        scope.launch {
            PlayerActions.resolve(video, request.pageTitle, request.animeId, previous.videos, request.episodes).fold(
                onSuccess = { request = it; switchStatus = null },
                onFailure = { switchStatus = "Не удалось: ${it.message ?: it::class.simpleName}" },
            )
        }
    }

    fun cycleQuality() {
        val list = request.qualities
        if (list.size < 2) return
        val currentIndex = list.indexOfFirst { it.second == request.url }.takeIf { it >= 0 } ?: 0
        val next = list[(currentIndex + 1) % list.size]
        reportedForUrl = null
        val embedded = player
        val position = state.positionSec
        if (embedded != null && embedded.loadFile(next.second)) {
            embedded.seekTo(position)
        }
        request = request.copy(url = next.second, qualityLabel = next.first)
        switchStatus = "Качество: ${next.first}"
    }

    // Видео из libmpv render + телевизионный оверлей внутри окна (как в тестовой Chromium-версии).
    var tvPanel by remember { mutableStateOf(TvPanel.NONE) }
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible, state.paused, tvPanel) {
        if (controlsVisible && !state.paused && tvPanel == TvPanel.NONE) {
            kotlinx.coroutines.delay(2600)
            controlsVisible = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerMoveFilter(onMove = {
                controlsVisible = true
                false
            }),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged {
                    videoSize = it
                    player?.setVideoSize(it.width, it.height)
                },
        ) {
            frame?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            if (!state.loaded && error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CircularProgressIndicator(color = YummyPalette.Accent, modifier = Modifier.size(38.dp))
                        Text("Загрузка потока…", color = YummyPalette.Text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            error?.let { message ->
                Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            "Не удалось открыть поток: $message",
                            color = Color(0xFFFF8A65),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PanelButton("Повторить") {
                                error = null
                                player?.start(request.url, request.headers, request.referer)?.onFailure { error = it.message ?: it.toString() }
                            }
                            PanelButton("Закрыть") { onClose() }
                        }
                    }
                }
            }
        }

        TvPlayerOverlay(
            title = request.pageTitle.ifBlank { request.title },
            episode = request.episodeNumber,
            dubbing = request.dubbingName,
            playerName = request.playerName,
            qualityLabel = request.qualityLabel,
            state = state,
            volume = volume,
            speed = speed,
            subtitleSize = subtitleSize,
            subtitleColor = subtitleColor,
            subtitleBackground = subtitleBackground,
            controlsVisible = controlsVisible || state.paused || tvPanel != TvPanel.NONE,
            panel = tvPanel,
            dubbings = request.episodeVideos
                .groupBy { it.dubbing.ifBlank { "Без озвучки" } }
                .map { (name, videos) -> name to videos.map { it.player }.filter { it.isNotBlank() }.distinct() },
            qualities = request.qualities,
            openingSkip = request.openingSkip,
            endingSkip = request.endingSkip,
            isFullscreen = windowControl.isFullscreen,
            onTogglePause = {
                player?.togglePause()
                controlsVisible = true
            },
            onSeek = {
                player?.seekTo(it.toDouble())
                controlsVisible = true
            },
            onPrev = { playPreviousEpisode() },
            onNext = { playNextEpisode() },
            onSkip = { seconds ->
                player?.seekTo(seconds)
                controlsVisible = true
            },
            onCancelAutoSkip = if (openingCountdown != null || endingCountdown != null) {
                {
                    cancelledSkipZone = if (openingCountdown != null) "opening" else "ending"
                    controlsVisible = true
                }
            } else null,
            openingCountdown = openingCountdown,
            endingCountdown = endingCountdown,
            onTogglePanel = { target ->
                tvPanel = if (tvPanel == target) TvPanel.NONE else target
                controlsVisible = true
            },
            onSelectDubbing = { name ->
                request.episodeVideos.firstOrNull { it.dubbing == name }?.let { switchTo(it) }
                tvPanel = TvPanel.NONE
            },
            onSelectPlayer = { name ->
                (request.episodeVideos.firstOrNull { it.player == name && it.dubbing == request.dubbingName }
                    ?: request.episodeVideos.firstOrNull { it.player == name })?.let { switchTo(it) }
                tvPanel = TvPanel.NONE
            },
            onSelectQuality = { label ->
                request.qualities.firstOrNull { it.first == label }?.let { (qualityName, qualityUrl) ->
                    player?.loadFile(qualityUrl)
                    request = request.copy(url = qualityUrl, qualityLabel = qualityName)
                }
                tvPanel = TvPanel.NONE
            },
            onSpeed = { newSpeed ->
                speed = newSpeed
                player?.setSpeed(newSpeed)
            },
            onVolume = { value ->
                val clamped = value.coerceIn(0, 130)
                if (clamped > 0) lastVolume = clamped
                volume = clamped
                player?.setVolume(clamped)
                YummyConfig.updatePreferences(volume = clamped)
            },
            onMute = {
                if (volume > 0) {
                    lastVolume = volume
                    volume = 0
                } else {
                    volume = lastVolume.coerceAtLeast(1)
                }
                player?.setVolume(volume)
                YummyConfig.updatePreferences(volume = volume)
            },
            onSubtitleSize = { size ->
                subtitleSize = size
                player?.setProperty("sub-font-size", size)
            },
            onSubtitleColor = { color ->
                subtitleColor = color
                player?.setProperty("sub-color", color)
            },
            onSubtitleBackground = { color ->
                subtitleBackground = color
                player?.setProperty("sub-back-color", color)
            },
            onFullscreen = { windowControl.toggleFullscreen() },
            onClose = {
                if (state.positionSec > 30.0) {
                    reportWatchProgress(videoApi, request.videoId, state.positionSec, state.durationSec)
                }
                onClose()
            },
        )
    }

}

@Composable
private fun ControlBar(
    active: PlaybackRequest,
    state: MpvPlaybackState,
    speed: Float,
    dragPosition: Float?,
    switchStatus: String?,
    isFullscreen: Boolean,
    onTogglePause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onSpeed: () -> Unit,
    onQuality: () -> Unit,
    onSources: () -> Unit,
    onFullscreen: () -> Unit,
    onSkipOpening: () -> Unit,
    onSkipEnding: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val duration = max(1.0, state.durationSec)
    val position = dragPosition ?: state.positionSec.toFloat()
    Column(
        Modifier
            .fillMaxWidth()
            .background(YummyPalette.BackgroundElevated)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(YummyPalette.Accent)
                    .clickable(onClick = onTogglePause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = null,
                    tint = Color(0xFF1A0F07),
                    modifier = Modifier.size(26.dp),
                )
            }
            Column(Modifier.width(300.dp)) {
                Text(
                    active.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = YummyPalette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    active.subtitle ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = YummyPalette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(formatTime(position.toDouble()), color = YummyPalette.TextDim, style = MaterialTheme.typography.labelSmall)
            Slider(
                value = (position.toDouble() / duration).toFloat().coerceIn(0f, 1f),
                onValueChange = { onSeek((it * duration).toFloat()) },
                onValueChangeFinished = onSeekFinished,
                modifier = Modifier.weight(1f),
            )
            Text(formatTime(duration), color = YummyPalette.TextDim, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pos = state.positionSec
            if (active.openingSkip?.let { pos >= it.startSec && pos < it.endSec } == true) {
                PanelButton("Пропустить опенинг") { onSkipOpening() }
            }
            if (active.endingSkip?.let { pos >= it.startSec && pos < it.endSec } == true) {
                PanelButton("Пропустить эндинг") { onSkipEnding() }
            }
            Spacer(Modifier.weight(1f))
            switchStatus?.let { Text(it, color = YummyPalette.Accent, style = MaterialTheme.typography.labelSmall) }
            PanelButton("Озвучка и балансер") { onSources() }
            if (active.qualities.size > 1) {
                PanelButton("Качество: ${active.qualityLabel.ifBlank { "auto" }}") { onQuality() }
            }
            PanelButton("x$speed") { onSpeed() }
            PanelButton(if (isFullscreen) "Свернуть" else "Полный экран") { onFullscreen() }
            PanelButton("Серия →") { onNext() }
            PanelButton("Закрыть") { onClose() }
        }
    }
}

@Composable
private fun SourcePanel(
    request: PlaybackRequest,
    externalSources: List<ExternalSource>,
    externalStatus: String?,
    onSelect: (VideoRef) -> Unit,
    onSelectExternal: (ExternalSource, String, String?) -> Unit,
    onClose: () -> Unit,
) {
    val grouped = request.episodeVideos.groupBy { it.dubbing.ifBlank { "Без озвучки" } }
    Column(
        Modifier
            .fillMaxWidth()
            .height(400.dp)
            .background(YummyPalette.BackgroundElevated)
            .padding(20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Озвучка и балансер · серия ${request.episodeNumber}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = YummyPalette.Text,
                )
                Text(
                    "Выберите озвучку, затем балансер — плеер перезапустится на выбранном источнике.",
                    style = MaterialTheme.typography.labelSmall,
                    color = YummyPalette.TextDim,
                )
            }
            PanelButton("Назад к управлению") { onClose() }
        }
        Spacer(Modifier.height(10.dp))
        externalStatus?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = YummyPalette.TextDim)
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(externalSources) { source ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(YummyPalette.Surface)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Озвучка ${source.dubbing}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = YummyPalette.Text,
                            )
                            Text("сервер", style = MaterialTheme.typography.labelSmall, color = YummyPalette.Accent)
                        }
                        Text(source.title, style = MaterialTheme.typography.labelSmall, color = YummyPalette.TextDim)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val qualities = source.qualities.ifEmpty { listOf(ExternalQuality("auto", source.url)) }
                        qualities.forEach { quality ->
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(YummyPalette.AccentSoft)
                                    .clickable { onSelectExternal(source, quality.url, quality.quality) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(quality.quality, style = MaterialTheme.typography.labelMedium, color = YummyPalette.Accent)
                            }
                        }
                    }
                }
            }
            items(grouped.entries.toList(), key = { it.key }) { (dubbing, list) ->
                val isCurrent = dubbing == request.dubbingName.ifBlank { "Без озвучки" }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isCurrent) YummyPalette.SurfaceHover else YummyPalette.Surface)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(dubbing, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = YummyPalette.Text)
                        if (isCurrent) Text("сейчас", style = MaterialTheme.typography.labelSmall, color = YummyPalette.Accent)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.distinctBy { it.player }.forEach { video ->
                            val selected = isCurrent && video.player == request.playerName
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) YummyPalette.Accent else YummyPalette.SurfaceHigh)
                                    .clickable { onSelect(video) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    video.player.ifBlank { "источник" },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) Color(0xFF1A0F07) else YummyPalette.Text,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(YummyPalette.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = YummyPalette.Text)
    }
}

private fun formatTime(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
/** Панель управления плеером внутри окна приложения (без отдельных окон поверх экрана). */
@Composable
private fun InAppControlBar(
    active: PlaybackRequest,
    state: MpvPlaybackState,
    speed: Float,
    volume: Int,
    subtitleSize: Int,
    switchStatus: String?,
    isFullscreen: Boolean,
    onTogglePause: () -> Unit,
    onSeek: (Float) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekBy: (Double) -> Unit,
    onSpeed: () -> Unit,
    onVolume: (Int) -> Unit,
    onMute: () -> Unit,
    onQuality: () -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    onFullscreen: () -> Unit,
    onClose: () -> Unit,
) {
    val duration = max(1.0, state.durationSec)
    Column(
        Modifier
            .fillMaxWidth()
            .background(YummyPalette.BackgroundElevated)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    active.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = YummyPalette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    active.subtitle.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = YummyPalette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            switchStatus?.let {
                Text(it, color = YummyPalette.Accent, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatTime(state.positionSec), color = YummyPalette.TextDim, style = MaterialTheme.typography.labelSmall)
            Slider(
                value = (state.positionSec / duration).toFloat().coerceIn(0f, 1f),
                onValueChange = { onSeek((it * duration).toFloat()) },
                modifier = Modifier.weight(1f),
            )
            Text(formatTime(state.durationSec), color = YummyPalette.TextDim, style = MaterialTheme.typography.labelSmall)
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Предыдущая серия", tint = YummyPalette.Text)
            }
            IconButton(onClick = { onSeekBy(-10.0) }) {
                Text("−10", color = YummyPalette.Text, style = MaterialTheme.typography.labelMedium)
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(YummyPalette.Accent)
                    .clickable(onClick = onTogglePause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = "Пауза",
                    tint = Color(0xFF16110B),
                )
            }
            IconButton(onClick = { onSeekBy(10.0) }) {
                Text("+10", color = YummyPalette.Text, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Следующая серия", tint = YummyPalette.Text)
            }
            Spacer(Modifier.width(6.dp))
            PanelButton("Озвучка и балансер") { onSources() }
            PanelButton("Качество: ${active.qualityLabel.ifBlank { "auto" }}") { onQuality() }
            PanelButton("x$speed") { onSpeed() }
            PanelButton("Субтитры: $subtitleSize") { onSettings() }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onMute) {
                Icon(
                    if (volume == 0) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = "Звук",
                    tint = YummyPalette.Text,
                )
            }
            Slider(
                value = volume.coerceIn(0, 130).toFloat(),
                onValueChange = { onVolume(it.toInt()) },
                valueRange = 0f..130f,
                modifier = Modifier.width(120.dp),
            )
            Text("$volume%", color = YummyPalette.TextDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Настройки плеера", tint = YummyPalette.Text)
            }
            IconButton(onClick = onFullscreen) {
                Icon(
                    if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = "Полный экран",
                    tint = YummyPalette.Text,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть плеер", tint = Color(0xFFFF8A65))
            }
        }
    }
}

/** Настройки плеера внутри приложения: скорость и субтитры. */
@Composable
private fun PlayerSettingsPanel(
    speed: Float,
    volume: Int,
    subtitleSize: Int,
    subtitleColor: String,
    subtitleBackground: String,
    onSpeed: (Float) -> Unit,
    onSubtitleSize: (Int) -> Unit,
    onSubtitleColor: (String) -> Unit,
    onSubtitleBackground: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(YummyPalette.BackgroundElevated)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Настройки плеера", style = MaterialTheme.typography.titleMedium, color = YummyPalette.Text)
            PanelButton("Назад к управлению") { onClose() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Скорость", color = YummyPalette.TextDim, modifier = Modifier.width(150.dp))
            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { value ->
                PanelButton(if (value == speed) "✓ ${value}x" else "${value}x") { onSpeed(value) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Размер субтитров", color = YummyPalette.TextDim, modifier = Modifier.width(150.dp))
            listOf(40, 55, 70, 90).forEach { value ->
                PanelButton(if (value == subtitleSize) "✓ $value" else "$value") { onSubtitleSize(value) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Цвет субтитров", color = YummyPalette.TextDim, modifier = Modifier.width(150.dp))
            listOf("#FFFFFF", "#FFD54F", "#69F0AE").forEach { value ->
                PanelButton(if (value == subtitleColor) "✓ $value" else value) { onSubtitleColor(value) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Фон субтитров", color = YummyPalette.TextDim, modifier = Modifier.width(150.dp))
            listOf("#00000000", "#80000000", "#FF000000").forEach { value ->
                val label = when (value) { "#00000000" -> "Прозрачный"; "#80000000" -> "Полупрозрачный"; else -> "Чёрный" }
                PanelButton(if (value == subtitleBackground) "✓ $label" else label) { onSubtitleBackground(value) }
            }
        }
        Text("Громкость: $volume% · горячие клавиши: пробел, ←/→ ±10 с, ↑/↓ ±5% громкости", color = YummyPalette.TextDim, style = MaterialTheme.typography.labelSmall)
    }
}
