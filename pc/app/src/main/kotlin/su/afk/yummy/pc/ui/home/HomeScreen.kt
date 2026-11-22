// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay
import su.afk.yummy.pc.api.AnimeCard
import su.afk.yummy.pc.api.HeroSlide
import su.afk.yummy.pc.api.HomeContinueWatching
import su.afk.yummy.pc.api.HomeData
import su.afk.yummy.pc.api.HomeExtras
import su.afk.yummy.pc.api.HomeNewEpisode
import su.afk.yummy.pc.api.YaniHomeExtrasApi
import su.afk.yummy.pc.api.YaniRepository
import su.afk.yummy.pc.ui.components.ErrorView
import su.afk.yummy.pc.ui.components.LoadState
import su.afk.yummy.pc.ui.components.LoadingView
import su.afk.yummy.pc.ui.components.PosterCard
import su.afk.yummy.pc.ui.components.PosterImage
import su.afk.yummy.pc.ui.components.RatingBadge
import su.afk.yummy.pc.ui.components.SectionHeader
import su.afk.yummy.pc.ui.components.SmoothRow
import su.afk.yummy.pc.ui.theme.YummyPalette

/**
 * Главная: баннер-карусель, «Продолжить просмотр», новые серии и лента подборок.
 *
 * @param repository репозиторий yani для ленты и баннеров.
 * @param onOpen открыть страницу тайтла (постеры ленты, «Подробнее» на баннере).
 * @param onPlayHero кнопка «Смотреть» на баннере: запуск плеера сразу (id тайтла),
 *   без перехода на страницу тайтла.
 * @param onContinuePlay клик по карточке «Продолжить просмотр»: запуск плеера сразу
 *   (id тайтла, серия из карточки); серия может быть пустой — тогда плеер откроет последнюю.
 */
@Composable
fun HomeScreen(
    repository: YaniRepository,
    onOpen: (Int) -> Unit,
    onPlayHero: (Int) -> Unit,
    onContinuePlay: (Int, String) -> Unit = { _, _ -> },
) {
    var reloadKey by remember { mutableStateOf(0) }
    val extrasApi = remember { YaniHomeExtrasApi() }

    val state by produceState<LoadState<HomeData>>(LoadState.Loading, repository, reloadKey) {
        value = LoadState.Loading
        value = try {
            LoadState.Data(repository.home())
        } catch (e: Exception) {
            LoadState.Error(e.message ?: e.toString())
        }
    }

    // Блоки «Продолжить просмотр» и «Новые серии» грузятся отдельно от ленты:
    // без токена или при ошибке сети они просто не показываются, главная остаётся рабочей.
    val extras by produceState(HomeExtras(), extrasApi, reloadKey) {
        value = HomeExtras()
        value = extrasApi.homeExtras(force = reloadKey > 0)
    }

    when (val current = state) {
        is LoadState.Loading -> LoadingView("Загружаем главную…")
        is LoadState.Error -> ErrorView(current.message) { reloadKey++ }
        is LoadState.Data -> HomeContent(current.value, extras, onOpen, onPlayHero, onContinuePlay)
    }
}

@Composable
private fun HomeContent(
    data: HomeData,
    extras: HomeExtras,
    onOpen: (Int) -> Unit,
    onPlayHero: (Int) -> Unit,
    onContinuePlay: (Int, String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (data.hero.isNotEmpty()) {
            item { HeroCarousel(data.hero, onOpen, onPlayHero) }
        }
        // Порядок блоков как в ТВ-клиенте: сначала «продолжить», затем новые серии, потом лента.
        if (extras.continueWatching.isNotEmpty()) {
            item { ContinueWatchingRow(extras.continueWatching, onContinuePlay) }
        }
        if (extras.newEpisodesFromMyLists.isNotEmpty()) {
            item { NewEpisodesRow("Новые серии из ваших списков", extras.newEpisodesFromMyLists, onOpen) }
        }
        if (extras.newEpisodes.isNotEmpty()) {
            item { NewEpisodesRow("Новые серии", extras.newEpisodes, onOpen) }
        }
        items(data.rows) { row ->
            SectionHeader(row.title)
            SmoothRow(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp)) {
                items(row.items, key = { it.id }) { card ->
                    PosterCard(card, onClick = { onOpen(card.id) }, width = 186.dp, posterHeight = 262.dp)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * «Продолжить просмотр»: широкие карточки с обложкой, серией, прогрессом и временем.
 * Клик сразу запускает плеер на серии из карточки, а не открывает страницу тайтла.
 */
@Composable
private fun ContinueWatchingRow(
    entries: List<HomeContinueWatching>,
    onContinuePlay: (Int, String) -> Unit,
) {
    SectionHeader("Продолжить просмотр")
    SmoothRow(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp)) {
        items(entries, key = { it.animeId }) { entry ->
            ContinueWatchingCard(entry, onClick = { onContinuePlay(entry.animeId, entry.episode) })
        }
    }
    Spacer(Modifier.height(10.dp))
}

/** Ряд новых серий: постерная карточка с плашкой «Серия N». */
@Composable
private fun NewEpisodesRow(title: String, episodes: List<HomeNewEpisode>, onOpen: (Int) -> Unit) {
    SectionHeader(title)
    SmoothRow(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp)) {
        items(episodes, key = { it.animeId }) { episode ->
            NewEpisodeCard(episode, onClick = { onOpen(episode.animeId) })
        }
    }
    Spacer(Modifier.height(10.dp))
}

/** Широкая карточка «Продолжить просмотр»: кадр/постер, название, серия, прогресс и время. */
@Composable
private fun ContinueWatchingCard(item: HomeContinueWatching, onClick: () -> Unit) {
    Column(
        Modifier
            .width(ContinueCardWidth)
            .clip(RoundedCornerShape(14.dp))
            .background(YummyPalette.Surface)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(ContinueThumbnailHeight)) {
            PosterImage(item.screenshotUrl ?: item.posterUrl, Modifier.fillMaxSize())
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.Center,
            ) {
                Text("▶", style = MaterialTheme.typography.titleMedium, color = YummyPalette.Text)
            }
            // Полоса прогресса поверх нижнего края обложки — как у карточки ТВ-клиента.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(YummyPalette.SurfaceHover),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(item.progress)
                        .height(4.dp)
                        .background(YummyPalette.Accent),
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = YummyPalette.Text,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    episodeLabel(item.episode),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = YummyPalette.Accent,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    timeLabel(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = YummyPalette.TextDim,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Карточка новой серии: обычный постер и плашка «Серия N» поверх него. */
@Composable
private fun NewEpisodeCard(item: HomeNewEpisode, onClick: () -> Unit) {
    Box {
        PosterCard(
            card = AnimeCard(
                id = item.animeId,
                title = item.title,
                posterUrl = item.posterUrl,
                rating = null,
                year = null,
            ),
            onClick = onClick,
            width = 186.dp,
            posterHeight = 262.dp,
        )
        if (item.episode.isNotBlank()) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(YummyPalette.Accent)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    "Серия ${item.episode}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A0F07),
                )
            }
        }
    }
}

private val ContinueCardWidth = 264.dp
private val ContinueThumbnailHeight = 148.dp

private fun episodeLabel(episode: String): String =
    if (episode.isBlank()) "Серия" else "Серия $episode"

/** Время на карточке: «14:46 / 23:37», а если длительность неизвестна — позиция остановки. */
private fun timeLabel(item: HomeContinueWatching): String {
    if (item.durationSec <= 0) {
        return if (item.positionSec > 0) "Остановились на ${clockLabel(item.positionSec)}" else ""
    }
    return "${clockLabel(item.positionSec)} / ${clockLabel(item.durationSec)}"
}

/** Секунды в «мм:сс» или «ч:мм:сс». */
private fun clockLabel(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

@Composable
private fun HeroCarousel(hero: List<HeroSlide>, onOpen: (Int) -> Unit, onPlayHero: (Int) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { hero.size })

    LaunchedEffect(hero.size) {
        if (hero.size <= 1) return@LaunchedEffect
        while (true) {
            delay(8_000)
            val next = (pagerState.currentPage + 1) % hero.size
            pagerState.animateScrollToPage(next)
        }
    }

    Box(Modifier.fillMaxWidth().height(470.dp)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            HeroSlideView(hero[page], onOpen, onPlayHero)
        }
        if (hero.size > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(hero.size.coerceAtMost(10)) { index ->
                    Box(
                        Modifier
                            .size(if (index == pagerState.currentPage) 10.dp else 8.dp)
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) YummyPalette.Accent
                                else YummyPalette.TextFaint,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroSlideView(slide: HeroSlide, onOpen: (Int) -> Unit, onPlayHero: (Int) -> Unit) {
    Box(Modifier.fillMaxSize().background(YummyPalette.BackgroundElevated)) {
        PosterImage(slide.backdropUrl ?: slide.posterUrl, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to YummyPalette.Background.copy(alpha = 0.98f),
                        0.45f to YummyPalette.Background.copy(alpha = 0.75f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.6f to Color.Transparent,
                        1f to YummyPalette.Background,
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .width(620.dp)
                .padding(start = 36.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RatingBadge(slide.rating)
            Text(
                slide.title,
                style = MaterialTheme.typography.displayMedium,
                color = YummyPalette.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                slide.description,
                style = MaterialTheme.typography.bodyLarge,
                color = YummyPalette.TextDim,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onPlayHero(slide.id) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = YummyPalette.Accent,
                        contentColor = Color(0xFF1A0F07),
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(48.dp).width(210.dp),
                ) {
                    Text("▶  Смотреть", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { onOpen(slide.id) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(48.dp).width(190.dp),
                ) {
                    Text("Подробнее")
                }
            }
        }
    }
}
