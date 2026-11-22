// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.news

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import su.afk.yummy.pc.api.AnimeCard
import su.afk.yummy.pc.api.NewsCategory
import su.afk.yummy.pc.api.NewsPost
import su.afk.yummy.pc.api.NewsPostDetails
import su.afk.yummy.pc.api.NewsSort
import su.afk.yummy.pc.api.YaniNewsApi
import su.afk.yummy.pc.ui.components.ErrorView
import su.afk.yummy.pc.ui.components.LoadState
import su.afk.yummy.pc.ui.components.LoadingView
import su.afk.yummy.pc.ui.components.PosterCard
import su.afk.yummy.pc.ui.theme.YummyPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Рубрики новостей yani в порядке сайта.
 *
 * Сервер отдаёт тот же список (`GET /posts/categories`), но экран стартует с этих значений:
 * чипы видны сразу, а если справочник не загрузится, фильтрация продолжит работать.
 * Обратите внимание: у рубрики «Интервью» uri — `interview` (в живом API без «s»).
 */
private val DEFAULT_NEWS_CATEGORIES = listOf(
    NewsCategory(id = 1, title = "Новости сайта", uri = "site_news"),
    NewsCategory(id = 2, title = "Новости аниме", uri = "anime_news"),
    NewsCategory(id = 3, title = "Статьи", uri = "articles"),
    NewsCategory(id = 4, title = "Интервью", uri = "interview"),
)

/** Размер страницы ленты: столько же просим у сервера и столько же добавляем по «Загрузить ещё». */
private const val NEWS_PAGE_SIZE = 20

/** Ширина растра для большой карточки (в пикселях, с запасом под масштаб интерфейса Windows). */
private const val FEATURED_IMAGE_WIDTH_PX = 800

/** Ширина растра для компактной карточки. */
private const val COMPACT_IMAGE_WIDTH_PX = 480

/** Ширина растра для картинки в детальном просмотре. */
private const val DETAIL_IMAGE_WIDTH_PX = 1100

/** Превью с соотношением сторон меньше этого считаем вертикальным (постер) и показываем целиком. */
private const val PORTRAIT_ASPECT = 1.15f

/**
 * Экран «Новости»: лента публикаций yani с сортировкой («Новые» / «Лучшие» / «Старые»),
 * рубриками («Все», «Новости сайта», «Новости аниме», «Статьи», «Интервью») и пагинацией
 * по `skip`. По клику открывается публикация целиком с текстом и связанными тайтлами.
 *
 * Данные берутся из [YaniNewsApi] (кэш ответов 5–10 минут), состояния — те же
 * [LoadState] / [LoadingView] / [ErrorView], что и на остальных экранах.
 *
 * @param onOpenAnime открыть страницу тайтла по id: вызывается из блока «Связанные аниме».
 */
@Composable
fun NewsScreen(onOpenAnime: (Int) -> Unit = {}) {
    val api = remember { YaniNewsApi() }

    var categories by remember { mutableStateOf(DEFAULT_NEWS_CATEGORIES) }
    var sort by remember { mutableStateOf(NewsSort.NEW) }
    var category by remember { mutableStateOf<NewsCategory?>(null) }
    var state by remember { mutableStateOf<LoadState<List<NewsPost>>>(LoadState.Loading) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var loadMoreError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var openedPostId by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    // Состояние прокрутки ленты живёт на уровне экрана: возврат из публикации оставляет ленту
    // на том же месте, а смена рубрики/сортировки прокручивает её к началу.
    val feedListState = rememberLazyListState()

    // Справочник рубрик не критичен: при ошибке остаёмся на встроенном списке.
    LaunchedEffect(Unit) {
        val loaded = runCatching { api.categories() }.getOrNull()
        if (!loaded.isNullOrEmpty()) categories = loaded
    }

    // Лента перезагружается только при смене рубрики/сортировки или ручном повторе. Возврат из
    // публикации не считается новой загрузкой: экран помнит, какую ленту уже показывал.
    var loadedSignature by remember { mutableStateOf<String?>(null) }
    var loadedReloadKey by remember { mutableStateOf(-1) }
    LaunchedEffect(category?.uri, sort, reloadKey) {
        val signature = "${category?.uri.orEmpty()}|${sort.apiValue}"
        if (signature == loadedSignature && reloadKey == loadedReloadKey) return@LaunchedEffect
        loadedSignature = signature
        loadedReloadKey = reloadKey
        hasMore = false
        loadMoreError = null
        state = LoadState.Loading
        state = try {
            val page = api.posts(categoryUri = category?.uri, sort = sort, limit = NEWS_PAGE_SIZE, skip = 0)
            hasMore = page.size >= NEWS_PAGE_SIZE
            // Новая рубрика/сортировка начинается с первой карточки; запрос прокрутки не ждёт
            // композиции списка (в отличие от scrollToItem) и применяется при первом измерении.
            feedListState.requestScrollToItem(0)
            LoadState.Data(page)
        } catch (e: Exception) {
            LoadState.Error(e.message ?: e.toString())
        }
    }

    val openId = openedPostId
    if (openId != null) {
        NewsDetailsView(
            api = api,
            postId = openId,
            onBack = { openedPostId = null },
            onOpenAnime = onOpenAnime,
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Новости", style = MaterialTheme.typography.headlineLarge, color = YummyPalette.Text)
                Text(
                    "Публикации yani: новости сайта и аниме, статьи, интервью",
                    style = MaterialTheme.typography.bodySmall,
                    color = YummyPalette.TextDim,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NewsSort.entries.forEach { option ->
                    SortChip(
                        label = option.label(),
                        selected = sort == option,
                        onClick = { if (sort != option) sort = option },
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryChip(
                label = "Все",
                selected = category == null,
                onClick = { if (category != null) category = null },
            )
            categories.forEach { item ->
                CategoryChip(
                    label = item.title,
                    selected = category?.uri == item.uri,
                    onClick = { if (category?.uri != item.uri) category = item },
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (val current = state) {
                is LoadState.Loading -> LoadingView("Загружаем новости…")

                is LoadState.Error -> ErrorView(current.message) { reloadKey++ }

                is LoadState.Data -> {
                    if (current.value.isEmpty()) {
                        EmptyNewsView(Modifier.fillMaxSize())
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            state = feedListState,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(bottom = 24.dp),
                        ) {
                            itemsIndexed(current.value, key = { _, post -> post.id }) { index, post ->
                                if (index == 0) {
                                    FeaturedNewsCard(post = post, onClick = { openedPostId = post.id })
                                } else {
                                    CompactNewsCard(post = post, onClick = { openedPostId = post.id })
                                }
                            }
                            if (hasMore) {
                                item(key = "load-more") {
                                    Column(
                                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        loadMoreError?.let { message ->
                                            Text(
                                                "Не удалось загрузить ещё: $message",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = YummyPalette.TextDim,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                if (loadingMore) return@OutlinedButton
                                                loadingMore = true
                                                loadMoreError = null
                                                val loaded = (state as? LoadState.Data)?.value.orEmpty()
                                                scope.launch {
                                                    runCatching {
                                                        api.posts(
                                                            categoryUri = category?.uri,
                                                            sort = sort,
                                                            limit = NEWS_PAGE_SIZE,
                                                            skip = loaded.size,
                                                        )
                                                    }.onSuccess { page ->
                                                        val known = loaded.mapTo(HashSet()) { it.id }
                                                        state = LoadState.Data(loaded + page.filterNot { it.id in known })
                                                        hasMore = page.size >= NEWS_PAGE_SIZE
                                                    }.onFailure { error ->
                                                        loadMoreError = error.message ?: error.toString()
                                                    }
                                                    loadingMore = false
                                                }
                                            },
                                            enabled = !loadingMore,
                                        ) {
                                            Text(if (loadingMore) "Загружаем…" else "Загрузить ещё")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Большая первая карточка ленты: изображение слева, крупный заголовок и анонс справа. */
@Composable
private fun FeaturedNewsCard(post: NewsPost, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(YummyPalette.Surface)
            .clickable(onClick = onClick),
    ) {
        NewsImage(
            url = post.previewImageUrl,
            maxWidthPx = FEATURED_IMAGE_WIDTH_PX,
            category = post.category.title,
            modifier = Modifier.fillMaxHeight().weight(0.5f),
        )
        Column(
            Modifier.weight(0.5f).fillMaxHeight().padding(horizontal = 26.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NewsCategoryBadge(post.category.title)
            Text(
                post.title,
                style = MaterialTheme.typography.headlineMedium,
                color = YummyPalette.Text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                post.preview,
                style = MaterialTheme.typography.bodyMedium,
                color = YummyPalette.TextDim,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                postMeta(post),
                style = MaterialTheme.typography.labelMedium,
                color = YummyPalette.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Компактная карточка ленты: маленькое превью слева, заголовок и анонс в две строки. */
@Composable
private fun CompactNewsCard(post: NewsPost, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(176.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(YummyPalette.Surface)
            .clickable(onClick = onClick),
    ) {
        NewsImage(
            url = post.previewImageUrl,
            maxWidthPx = COMPACT_IMAGE_WIDTH_PX,
            category = post.category.title,
            modifier = Modifier.width(250.dp).fillMaxHeight(),
        )
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NewsCategoryBadge(post.category.title)
                Text(newsDate(post.createdAtSec), style = MaterialTheme.typography.labelSmall, color = YummyPalette.TextFaint)
            }
            Text(
                post.title,
                style = MaterialTheme.typography.titleMedium,
                color = YummyPalette.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                post.preview,
                style = MaterialTheme.typography.bodySmall,
                color = YummyPalette.TextDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Автор: ${post.author.nickname}",
                style = MaterialTheme.typography.labelSmall,
                color = YummyPalette.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Картинка новости с заглушкой.
 *
 * Превью грузит [NewsImageLoader]: он приводит изображение к ширине [maxWidthPx] и умеет AVIF
 * (Skia его не читает). Пока картинка грузится или если её нет, видна градиентная плашка с рубрикой —
 * карточка не «прыгает» и не выглядит пустой.
 */
@Composable
private fun NewsImage(
    url: String?,
    maxWidthPx: Int,
    category: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var bitmap by remember(url, maxWidthPx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, maxWidthPx) {
        bitmap = NewsImageLoader.load(url, maxWidthPx)
    }
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(YummyPalette.SurfaceHigh, YummyPalette.AccentSoft),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(12.dp),
            ) {
                Text("📰", fontSize = 26.sp)
                if (category.isNotBlank()) {
                    Text(
                        category,
                        style = MaterialTheme.typography.labelSmall,
                        color = YummyPalette.TextDim,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Картинка в детальном виде.
 *
 * Превью публикаций yani бывают горизонтальными (кадры, баннеры) и вертикальными (постеры
 * тайтлов). Горизонтальное растягиваем на всю ширину, вертикальное показываем целиком по центру,
 * ограничив высоту: обрезать постер или растягивать его на пол-экрана с пустыми полями не нужно.
 */
@Composable
private fun NewsDetailImage(url: String, category: String) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { bitmap = NewsImageLoader.load(url, DETAIL_IMAGE_WIDTH_PX) }
    val shape = RoundedCornerShape(16.dp)
    val placeholderBrush = Brush.linearGradient(listOf(YummyPalette.SurfaceHigh, YummyPalette.AccentSoft))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image == null) {
            Box(
                Modifier.fillMaxWidth().height(260.dp).clip(shape).background(placeholderBrush),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("📰", fontSize = 26.sp)
                    Text(category, style = MaterialTheme.typography.labelSmall, color = YummyPalette.TextDim)
                }
            }
        } else {
            val aspect = image.width.toFloat() / image.height.coerceAtLeast(1)
            if (aspect < PORTRAIT_ASPECT) {
                // Портрет: высота фиксирована, ширина по пропорции — картинка видна целиком.
                val imageHeight = 520.dp
                val imageWidth = (imageHeight.value * aspect).dp
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(imageWidth)
                        .height(imageHeight)
                        .clip(shape)
                        .background(YummyPalette.SurfaceHigh),
                )
            } else {
                // Горизонтальное превью занимает всю ширину, высота — по пропорции картинки.
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(YummyPalette.SurfaceHigh),
                )
            }
        }
    }
}

/** Детальный вид публикации: заголовок, дата, рубрика, картинка, текст и связанные тайтлы. */
@Composable
private fun NewsDetailsView(
    api: YaniNewsApi,
    postId: Int,
    onBack: () -> Unit,
    onOpenAnime: (Int) -> Unit,
) {
    var state by remember(postId) { mutableStateOf<LoadState<NewsPostDetails>>(LoadState.Loading) }
    var reloadKey by remember(postId) { mutableIntStateOf(0) }

    LaunchedEffect(postId, reloadKey) {
        state = LoadState.Loading
        state = try {
            LoadState.Data(api.post(postId))
        } catch (e: Exception) {
            LoadState.Error(e.message ?: e.toString())
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("← Назад", style = MaterialTheme.typography.titleSmall, color = YummyPalette.Accent)
            }
            Spacer(Modifier.weight(1f))
            Text("Новости", style = MaterialTheme.typography.labelMedium, color = YummyPalette.TextFaint)
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (val current = state) {
                is LoadState.Loading -> LoadingView("Загружаем публикацию…")
                is LoadState.Error -> ErrorView(current.message) { reloadKey++ }
                is LoadState.Data -> NewsDetailsBody(current.value, onOpenAnime)
            }
        }
    }
}

@Composable
private fun NewsDetailsBody(details: NewsPostDetails, onOpenAnime: (Int) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NewsCategoryBadge(details.category.title)
                    Text(
                        newsDateTime(details.createdAtSec),
                        style = MaterialTheme.typography.labelMedium,
                        color = YummyPalette.TextDim,
                    )
                    val editedAtSec = details.editedAtSec
                    if (editedAtSec != null) {
                        Text(
                            "· изменено ${newsDateTime(editedAtSec)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = YummyPalette.TextFaint,
                        )
                    }
                }
                Text(details.title, style = MaterialTheme.typography.headlineLarge, color = YummyPalette.Text)
                Text(detailsMeta(details), style = MaterialTheme.typography.bodySmall, color = YummyPalette.TextDim)
            }
        }

        val imageUrl = details.imageUrl
        if (imageUrl != null) {
            item {
                NewsDetailImage(url = imageUrl, category = details.category.title)
            }
        }

        item {
            Text(
                details.contentText,
                style = MaterialTheme.typography.bodyLarge,
                color = YummyPalette.Text,
            )
        }

        if (details.relatedAnime.isNotEmpty()) {
            item {
                Text(
                    "Связанные аниме",
                    style = MaterialTheme.typography.titleLarge,
                    color = YummyPalette.Text,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(details.relatedAnime, key = { it.animeId }) { related ->
                        PosterCard(
                            card = AnimeCard(
                                id = related.animeId,
                                title = related.title,
                                posterUrl = related.posterUrl,
                                rating = related.rating,
                                year = related.year,
                            ),
                            onClick = { onOpenAnime(related.animeId) },
                            width = 160.dp,
                            posterHeight = 224.dp,
                        )
                    }
                }
            }
        }
    }
}

/** Рубрика-плашка: тёплый акцентный фон, читаемый в обеих темах. */
@Composable
private fun NewsCategoryBadge(title: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(YummyPalette.AccentSoft)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = YummyPalette.Accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Чип сортировки: выбранный залит акцентом, остальные — на фоне карточек. */
@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) YummyPalette.Accent else YummyPalette.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color(0xFF1A0F07) else YummyPalette.Text,
        )
    }
}

/** Чип рубрики: выбранный подсвечен акцентной рамкой, как сортировка в других разделах. */
@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .height(36.dp)
            .clip(shape)
            .background(if (selected) YummyPalette.AccentSoft else YummyPalette.SurfaceHigh)
            .border(1.dp, if (selected) YummyPalette.Accent else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) YummyPalette.Accent else YummyPalette.TextDim,
        )
    }
}

/** Пустая лента: на сервере не нашлось публикаций в выбранной рубрике. */
@Composable
private fun EmptyNewsView(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("📰", style = MaterialTheme.typography.displayMedium, color = YummyPalette.TextFaint)
            Text("Публикаций пока нет", style = MaterialTheme.typography.titleLarge, color = YummyPalette.Text)
            Text(
                "В этой рубрике ещё нет новостей — выберите другую или откройте позже.",
                style = MaterialTheme.typography.bodySmall,
                color = YummyPalette.TextDim,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Подпись большой карточки: «рубрика подписана отдельно», здесь дата и автор. */
private fun postMeta(post: NewsPost): String =
    listOf(newsDate(post.createdAtSec), post.author.nickname)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

/** Подпись детального вида: автор, просмотры и комментарии (нулевые счётчики не показываем). */
private fun detailsMeta(details: NewsPostDetails): String {
    val parts = mutableListOf("Автор: ${details.author.nickname}")
    if (details.views > 0) parts += "Просмотров: ${details.views}"
    if (details.comments > 0) parts += "Комментариев: ${details.comments}"
    if (details.likes > 0) parts += "Нравится: ${details.likes}"
    return parts.joinToString(" · ")
}

/** Русская подпись сортировки. */
private fun NewsSort.label(): String = when (this) {
    NewsSort.NEW -> "Новые"
    NewsSort.BEST -> "Лучшие"
    NewsSort.OLD -> "Старые"
}

private val NEWS_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru-RU"))

private val NEWS_DATE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru-RU"))

/** Дата карточки: «14 октября 2025». Пустая строка, если unix-время не пришло. */
private fun newsDate(unixSec: Long): String {
    if (unixSec <= 0) return ""
    return Instant.ofEpochSecond(unixSec).atZone(ZoneId.systemDefault()).format(NEWS_DATE_FORMAT)
}

/** Дата публикации в детальном виде: «14 октября 2025, 18:30». */
private fun newsDateTime(unixSec: Long): String {
    if (unixSec <= 0) return ""
    return Instant.ofEpochSecond(unixSec).atZone(ZoneId.systemDefault()).format(NEWS_DATE_TIME_FORMAT)
}
