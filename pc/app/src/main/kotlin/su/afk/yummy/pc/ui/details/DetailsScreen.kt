// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import su.afk.yummy.pc.api.AnimeCard
import su.afk.yummy.pc.api.AnimePage
import su.afk.yummy.pc.api.EpisodeCard
import su.afk.yummy.pc.api.ExternalSource
import su.afk.yummy.pc.api.ExternalSourcesApi
import su.afk.yummy.pc.api.LocalLibrary
import su.afk.yummy.pc.api.VideoRef
import su.afk.yummy.pc.api.YaniAnimeExtrasApi
import su.afk.yummy.pc.api.YaniApi
import su.afk.yummy.pc.api.YaniRepository
import su.afk.yummy.pc.player.PlaybackRequest
import su.afk.yummy.pc.player.PlayerActions
import su.afk.yummy.pc.ui.components.ErrorView
import su.afk.yummy.pc.ui.components.LoadState
import su.afk.yummy.pc.ui.components.LoadingView
import su.afk.yummy.pc.ui.components.PosterCard
import su.afk.yummy.pc.ui.components.PosterImage
import su.afk.yummy.pc.ui.components.SectionHeader
import su.afk.yummy.pc.ui.components.SmoothRow
import su.afk.yummy.pc.ui.theme.YummyPalette

/**
 * Страница тайтла: постер, метаданные, описание, жанры, скриншоты, озвучки,
 * сетка серий, похожие тайтлы и порядок просмотра.
 *
 * @param repository репозиторий yani: страница тайтла и сброс кэша после смены списка.
 * @param animeId id тайтла на yani.
 * @param autoPlay открыть тайтл и сразу запустить первую серию (аргумент `--autoplay`).
 * @param autoPlayer какая озвучка/плеер должны запуститься по умолчанию (аргумент `--player`).
 * @param onPlay запустить воспроизведение (страница уступает место экрану плеера).
 * @param onOpenAnime открыть другой тайтл (похожие и «порядок просмотра»).
 * @param onBack вернуться на предыдущий экран.
 * @param onOpenSearch открыть поиск по названию жанра: клик по чипу жанра.
 */
@Composable
fun DetailsScreen(
    repository: YaniRepository,
    animeId: Int,
    autoPlay: Boolean = false,
    autoPlayer: String = "",
    onPlay: (PlaybackRequest) -> Unit,
    onOpenAnime: (Int) -> Unit,
    onBack: () -> Unit,
    onOpenSearch: (String) -> Unit = {},
    onOpenComments: (Int, String) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val api = remember { YaniApi() }
    val extras = remember { YaniAnimeExtrasApi() }
    var status by remember(animeId) { mutableStateOf<String?>(null) }
    var preparing by remember(animeId) { mutableStateOf(false) }
    var playJob by remember(animeId) { mutableStateOf<Job?>(null) }
    var subscribeDialogOpen by remember(animeId) { mutableStateOf(false) }
    var screenshotIndex by remember(animeId) { mutableStateOf<Int?>(null) }

    val state by produceState<LoadState<AnimePage>>(LoadState.Loading, animeId) {
        value = try {
            LoadState.Data(repository.page(animeId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            LoadState.Error(e.message ?: e.toString())
        }
    }
    // Похожие тайтлы — отдельная ручка, грузим параллельно с деталями. Ошибка не критична:
    // секция просто не появится, страница тайтла останется рабочей.
    val similar by produceState<List<AnimeCard>>(emptyList(), animeId) {
        value = try {
            extras.recommendations(animeId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun playEpisode(page: AnimePage, videos: List<VideoRef>, preferredPlayer: String = autoPlayer) {
        val ordered = videos.sortedBy { it.episode.toIntOrNull() ?: Int.MAX_VALUE }
        val video = ordered.firstOrNull { it.player.equals(preferredPlayer, ignoreCase = true) }
            ?: ordered.firstOrNull { it.dubbing == page.dubbings.firstOrNull()?.name }
            ?: ordered.firstOrNull()
            ?: return
        preparing = true
        status = null
        scope.launch {
            try {
                PlayerActions.resolve(video, page.title, page.id, videos, page.episodes).fold(
                    onSuccess = { onPlay(it) },
                    onFailure = { status = "Не удалось получить поток: ${it.message}" },
                )
            } finally {
                preparing = false
            }
        }
    }

    when (val current = state) {
        is LoadState.Loading -> LoadingView("Загружаем тайтл…")
        is LoadState.Error -> ErrorView(current.message) { status = null }
        is LoadState.Data -> {
            val page = current.value
            val externalSources by produceState<List<ExternalSource>>(emptyList(), page.id) {
                val episode = page.episodes.firstOrNull()?.number?.toIntOrNull() ?: 1
                value = runCatching { ExternalSourcesApi().sources(page.id, episode) }.getOrDefault(emptyList())
            }
            // Локальное состояние видео: подписка меняет `subscribed`, и колокольчик должен
            // обновиться сразу, без перезагрузки страницы. Остальные поля не меняются.
            var videos by remember(page.id) { mutableStateOf(page.videos) }
            var listHref by remember(page.id) { mutableStateOf(page.currentListHref) }
            var isFav by remember(page.id) { mutableStateOf(page.isFavorite) }
            // Прогреваем поток самой популярной озвучки: «Смотреть» откроется без ожидания.
            LaunchedEffect(page.id) {
                val popular = page.dubbings.maxByOrNull { it.videos.size }?.videos?.firstOrNull()
                    ?: page.videos.firstOrNull()
                if (popular != null) runCatching { PlayerActions.prefetch(popular) }
            }
            LaunchedEffect(page.id, autoPlay) {
                if (autoPlay) {
                    page.episodes.firstOrNull()?.let { playEpisode(page, it.videos) }
                }
            }

            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                ) {
                    TextButton(onClick = onBack) { Text("← Назад") }
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        Column(Modifier.weight(1f)) {
                            TitleWithSubscriptions(
                                title = page.title,
                                hasSubscriptions = videos.any { it.subscribed },
                                onOpenSubscriptions = { subscribeDialogOpen = true },
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                page.ageRating?.let { Chip(it) }
                                page.year?.let { Chip(it.toString()) }
                                page.typeName?.let { Chip(it) }
                                page.status?.let { Chip(it) }
                                page.views?.let { Chip("$it просмотров") }
                            }
                            if (page.episodesCount != null) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "Вышло: ${page.airedCount ?: "?"} из ${page.episodesCount?.takeIf { it > 0 } ?: "?"}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = YummyPalette.Text,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(page.description, style = MaterialTheme.typography.bodyLarge, color = YummyPalette.TextDim)
                            if (page.genres.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Жанры",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = YummyPalette.TextDim,
                                )
                                Spacer(Modifier.height(6.dp))
                                GenreChips(page.genres, onOpenSearch)
                            }
                            Spacer(Modifier.height(18.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = { page.episodes.firstOrNull()?.let { playEpisode(page, it.videos) } },
                                    enabled = page.episodes.isNotEmpty(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = YummyPalette.Accent,
                                        contentColor = Color(0xFF1A0F07),
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(52.dp).width(330.dp),
                                ) {
                                    Text("▶  Смотреть", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                                var listMenuOpen by remember { mutableStateOf(false) }
                                val statusLabel = listStatusLabel(listHref, isFav)
                                val statusColor = listStatusColor(listHref, isFav)
                                Box {
                                    Button(
                                        onClick = { listMenuOpen = true },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = statusColor,
                                            contentColor = Color(0xFF16110B),
                                        ),
                                        modifier = Modifier.height(52.dp).width(220.dp),
                                    ) { Text(statusLabel, fontWeight = FontWeight.Bold) }
                                    DropdownMenu(
                                        expanded = listMenuOpen,
                                        onDismissRequest = { listMenuOpen = false },
                                    ) {
                                        LIST_STATUS_OPTIONS.forEach { (listId, label) ->
                                            DropdownMenuItem(
                                                text = {
                                                    val isCurrent = if (listId < 0) isFav else listStatusHrefFor(listId) == listHref
                                                    Text(
                                                        if (isCurrent) "✓ $label" else label,
                                                        color = if (isCurrent) YummyPalette.Accent else YummyPalette.Text,
                                                    )
                                                },
                                                onClick = {
                                                    listMenuOpen = false
                                                    scope.launch {
                                                        try {
                                                            if (listId < 0) {
                                                                api.setFavorite(page.id, true)
                                                            } else {
                                                                api.setListStatus(page.id, listId)
                                                            }
                                                            repository.invalidateCache()
                                                            status = "Список: $label"
                                                        } catch (cancelled: CancellationException) {
                                                            throw cancelled
                                                        } catch (error: Exception) {
                                                            // Без сети действие не теряется: очередь
                                                            // LocalLibrary отправит его, когда сервер ответит.
                                                            if (listId < 0) {
                                                                LocalLibrary.queueFavorite(page.id, true)
                                                            } else {
                                                                LocalLibrary.queueListStatus(page.id, listId)
                                                            }
                                                            val reason = error.message ?: "ошибка сети"
                                                            status = "Не удалось: $reason. Сохранено локально — отправим позже"
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                        if (listHref != null || isFav) {
                                            DropdownMenuItem(
                                                text = { Text("Удалить из списков", color = Color(0xFFFF8A65)) },
                                                onClick = {
                                                    listMenuOpen = false
                                                    scope.launch {
                                                        runCatching {
                                                            api.removeFromList(page.id)
                                                            if (isFav) api.setFavorite(page.id, false)
                                                        }.onSuccess {
                                                            listHref = null
                                                            isFav = false
                                                            repository.invalidateCache()
                                                            status = "Удалено из списков"
                                                        }.onFailure {
                                                            status = "Не удалось удалить: ${it.message}"
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            status?.let {
                                Spacer(Modifier.height(10.dp))
                                Text(it, color = YummyPalette.Accent, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Column(Modifier.width(300.dp)) {
                            Box(Modifier.fillMaxWidth().height(440.dp).clip(RoundedCornerShape(18.dp))) {
                                PosterImage(page.posterUrl, Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.height(10.dp))
                            RatingLine(page)
                        }
                    }

                    if (page.screenshots.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        SectionHeader("Скриншоты")
                        SmoothRow(contentPadding = PaddingValues(vertical = 4.dp), spacing = 12.dp) {
                            itemsIndexed(page.screenshots) { index, url ->
                                Box(
                                    Modifier
                                        .width(ScreenshotWidth)
                                        .height(ScreenshotHeight)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { screenshotIndex = index },
                                ) {
                                    PosterImage(url, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }

                    if (page.dubbings.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        SectionHeader("Озвучки и балансеры")
                        SmoothRow(contentPadding = PaddingValues(vertical = 4.dp), spacing = 10.dp) {
                            items(page.dubbings) { group ->
                                Column(
                                    Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(YummyPalette.Surface)
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        group.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = YummyPalette.Text,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        group.videos.distinctBy { it.player }.forEach { video ->
                                            Row(
                                                Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(YummyPalette.SurfaceHigh)
                                                    .clickable {
                                                        playEpisode(
                                                            page = page,
                                                            videos = group.videos.filter {
                                                                it.player == video.player && it.dubbing == video.dubbing
                                                            },
                                                            preferredPlayer = video.player,
                                                        )
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                            ) {
                                                Text(
                                                    video.player.ifBlank { "источник" },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = YummyPalette.Text,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    SectionHeader("Серии")
                    if (page.episodes.isEmpty()) {
                        Text(
                            "Серии не найдены",
                            style = MaterialTheme.typography.bodyMedium,
                            color = YummyPalette.TextDim,
                        )
                    } else {
                        EpisodeGrid(
                            episodes = page.episodes,
                            onPlayEpisode = { playEpisode(page, it.videos) },
                        )
                    }

                    if (similar.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        SectionHeader("Похожие тайтлы")
                        SmoothRow(contentPadding = PaddingValues(vertical = 4.dp), spacing = 14.dp) {
                            items(similar, key = { it.id }) { card ->
                                PosterCard(
                                    card = card,
                                    onClick = { onOpenAnime(card.id) },
                                    width = 170.dp,
                                    posterHeight = 240.dp,
                                )
                            }
                        }
                    }

                    if (page.viewingOrder.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        SectionHeader("Порядок просмотра")
                        SmoothRow(contentPadding = PaddingValues(vertical = 4.dp)) {
                            items(page.viewingOrder, key = { it.id }) { item ->
                                Column(
                                    Modifier
                                        .width(180.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(YummyPalette.Surface)
                                        .clickable { onOpenAnime(item.id) }
                                        .padding(8.dp),
                                ) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(10.dp))) {
                                        PosterImage(item.posterUrl, Modifier.fillMaxSize())
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        item.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = YummyPalette.Text,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(22.dp))
                    Button(
                        onClick = { onOpenComments(page.id, page.title) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = YummyPalette.SurfaceHigh,
                            contentColor = YummyPalette.Text,
                        ),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text("Комментарии (${page.commentsCount})", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(24.dp))
                }

                if (preparing) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xCC000000))
                            // Поглощаем клики — кнопки страницы под прокладкой не нажимаются.
                            .pointerInput(Unit) { detectTapGestures { } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            CircularProgressIndicator(color = YummyPalette.Accent, modifier = Modifier.size(40.dp))
                            Text(
                                "Готовим видеопоток…",
                                color = YummyPalette.Text,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Это может занять несколько секунд",
                                color = YummyPalette.TextDim,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = {
                                playJob?.cancel()
                                playJob = null
                                preparing = false
                            }) { Text("Отмена", color = YummyPalette.Text) }
                        }
                    }
                }

                if (subscribeDialogOpen) {
                    SubscribeDialog(
                        page = page,
                        videos = videos,
                        onVideosChange = { videos = it },
                        onDismiss = { subscribeDialogOpen = false },
                    )
                }
                screenshotIndex?.let { index ->
                    ScreenshotLightbox(
                        screenshots = page.screenshots,
                        startIndex = index,
                        onDismiss = { screenshotIndex = null },
                    )
                }
            }
        }
    }
}

/** Название тайтла и колокольчик подписки на новые серии озвучки. */
@Composable
private fun TitleWithSubscriptions(
    title: String,
    hasSubscriptions: Boolean,
    onOpenSubscriptions: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, color = YummyPalette.Text)
        IconButton(
            onClick = onOpenSubscriptions,
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (hasSubscriptions) YummyPalette.AccentSoft else YummyPalette.SurfaceHigh),
        ) {
            Icon(
                imageVector = if (hasSubscriptions) Icons.Outlined.NotificationsActive else Icons.Outlined.Notifications,
                contentDescription = "Подписки на озвучку",
                tint = if (hasSubscriptions) YummyPalette.Accent else YummyPalette.TextDim,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Рейтинги одной строкой под постером: `★ 7.9 · КП — · Shiki 6.5 · MAL 6.7`. */
@Composable
private fun RatingLine(page: AnimePage) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = YummyPalette.RatingStar)) { append("★") }
            append(
                " ${fmt(page.rating)} · КП ${fmt(page.kinopoiskRating)} · " +
                    "Shiki ${fmt(page.shikimoriRating)} · MAL ${fmt(page.myAnimeListRating)}",
            )
        },
        color = YummyPalette.Accent,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Жанры чипами: клик открывает поиск по жанру (подключает `onOpenSearch`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreChips(genres: List<String>, onOpenSearch: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        genres.forEach { genre ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(YummyPalette.SurfaceHigh)
                    .clickable { onOpenSearch(genre) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(genre, style = MaterialTheme.typography.labelMedium, color = YummyPalette.Text)
            }
        }
    }
}

/** Сетка серий: карточки с обложкой, номером, длительностью и просмотрами. */
@Composable
private fun EpisodeGrid(
    episodes: List<EpisodeCard>,
    onPlayEpisode: (EpisodeCard) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val spacing = 10.dp
        val columns = ((maxWidth + spacing) / (EpisodeCardWidth + spacing)).toInt().coerceAtLeast(1)
        val rows = (episodes.size + columns - 1) / columns
        val gridHeight = EpisodeCardHeight * rows + spacing * (rows - 1).coerceAtLeast(0)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            modifier = Modifier.fillMaxWidth().height(gridHeight),
        ) {
            items(episodes, key = { it.number }) { episode ->
                EpisodeGridCard(episode = episode, onClick = { onPlayEpisode(episode) })
            }
        }
    }
}

/** Одна карточка серии в сетке. */
@Composable
private fun EpisodeGridCard(
    episode: EpisodeCard,
    onClick: () -> Unit,
) {
    val duration = durationLabel(episode.durationSec)
    val views = viewsLabel(episode.videos.maxOfOrNull { it.views ?: 0 }?.takeIf { it > 0 })
    Column(
        Modifier
            .fillMaxWidth()
            .height(EpisodeCardHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(YummyPalette.Surface)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(EpisodeThumbnailHeight)) {
            PosterImage(episode.thumbnailUrl, Modifier.fillMaxSize())
            if (duration != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC101014))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(duration, style = MaterialTheme.typography.labelSmall, color = YummyPalette.Text)
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                episode.title ?: "Серия ${episode.number}",
                style = MaterialTheme.typography.titleSmall,
                color = YummyPalette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                views ?: "без просмотров",
                style = MaterialTheme.typography.labelSmall,
                color = YummyPalette.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Полноэкранный просмотр скриншота поверх окна приложения.
 *
 * Это Compose-диалог (`Dialog` + `usePlatformDefaultWidth = false`), а не отдельное окно:
 * он рисуется слоем поверх всего окна, поэтому перекрывает и рейку навигации, и страницу.
 * Навигация ◀/▶ и клавиши ←/→ листают кадры по кругу, `Esc`/кнопка ✕ закрывают просмотр.
 */
@Composable
private fun ScreenshotLightbox(
    screenshots: List<String>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    if (screenshots.isEmpty()) return
    var index by remember(screenshots) { mutableStateOf(startIndex.coerceIn(0, screenshots.lastIndex)) }
    val focusRequester = remember { FocusRequester() }
    // Фокус просим после первого кадра: до раскладки FocusRequester ещё не привязан к узлу,
    // а без фокуса не работают стрелки и Esc.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    fun previous() {
        index = (index - 1 + screenshots.size) % screenshots.size
    }

    fun next() {
        index = (index + 1) % screenshots.size
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF08080B))
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> {
                            onDismiss()
                            true
                        }

                        Key.DirectionLeft -> {
                            previous()
                            true
                        }

                        Key.DirectionRight -> {
                            next()
                            true
                        }

                        else -> false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            PosterImage(
                screenshots[index],
                Modifier.fillMaxSize().padding(horizontal = 88.dp, vertical = 56.dp),
                contentScale = ContentScale.Fit,
            )
            Text(
                "${index + 1} / ${screenshots.size}",
                style = MaterialTheme.typography.titleSmall,
                color = YummyPalette.TextDim,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Закрыть просмотр",
                    tint = YummyPalette.Text,
                    modifier = Modifier.size(28.dp),
                )
            }
            if (screenshots.size > 1) {
                LightboxArrow(
                    icon = Icons.Outlined.ChevronLeft,
                    description = "Предыдущий скриншот",
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                    onClick = ::previous,
                )
                LightboxArrow(
                    icon = Icons.Outlined.ChevronRight,
                    description = "Следующий скриншот",
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                    onClick = ::next,
                )
            }
        }
    }
}

@Composable
private fun LightboxArrow(
    icon: ImageVector,
    description: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color(0xCC1C1C22))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = YummyPalette.Text, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun Chip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(YummyPalette.SurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = YummyPalette.Text)
    }
}

private fun fmt(value: Double?): String =
    if (value == null || value <= 0.0) "—" else "%.1f".format(value)

/** Длительность серии в секундах → «24 мин» (округление до минуты). */
private fun durationLabel(seconds: Int?): String? = seconds?.takeIf { it > 0 }?.let {
    "${(it + 30) / 60} мин"
}

/** Просмотры серии: «1,2 тыс. просмотров», «3 млн просмотров». */
private fun viewsLabel(views: Int?): String? = views?.takeIf { it > 0 }?.let { count ->
    val compact = when {
        count >= 1_000_000 -> "%.1f".format(count / 1_000_000.0).replace('.', ',') + " млн"
        count >= 10_000 -> "${count / 1000} тыс."
        count >= 1_000 -> "%.1f".format(count / 1000.0).replace('.', ',') + " тыс."
        else -> count.toString()
    }
    "$compact просмотров"
}

/** Обложка скриншота в ряду: 16:9, как кадр из серии. */
private val ScreenshotWidth = 220.dp
private val ScreenshotHeight = 124.dp

/** Карточка серии в сетке: обложка 220×124 + подпись. */
private val EpisodeCardWidth = 220.dp
private val EpisodeThumbnailHeight = 124.dp
private val EpisodeCardHeight = 178.dp

/** Статусы списка: id (для PUT /anime/{id}/list) и подпись. -1 — «Любимое». */
private val LIST_STATUS_OPTIONS = listOf(
    0 to "Смотрю",
    1 to "В планах",
    2 to "Просмотрено",
    3 to "Брошено",
    5 to "Отложено",
    -1 to "Любимое",
)
/** Подпись кнопки списка по текущему статусу. */
private fun listStatusLabel(href: String?, isFav: Boolean): String = when {
    isFav -> "Любимое"
    href == null -> "В список"
    else -> LIST_STATUS_OPTIONS.firstOrNull { listStatusHrefFor(it.first) == href }?.second ?: "В список"
}

/** Цвет кнопки списка: палитра статусов yani. */
private fun listStatusColor(href: String?, isFav: Boolean): Color = when {
    isFav -> Color(0xFFD65CE0)
    href == "watch_now" -> Color(0xFFF4665C)
    href == "will" -> Color(0xFF8E7BE8)
    href == "watched" -> Color(0xFF35C77B)
    href == "lost" -> Color(0xFF7C8A99)
    href == "postponed" -> Color(0xFFF2C744)
    else -> Color(0xFF23232B)
}

/** href статуса по id списка yani. */
private fun listStatusHrefFor(listId: Int): String = when (listId) {
    0 -> "watch_now"
    1 -> "will"
    2 -> "watched"
    3 -> "lost"
    5 -> "postponed"
    else -> "fav"
}