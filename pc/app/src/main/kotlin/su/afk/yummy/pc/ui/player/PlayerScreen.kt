// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.player

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import su.afk.yummy.pc.player.EmbeddedMpv
import su.afk.yummy.pc.player.MpvPlaybackState
import su.afk.yummy.pc.player.PlaybackRequest
import su.afk.yummy.pc.player.PlayerActions
import su.afk.yummy.pc.player.rememberChoice
import su.afk.yummy.pc.ui.LocalWindowControl
import su.afk.yummy.pc.ui.theme.YummyPalette
import java.awt.Canvas
import kotlin.math.max
import kotlin.math.min

private enum class PlayerPanel { NONE, SOURCES }

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
    var controlsVisible by remember { mutableStateOf(true) }
    var qualityDialogOpen by remember { mutableStateOf(false) }
    // URL, для которого просмотр уже отправлен на сайт: не спамим повторными PUT.
    var reportedForUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val videoApi = remember { YaniVideoApi() }

    val canvas = remember { Canvas() }
    var player by remember { mutableStateOf<EmbeddedMpv?>(null) }
    // Плашка загрузки — отдельное окно ОС: mpv перекрывает Compose-слой.
    val loadingOverlay = remember { PlayerLoadingOverlay(windowControl.window) }
    LaunchedEffect(state.loaded, error, request.url) {
        if (!state.loaded && error == null) {
            loadingOverlay.show(if (request.qualityLabel.isNotBlank()) "Загрузка потока (${request.qualityLabel})…" else "Загрузка видеопотока…")
        } else {
            loadingOverlay.hide()
        }
    }

    LaunchedEffect(request.url) {
        player?.dispose()
        // Сбрасываем состояние прошлого потока: оверлей загрузки появляется сразу,
        // а отчёт о просмотре не срабатывает по старой позиции на новом URL.
        state = MpvPlaybackState()
        error = null
        qualityDialogOpen = false
        var hwnd = 0L
        repeat(40) {
            if (hwnd == 0L && canvas.isDisplayable) {
                hwnd = runCatching { com.sun.jna.Native.getComponentID(canvas) }.getOrDefault(0L)
            }
            if (hwnd == 0L) delay(100)
        }
        if (hwnd == 0L) {
            error = "не удалось получить окно видео (HWND)"
            return@LaunchedEffect
        }
        val embedded = EmbeddedMpv(
            hwnd = hwnd,
            headers = request.headers,
            referer = request.referer,
            title = request.title,
        )
        player = embedded
        embedded.start(request.url).onFailure { error = it.message ?: it.toString() }
        // Запоминаем выбранную озвучку/балансер для быстрого запуска с главной.
        runCatching { rememberChoice(request) }

        // Пока смотрится текущая серия, заранее разрешаем поток следующей — автопереход
        // и кнопка «Серия →» откроются из кэша PlayerActions, без сетевого ожидания.
        request.nextEpisodeVideo()?.let { next ->
            scope.launch { PlayerActions.prefetch(next) }
        }
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

    // Автопропуск опенинга/эндинга с настраиваемой задержкой.
    LaunchedEffect(state.positionSec, request.openingSkip, request.endingSkip) {
        if (!state.loaded || state.paused || state.ended) return@LaunchedEffect
        val pos = state.positionSec
        val delaySec = YummyConfig.skipDelaySec.toDouble()
        val opening = request.openingSkip
        val ending = request.endingSkip
        val skipTo = when {
            YummyConfig.autoSkipOpening && opening != null &&
                pos >= opening.startSec + delaySec && pos < opening.endSec - 2.0 -> opening.endSec.toDouble()
            YummyConfig.autoSkipEnding && ending != null &&
                pos >= ending.startSec + delaySec && pos < ending.endSec - 2.0 -> ending.endSec.toDouble()
            else -> null
        }
        if (skipTo != null) player?.seekTo(skipTo)
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

    // Панель управления прячется через 3 секунды бездействия (диалог качества не трогаем).
    LaunchedEffect(Unit) {
        var lastPointer = runCatching { java.awt.MouseInfo.getPointerInfo()?.location }.getOrNull()
        var lastActive = System.currentTimeMillis()
        while (true) {
            delay(250)
            val pointer = runCatching { java.awt.MouseInfo.getPointerInfo()?.location }.getOrNull()
            if (pointer != null && pointer != lastPointer) {
                lastPointer = pointer
                lastActive = System.currentTimeMillis()
                controlsVisible = true
            }
            if (System.currentTimeMillis() - lastActive > 3000 &&
                !state.paused &&
                panel == PlayerPanel.NONE &&
                !qualityDialogOpen
            ) {
                controlsVisible = false
            }
        }
    }

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

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            SwingPanel(modifier = Modifier.fillMaxSize(), background = Color.Black, factory = { canvas })
            if (!state.loaded && error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CircularProgressIndicator(
                            color = YummyPalette.Accent,
                            modifier = Modifier.size(38.dp),
                        )
                        Text(
                            "Загрузка потока…",
                            color = YummyPalette.Text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
                                player?.start(request.url)?.onFailure { error = it.message ?: it.toString() }
                            }
                            PanelButton("Закрыть") { onClose() }
                        }
                    }
                }
            }
        }

        when (panel) {
            PlayerPanel.SOURCES -> SourcePanel(
                request = request,
                externalSources = externalSources.orEmpty(),
                externalStatus = externalStatus,
                onSelect = ::switchTo,
                onSelectExternal = ::switchToExternal,
                onClose = { panel = PlayerPanel.NONE },
            )

            PlayerPanel.NONE -> if (controlsVisible) {
                ControlBar(
                    active = request,
                    state = state,
                    speed = speed,
                    dragPosition = dragPosition,
                    switchStatus = switchStatus,
                    isFullscreen = windowControl.isFullscreen,
                    onTogglePause = { player?.togglePause() },
                    onSeek = { dragPosition = it },
                    onSeekFinished = {
                        dragPosition?.let { player?.seekTo(it.toDouble()) }
                        dragPosition = null
                    },
                    onSpeed = {
                        speed = when (speed) {
                            1.0f -> 1.25f
                            1.25f -> 1.5f
                            1.5f -> 2.0f
                            else -> 1.0f
                        }
                        player?.setSpeed(speed)
                    },
                    onQuality = { if (request.qualities.isNotEmpty()) qualityDialogOpen = true },
                    onSources = { panel = PlayerPanel.SOURCES },
                    onFullscreen = { windowControl.toggleFullscreen() },
                    onSkipOpening = { request.openingSkip?.let { player?.seekTo(it.endSec.toDouble()) } },
                    onSkipEnding = { request.endingSkip?.let { player?.seekTo(it.endSec.toDouble()) } },
                    onNext = { playNextEpisode() },
                    onClose = {
                        // Финальный отчёт о просмотре: композиция сейчас закроется,
                        // поэтому PUT уходит в scope, переживающий экран (reportWatchProgress).
                        if (state.positionSec > 30.0) {
                            reportWatchProgress(videoApi, request.videoId, state.positionSec, state.durationSec)
                        }
                        onClose()
                    },
                )
            }
        }
    }

    if (qualityDialogOpen && request.qualities.isNotEmpty()) {
        QualityDialog(
            qualities = request.qualities,
            currentUrl = request.url,
            currentLabel = request.qualityLabel,
            onSelect = { url, label ->
                qualityDialogOpen = false
                reportedForUrl = null
                val embedded = player
                val position = state.positionSec
                if (embedded != null) {
                    // Меняем поток «на лету» в том же процессе mpv: рендер-поверхность
                    // не пересоздаётся, поэтому экран не чернеет.
                    if (embedded.loadFile(url).isSuccess) {
                        embedded.seekTo(position)
                    }
                }
                request = request.copy(url = url, qualityLabel = label)
            },
            onDismiss = { qualityDialogOpen = false },
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
