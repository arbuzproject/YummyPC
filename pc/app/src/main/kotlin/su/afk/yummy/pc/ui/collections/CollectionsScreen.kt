// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import su.afk.yummy.pc.api.CollectionItem
import su.afk.yummy.pc.api.YaniRepository
import su.afk.yummy.pc.api.collection
import su.afk.yummy.pc.api.collections
import su.afk.yummy.pc.ui.components.ErrorView
import su.afk.yummy.pc.ui.components.LoadState
import su.afk.yummy.pc.ui.components.LoadingView
import su.afk.yummy.pc.ui.components.PosterCard
import su.afk.yummy.pc.ui.components.PosterImage
import su.afk.yummy.pc.ui.theme.YummyPalette

/** Сколько подборок экран запрашивает за раз (`limit` у `GET /collection`). */
private const val COLLECTIONS_LIMIT = 30

/** Сколько детальных запросов идёт параллельно при фоновой догрузке состава подборок. */
private const val DETAILS_CONCURRENCY = 4

/** Минимальная ширина карточки подборки в сетке. */
private val CollectionCardMinWidth = 260.dp

/** Высота полосы превью в карточке подборки. */
private val CollectionPreviewHeight = 140.dp

/** Ширина и высота карточек тайтлов в детальном виде — как в сетках топа и поиска. */
private val AnimeCardWidth = 210.dp
private val AnimePosterHeight = 296.dp

/** Сердце на плашке лайков: тот же ритм, что у ★ рейтинга. */
private val LikeHeart = Color(0xFFFF6E6E)

/**
 * Экран «Коллекции»: пользовательские подборки тайтлов yani.
 *
 * Сетка карточек: превью постеров из списка подборок, лайки и счётчик тайтлов. Состав подборки
 * список не отдаёт (поле `animes` приходит пустым), поэтому экран сразу показывает превью,
 * а карточки тайтлов догружает фоном через `GET /collection/{id}` — счётчик появляется следом.
 * Клик по подборке открывает её детальный вид (внутреннее состояние [selected]), где тайтлы
 * показываются общей [PosterCard]-сеткой; клик по тайтлу уходит в [onOpenAnime].
 *
 * @param repository репозиторий yani; подборки читаются через расширения
 *   [YaniRepository.collections] и [YaniRepository.collection] из `YaniCollectionsApi.kt`.
 * @param onOpenAnime открыть страницу тайтла по его id.
 */
@Composable
fun CollectionsScreen(
    repository: YaniRepository,
    onOpenAnime: (Int) -> Unit,
) {
    var listState by remember { mutableStateOf<LoadState<List<CollectionItem>>>(LoadState.Loading) }
    var hydratedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var hydrationDone by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    var selected by remember { mutableStateOf<CollectionItem?>(null) }
    var detailState by remember { mutableStateOf<LoadState<CollectionItem>?>(null) }
    var detailReloadKey by remember { mutableStateOf(0) }

    // Список подборок + фоновая догрузка состава: карточки не ждут деталей целиком.
    LaunchedEffect(reloadKey) {
        listState = LoadState.Loading
        hydratedIds = emptySet()
        hydrationDone = false
        selected = null
        detailState = null
        detailReloadKey = 0

        val loaded = try {
            repository.collections(limit = COLLECTIONS_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listState = LoadState.Error(e.message ?: e.toString())
            return@LaunchedEffect
        }

        var items = loaded
        listState = LoadState.Data(items)

        val loadedIds = mutableSetOf<Int>()
        loaded.chunked(DETAILS_CONCURRENCY).forEach { chunk ->
            val details = coroutineScope {
                chunk.map { item -> async { item.id to quietly { repository.collection(item.id) } } }.awaitAll()
            }
            val byId = details.mapNotNull { (id, detail) -> detail?.let { id to it } }.toMap()
            if (byId.isNotEmpty()) {
                items = items.map { item -> byId[item.id]?.let { mergeCollection(item, it) } ?: item }
                loadedIds += byId.keys
                hydratedIds = loadedIds.toSet()
                listState = LoadState.Data(items)
            }
        }
        hydrationDone = true
    }

    // Детали открытой подборки: если состав уже догружен — показываем сразу, иначе идём в сеть.
    LaunchedEffect(selected?.id, detailReloadKey) {
        val item = selected ?: return@LaunchedEffect
        if (item.animes.isNotEmpty() && detailReloadKey == 0) {
            detailState = LoadState.Data(item)
            return@LaunchedEffect
        }
        detailState = LoadState.Loading
        detailState = try {
            repository.collection(item.id)
                ?.let { LoadState.Data(mergeCollection(item, it)) }
                ?: LoadState.Error("Подборка не найдена на сервере yani")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadState.Error(e.message ?: e.toString())
        }
    }

    val current = selected
    if (current == null) {
        CollectionsList(
            state = listState,
            hydratedIds = hydratedIds,
            hydrationDone = hydrationDone,
            onRefresh = { reloadKey++ },
            onOpenCollection = { item ->
                selected = item
                detailState = if (item.animes.isNotEmpty()) LoadState.Data(item) else null
                detailReloadKey = 0
            },
        )
    } else {
        CollectionDetails(
            collection = current,
            state = detailState,
            onBack = {
                selected = null
                detailState = null
                detailReloadKey = 0
            },
            onRefresh = { detailReloadKey++ },
            onOpenAnime = onOpenAnime,
        )
    }
}

/** Сетка карточек подборок с заголовком экрана, кнопкой «Обновить» и состояниями загрузки. */
@Composable
private fun CollectionsList(
    state: LoadState<List<CollectionItem>>,
    hydratedIds: Set<Int>,
    hydrationDone: Boolean,
    onRefresh: () -> Unit,
    onOpenCollection: (CollectionItem) -> Unit,
) {
    val subtitle = when (state) {
        is LoadState.Data ->
            "Подборки тайтлов yani · ${plural(state.value.size, "подборка", "подборки", "подборок")}"
        else -> "Подборки тайтлов yani: превью постеров, лайки и полный состав"
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Коллекции", style = MaterialTheme.typography.headlineLarge, color = YummyPalette.Text)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = YummyPalette.TextDim)
            }
            OutlinedButton(onClick = onRefresh, enabled = state !is LoadState.Loading) { Text("Обновить") }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state) {
                is LoadState.Loading -> LoadingView("Загружаем коллекции…")
                is LoadState.Error -> ErrorView(state.message) { onRefresh() }
                is LoadState.Data -> if (state.value.isEmpty()) {
                    EmptyView(
                        title = "Коллекций пока нет",
                        hint = "Подборки создают пользователи yani — загляните позже или нажмите «Обновить».",
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = CollectionCardMinWidth),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(state.value, key = { it.id }) { collection ->
                            val hydrated =
                                collection.animes.isNotEmpty() || collection.id in hydratedIds
                            CollectionCard(
                                collection = collection,
                                countLabel = when {
                                    collection.animes.isNotEmpty() ->
                                        plural(collection.animes.size, "тайтл", "тайтла", "тайтлов")
                                    hydrated -> "Пустая подборка"
                                    !hydrationDone -> "Загрузка тайтлов…"
                                    else -> "Подборка тайтлов"
                                },
                                onClick = { onOpenCollection(collection) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Карточка подборки: полоса превью-постеров, лайки, название и счётчик тайтлов. */
@Composable
private fun CollectionCard(
    collection: CollectionItem,
    countLabel: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(YummyPalette.Surface)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(CollectionPreviewHeight)) {
            PreviewStrip(
                urls = collection.previewUrls.take(4),
                modifier = Modifier.fillMaxSize(),
            )
            // Мягкий переход превью в подпись и затемнение под плашкой лайков.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to YummyPalette.Surface,
                        ),
                    ),
            )
            LikesBadge(collection.likes, Modifier.align(Alignment.TopEnd).padding(10.dp))
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                collection.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = YummyPalette.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    countLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (collection.animes.isNotEmpty()) YummyPalette.TextDim else YummyPalette.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                if (collection.views > 0) {
                    Text(
                        plural(collection.views, "просмотр", "просмотра", "просмотров"),
                        style = MaterialTheme.typography.labelSmall,
                        color = YummyPalette.TextFaint,
                    )
                }
            }
        }
    }
}

/** Полоса превью (до 4 миниатюр): без превью — заглушка на весь блок. */
@Composable
private fun PreviewStrip(urls: List<String>, modifier: Modifier = Modifier) {
    if (urls.isEmpty()) {
        Box(modifier.background(YummyPalette.SurfaceHigh))
        return
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        urls.forEach { url ->
            PosterImage(url, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** Плашка с количеством лайков в углу превью — как рейтинг у [PosterCard]. */
@Composable
private fun LikesBadge(likes: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xCC101014))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("♥", color = LikeHeart, fontSize = 11.sp)
        Text(
            likes.toString(),
            color = YummyPalette.Text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Детальный вид подборки: название, описание и сетка тайтлов с переходом на страницу тайтла. */
@Composable
private fun CollectionDetails(
    collection: CollectionItem,
    state: LoadState<CollectionItem>?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenAnime: (Int) -> Unit,
) {
    val shown = (state as? LoadState.Data)?.value ?: collection

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(onClick = onBack, shape = RoundedCornerShape(12.dp)) { Text("← Назад") }
            Column(Modifier.weight(1f)) {
                Text(
                    shown.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = YummyPalette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    collectionSubtitle(shown),
                    style = MaterialTheme.typography.bodySmall,
                    color = YummyPalette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = onRefresh,
                shape = RoundedCornerShape(12.dp),
                enabled = state !is LoadState.Loading,
            ) { Text("Обновить") }
        }

        if (shown.description.isNotBlank()) {
            Text(
                shown.description,
                style = MaterialTheme.typography.bodySmall,
                color = YummyPalette.TextDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val current = state ?: LoadState.Loading) {
                is LoadState.Loading -> LoadingView("Загружаем подборку…")
                is LoadState.Error -> ErrorView(current.message) { onRefresh() }
                is LoadState.Data -> if (current.value.animes.isEmpty()) {
                    EmptyView(
                        title = "В подборке пока нет тайтлов",
                        hint = "Автор ещё не добавил аниме — загляните позже или нажмите «Обновить».",
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = AnimeCardWidth),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(current.value.animes, key = { it.id }) { card ->
                            PosterCard(
                                card = card,
                                onClick = { onOpenAnime(card.id) },
                                width = AnimeCardWidth,
                                posterHeight = AnimePosterHeight,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Подпись детального вида: число тайтлов, лайки, просмотры и автор. */
private fun collectionSubtitle(collection: CollectionItem): String = buildList {
    if (collection.animes.isNotEmpty()) {
        add(plural(collection.animes.size, "тайтл", "тайтла", "тайтлов"))
    }
    add(plural(collection.likes, "лайк", "лайка", "лайков"))
    if (collection.views > 0) {
        add(plural(collection.views, "просмотр", "просмотра", "просмотров"))
    }
    collection.ownerName?.let { add("автор: $it") }
}.joinToString(" · ")

/** Пустое состояние: нет подборок на экране или нет тайтлов внутри подборки. */
@Composable
private fun EmptyView(title: String, hint: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("✦", style = MaterialTheme.typography.displayMedium, color = YummyPalette.TextFaint)
            Text(title, style = MaterialTheme.typography.titleLarge, color = YummyPalette.Text)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = YummyPalette.TextDim,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Сливает карточку из списка с детальным ответом: состав и описание берём из деталей,
 * превью и просмотры — из списка, если детали их не вернули.
 */
private fun mergeCollection(card: CollectionItem, details: CollectionItem): CollectionItem = details.copy(
    title = details.title.ifBlank { card.title },
    likes = maxOf(details.likes, card.likes),
    animes = details.animes.ifEmpty { card.animes },
    description = details.description.ifBlank { card.description },
    views = maxOf(details.views, card.views),
    posterPreviews = details.posterPreviews.ifEmpty { card.posterPreviews },
    ownerName = details.ownerName ?: card.ownerName,
)

/** Выполняет запрос, не роняя общую загрузку списка: сбой одной подборки — просто нет деталей. */
private suspend fun <T> quietly(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** Русское склонение: `plural(14, "тайтл", "тайтла", "тайтлов")` → «14 тайтлов». */
private fun plural(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
    return "$count $word"
}
