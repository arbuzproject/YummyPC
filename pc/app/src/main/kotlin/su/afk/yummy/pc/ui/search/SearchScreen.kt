// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import su.afk.yummy.pc.api.AnimeCard
import su.afk.yummy.pc.api.AnimeType
import su.afk.yummy.pc.api.GenreOption
import su.afk.yummy.pc.api.SearchFilters
import su.afk.yummy.pc.api.YaniRepository
import su.afk.yummy.pc.ui.components.ErrorView
import su.afk.yummy.pc.ui.components.LoadState
import su.afk.yummy.pc.ui.components.LoadingView
import su.afk.yummy.pc.ui.components.PosterCard
import su.afk.yummy.pc.ui.theme.YummyPalette

private data class StatusOption(val value: String, val label: String)
private data class SeasonOption(val value: String, val label: String)
private data class AgeOption(val value: Int, val label: String)
private data class SortOption(val value: String, val label: String)

private val STATUS_OPTIONS = listOf(
    StatusOption("released", "Вышел"),
    StatusOption("ongoing", "Онгоинг"),
    StatusOption("announcement", "Анонс"),
)
private val SEASON_OPTIONS = listOf(
    SeasonOption("winter", "Зима"),
    SeasonOption("spring", "Весна"),
    SeasonOption("summer", "Лето"),
    SeasonOption("fall", "Осень"),
)
private val AGE_OPTIONS = listOf(
    AgeOption(1, "0+"),
    AgeOption(2, "6+"),
    AgeOption(3, "12+"),
    AgeOption(4, "16+"),
    AgeOption(5, "18+"),
)
private val SORT_OPTIONS = listOf(
    SortOption("top", "По топу"),
    SortOption("rating", "По рейтингу"),
    SortOption("rating_counters", "По числу оценок"),
    SortOption("views", "По просмотрам"),
    SortOption("title", "По названию"),
)

/** Фильтры по умолчанию — «По топу»: с них открывается экран, как раздел «Топ». */
private val DEFAULT_FILTERS = SearchFilters()

/** Сколько популярных тайтлов грузим при пустом запросе (как просит макет). */
private const val POPULAR_LIMIT = 48

/** Что показывает сетка: популярное (пустой запрос + фильтры по умолчанию) или поиск. */
private sealed interface SearchRequest {
    data object Popular : SearchRequest
    data class Query(val text: String, val filters: SearchFilters) : SearchRequest
}

/**
 * Поиск с фильтрами сайта.
 *
 * Экран открывается уже с результатами: при пустом [initialQuery] сразу грузится «Популярное»
 * (сортировка по топу, `repository.top`), при непустом — поиск по этому запросу. Enter в поле
 * и кнопка «Искать» перезапускают поиск, смена фильтров и формулы ТОПа применяется сразу.
 * Каждый новый запуск отменяет предыдущий, поэтому устаревший ответ не перетирает новый.
 *
 * @param repository репозиторий yani.
 * @param initialQuery непустой запрос, если пришли из другого экрана.
 * @param onOpen открыть страницу тайтла по его id.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    repository: YaniRepository,
    initialQuery: String = "",
    initialGenre: String = "",
    onOpen: (Int) -> Unit,
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var genreApplied by remember(initialGenre) { mutableStateOf(false) }
    var filters by remember { mutableStateOf(DEFAULT_FILTERS) }
    var showFilters by remember { mutableStateOf(false) }
    var showAllGenres by remember { mutableStateOf(false) }

    // Перезапуск запроса без изменения текста/фильтров: Enter, «Искать», «Повторить», «Каталог».
    var reloadTick by remember { mutableStateOf(0) }
    // Что именно загружено/загружается — от этого зависят подпись сетки и тексты состояний.
    var lastRequest by remember { mutableStateOf<SearchRequest>(SearchRequest.Popular) }
    var state by remember { mutableStateOf<LoadState<List<AnimeCard>>>(LoadState.Loading) }

    val filterData by produceState<Triple<List<AnimeType>, List<GenreOption>, String?>>(
        Triple(emptyList(), emptyList(), null),
    ) {
        value = runCatching {
            Triple(repository.types(), repository.genres(), null)
        }.getOrElse { Triple(emptyList(), emptyList(), it.message) }
    }

    fun currentRequest(): SearchRequest {
        val text = query.trim()
        return if (text.isEmpty() && filters == DEFAULT_FILTERS) {
            SearchRequest.Popular
        } else {
            SearchRequest.Query(text, filters)
        }
    }

    // Первый запуск — сразу при открытии экрана; дальше — на смену фильтров/сортировки
    // и на reloadTick (Enter/«Искать»). Смена ключей отменяет прошлую загрузку,
    // поэтому на экране всегда результат последнего запроса.
    LaunchedEffect(filterData, initialGenre) {
        if (initialGenre.isNotBlank() && !genreApplied) {
            val match = filterData.second.firstOrNull { it.title.equals(initialGenre, ignoreCase = true) }
            if (match != null) {
                genreApplied = true
                filters = filters.copy(genres = listOf(match.id))
            }
        }
    }

    LaunchedEffect(filters, initialQuery, reloadTick) {
        val request = currentRequest()
        lastRequest = request
        state = LoadState.Loading
        state = try {
            LoadState.Data(
                when (request) {
                    SearchRequest.Popular -> repository.top(limit = POPULAR_LIMIT)
                    is SearchRequest.Query -> repository.search(request.text, request.filters)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadState.Error(e.message ?: e.toString())
        }
    }

    val activeFilters = filters.genres.size + filters.types.size + filters.statuses.size +
        filters.seasons.size + filters.ageRatings.size +
        listOfNotNull(filters.fromYear, filters.toYear).size +
        if (filters.sort != DEFAULT_FILTERS.sort) 1 else 0

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Поиск", style = MaterialTheme.typography.headlineLarge, color = YummyPalette.Text)
        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .onPreviewKeyEvent { event ->
                        val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        if (isEnter && event.type == KeyEventType.KeyDown) {
                            reloadTick++
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                label = { Text("Название тайтла") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { reloadTick++ }),
            )
            Button(
                onClick = { reloadTick++ },
                colors = ButtonDefaults.buttonColors(
                    containerColor = YummyPalette.Accent,
                    contentColor = Color(0xFF1A0F07),
                ),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Искать", fontWeight = FontWeight.Bold) }

            OutlinedButton(onClick = { showFilters = !showFilters }) {
                Text(if (activeFilters > 0) "Фильтры · $activeFilters" else "Фильтры")
            }
            TextButton(onClick = {
                query = ""
                filters = DEFAULT_FILTERS
                reloadTick++
            }) { Text("Каталог") }
            Text(
                "Enter — поиск · пустой запрос — популярное",
                style = MaterialTheme.typography.labelSmall,
                color = YummyPalette.TextDim,
            )
        }

        if (showFilters) {
            FilterPanel(
                filters = filters,
                types = filterData.first,
                genres = filterData.second,
                showAllGenres = showAllGenres,
                onToggleAllGenres = { showAllGenres = !showAllGenres },
                onChange = { filters = it },
                onClose = { showFilters = false },
                onReset = {
                    query = ""
                    filters = DEFAULT_FILTERS
                    reloadTick++
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        when (val current = state) {
            is LoadState.Loading -> LoadingView(
                if (lastRequest is SearchRequest.Popular) "Загружаем популярное…" else "Ищем…",
            )

            is LoadState.Error -> ErrorView(current.message) { reloadTick++ }

            is LoadState.Data -> {
                if (current.value.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            when (lastRequest) {
                                SearchRequest.Popular ->
                                    "Популярное сейчас пусто — введите запрос или выберите фильтры"
                                is SearchRequest.Query ->
                                    "Ничего не нашлось — попробуйте изменить запрос или фильтры"
                            },
                            color = YummyPalette.TextDim,
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        ResultsHeader(lastRequest, current.value.size)
                        LazyVerticalGrid(
                            modifier = Modifier.weight(1f),
                            columns = GridCells.Adaptive(minSize = 190.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            contentPadding = PaddingValues(bottom = 24.dp),
                        ) {
                            items(current.value, key = { it.id }) { card ->
                                PosterCard(card, onClick = { onOpen(card.id) }, width = 190.dp, posterHeight = 268.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Подпись над сеткой: откуда результаты и сколько их. */
@Composable
private fun ResultsHeader(request: SearchRequest, count: Int) {
    val title = when (request) {
        SearchRequest.Popular -> "Популярное · Топ тайтлов"
        is SearchRequest.Query ->
            if (request.text.isEmpty()) "Подборка по фильтрам" else "Результаты по запросу «${request.text}»"
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(YummyPalette.Accent),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = YummyPalette.Text,
        )
        Text(titlesCount(count), style = MaterialTheme.typography.labelSmall, color = YummyPalette.TextDim)
    }
}

/** «1 тайтл», «2 тайтла», «48 тайтлов» — подпись к количеству результатов. */
private fun titlesCount(count: Int): String {
    val tail = count % 100
    val word = when {
        tail in 11..14 -> "тайтлов"
        count % 10 == 1 -> "тайтл"
        count % 10 in 2..4 -> "тайтла"
        else -> "тайтлов"
    }
    return "$count $word"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    filters: SearchFilters,
    types: List<AnimeType>,
    genres: List<GenreOption>,
    showAllGenres: Boolean,
    onToggleAllGenres: () -> Unit,
    onChange: (SearchFilters) -> Unit,
    onClose: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(YummyPalette.Surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilterSection("Тип") {
            types.forEach { type ->
                val selected = type.value in filters.types
                Chip(type.name, selected) {
                    onChange(filters.copy(types = filters.types.toggle(type.value)))
                }
            }
        }

        FilterSection("Статус") {
            STATUS_OPTIONS.forEach { option ->
                Chip(option.label, option.value in filters.statuses) {
                    onChange(filters.copy(statuses = filters.statuses.toggle(option.value)))
                }
            }
        }

        FilterSection("Сезон") {
            SEASON_OPTIONS.forEach { option ->
                Chip(option.label, option.value in filters.seasons) {
                    onChange(filters.copy(seasons = filters.seasons.toggle(option.value)))
                }
            }
        }

        FilterSection("Возраст") {
            AGE_OPTIONS.forEach { option ->
                Chip(option.label, option.value in filters.ageRatings) {
                    onChange(filters.copy(ageRatings = filters.ageRatings.toggle(option.value)))
                }
            }
        }

        FilterSection("Годы") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                YearField(filters.fromYear, "с") { onChange(filters.copy(fromYear = it)) }
                YearField(filters.toYear, "по") { onChange(filters.copy(toYear = it)) }
                if (filters.fromYear != null || filters.toYear != null) {
                    TextButton(onClick = { onChange(filters.copy(fromYear = null, toYear = null)) }) { Text("Сброс") }
                }
            }
        }

        FilterSection("Сортировка") {
            var sortMenuOpen by remember { mutableStateOf(false) }
            Box {
                Chip(
                    "Формула ТОПа: ${SORT_OPTIONS.firstOrNull { it.value == filters.sort }?.label ?: "По топу"}",
                    true,
                ) { sortMenuOpen = true }
                androidx.compose.material3.DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false },
                ) {
                    SORT_OPTIONS.forEach { option ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(option.label, color = YummyPalette.Text) },
                            onClick = {
                                sortMenuOpen = false
                                onChange(filters.copy(sort = option.value))
                            },
                        )
                    }
                }
            }
        }
        FilterSection("Жанры (${filters.genres.size} выбрано)") {
            val visible = if (showAllGenres) genres else genres.take(24)
            visible.forEach { genre ->
                Chip(genre.title, genre.id in filters.genres) {
                    onChange(filters.copy(genres = filters.genres.toggle(genre.id)))
                }
            }
            if (genres.size > 24) {
                TextButton(onClick = onToggleAllGenres) {
                    Text(if (showAllGenres) "Свернуть" else "Ещё ${genres.size - 24}")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(
                    containerColor = YummyPalette.Accent,
                    contentColor = Color(0xFF1A0F07),
                ),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Готово", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = onReset) { Text("Сбросить всё") }
            Text(
                "Фильтры и сортировка применяются сразу — сетка обновляется автоматически.",
                style = MaterialTheme.typography.labelSmall,
                color = YummyPalette.TextDim,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = YummyPalette.Text,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) YummyPalette.Accent else YummyPalette.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color(0xFF1A0F07) else YummyPalette.Text,
        )
    }
}

@Composable
private fun YearField(value: Int?, label: String, onChange: (Int?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter(Char::isDigit).take(4)
            onChange(text.toIntOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.width(120.dp),
    )
}

private fun <T> List<T>.toggle(value: T): List<T> =
    if (value in this) filterNot { it == value } else this + value
