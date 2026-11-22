// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.ktor.client.plugins.ClientRequestException
import su.afk.yummy.pc.api.LocalLibrary
import su.afk.yummy.pc.api.StoredHistory
import su.afk.yummy.pc.api.WatchHistoryItem
import su.afk.yummy.pc.api.YaniHistoryApi
import su.afk.yummy.pc.api.YummyConfig
import su.afk.yummy.pc.ui.components.ErrorView
import su.afk.yummy.pc.ui.components.LoadingView
import su.afk.yummy.pc.ui.theme.YummyPalette

/** Порядок сортировки истории просмотра. */
private enum class HistorySort(val label: String) {
    /** Свежие просмотры сверху — так историю показывает сам yani. */
    NEWEST("Сначала новые"),

    /** Старые просмотры сверху: удобно вспомнить, с чего всё начиналось. */
    OLDEST("Сначала старые"),
}

/**
 * Что показывает экран «История просмотра».
 *
 * История приходит либо с сервера ([Ready.local] = false), либо из локального снимка
 * [LocalLibrary] ([Ready.local] = true) — второй вариант включается без авторизации,
 * при ошибке сети и при устаревшем токене.
 */
private sealed interface HistoryContent {
    data object Loading : HistoryContent

    /**
     * @property items записи в порядке сервера (свежие сверху).
     * @property local true — данные с диска, а не с сервера.
     * @property note почему вместо сервера показан снимок; null — просто нет авторизации.
     */
    data class Ready(
        val items: List<WatchHistoryItem>,
        val local: Boolean,
        val note: String? = null,
    ) : HistoryContent

    /** Ни сервера, ни снимка: история доступна только после входа в профиль. */
    data object LoginRequired : HistoryContent

    /** Сервер не ответил, а снимка на диске нет. */
    data class Failed(val message: String) : HistoryContent
}

/**
 * Экран «История просмотра»: серии, которые пользователь уже смотрел в аккаунте yani.
 *
 * История приходит одним запросом ([YaniHistoryApi.history], сервер листается страницами
 * прозрачно для экрана), поэтому поиск по названию и сортировка работают локально по уже
 * загруженному списку — ввод в поиск и переключение сортировки не ходят в сеть.
 *
 * При удачной загрузке ответ сохраняется в [LocalLibrary.saveHistory]. Если пользователь
 * не авторизован или сервер недоступен (ошибка сети, 401, 5xx), экран показывает последний
 * сохранённый снимок с пометкой «Локальная история» и кнопкой «Обновить». Если снимка нет,
 * остаётся прежнее поведение: приглашение войти или ошибка с повтором.
 *
 * @param onOpenAnime открыть страницу тайтла по его id.
 */
@Composable
fun HistoryScreen(onOpenAnime: (Int) -> Unit) {
    val historyApi = remember { YaniHistoryApi() }

    var content by remember { mutableStateOf<HistoryContent>(HistoryContent.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(HistorySort.NEWEST) }

    LaunchedEffect(reloadKey) {
        content = HistoryContent.Loading
        content = if (!YummyConfig.isAuthorized) {
            // Без токена сервер не ответит, но снимок с диска показать можно.
            localHistoryOrLogin()
        } else {
            try {
                val items = historyApi.history()
                // Удачная загрузка — свежий снимок для оффлайн-режима.
                LocalLibrary.saveHistory(items)
                HistoryContent.Ready(items = items, local = false)
            } catch (e: ClientRequestException) {
                if (e.response.status.value == 401) {
                    // Токен сессии устарел — как гость, но снимок показать можем.
                    localHistoryOrLogin()
                } else {
                    localHistoryOrError(
                        e.message.ifBlank { "Сервер вернул ошибку ${e.response.status.value}" },
                    )
                }
            } catch (e: Exception) {
                localHistoryOrError(e.message ?: e.toString())
            }
        }
    }

    val ready = content as? HistoryContent.Ready
    val items = ready?.items.orEmpty()
    val local = ready?.local == true
    val visible = remember(items, query, sort) { filterAndSortHistory(items, query, sort) }
    val subtitle = when (val current = content) {
        is HistoryContent.Loading -> "Загружаем историю…"
        is HistoryContent.LoginRequired -> "Нужен вход в профиль yani"
        is HistoryContent.Failed -> "Не удалось загрузить историю"
        is HistoryContent.Ready -> when {
            current.local -> localSubtitle(current)
            current.items.isNotEmpty() -> "Аккаунт yani · ${recordsCount(current.items.size)}"
            else -> "Все просмотренные серии в одном месте"
        }
    }
    val canRefresh = content !is HistoryContent.Loading && (ready != null || YummyConfig.isAuthorized)

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("История просмотра", style = MaterialTheme.typography.headlineLarge, color = YummyPalette.Text)
                    if (local) LocalBadge("Локальная история")
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = YummyPalette.TextDim)
            }
            if (canRefresh) {
                OutlinedButton(
                    onClick = { reloadKey++ },
                    enabled = content !is HistoryContent.Loading,
                ) { Text("Обновить") }
            }
        }

        when (val current = content) {
            is HistoryContent.Loading -> LoadingView("Загружаем историю просмотра…")
            is HistoryContent.LoginRequired -> LoginRequiredView(Modifier.weight(1f).fillMaxWidth())
            is HistoryContent.Failed -> ErrorView(current.message) { reloadKey++ }
            is HistoryContent.Ready -> {
                current.note?.let { message ->
                    LocalNotice("Не удалось обновить с сервера: $message. Показана сохранённая копия.")
                    Spacer(Modifier.height(12.dp))
                }
                SearchAndSort(
                    query = query,
                    onQueryChange = { query = it },
                    sort = sort,
                    onSortChange = { sort = it },
                )
                Spacer(Modifier.height(14.dp))
                if (visible.isEmpty()) {
                    EmptyHistoryView(
                        title = when {
                            current.items.isEmpty() && current.local -> "В локальной истории пока пусто"
                            current.items.isEmpty() || query.isBlank() -> "История просмотра пуста"
                            else -> "Ничего не найдено"
                        },
                        hint = when {
                            current.items.isEmpty() && current.local ->
                                "История сохранится здесь после первой удачной загрузки с сервера."
                            current.items.isEmpty() ->
                                "Посмотрите серию на yani.tv — она появится здесь."
                            query.isBlank() ->
                                "В истории пока нет записей."
                            else ->
                                "Попробуйте изменить запрос."
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
                    ) {
                        itemsIndexed(
                            items = visible,
                            key = { index, item -> "$index-${item.animeId}-${item.episode}" },
                        ) { _, item ->
                            HistoryCard(item = item, onClick = { onOpenAnime(item.animeId) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Подпись под заголовком в локальном режиме. Саму пометку «Локальная история»
 * показывает [LocalBadge] рядом с заголовком.
 */
private fun localSubtitle(content: HistoryContent.Ready): String = listOfNotNull(
    "Локальная копия",
    content.items.takeIf { it.isNotEmpty() }?.let { recordsCount(it.size) },
).joinToString(" · ")

/** Последний снимок истории с диска в виде контента экрана; null — снимка нет. */
private fun readLocalHistory(note: String? = null): HistoryContent.Ready? =
    LocalLibrary.loadHistory()?.let { stored ->
        HistoryContent.Ready(
            items = stored.map { it.toWatchHistoryItem() },
            local = true,
            note = note,
        )
    }

/** Снимок, если он есть, иначе приглашение войти. */
private fun localHistoryOrLogin(): HistoryContent = readLocalHistory() ?: HistoryContent.LoginRequired

/** Снимок с пояснением, почему не удалось обновить; если снимка нет — ошибка. */
private fun localHistoryOrError(message: String): HistoryContent =
    readLocalHistory(note = message) ?: HistoryContent.Failed(message)

/** Запись снимка → карточка истории; кадр из серии при оффлайн-показе недоступен. */
private fun StoredHistory.toWatchHistoryItem(): WatchHistoryItem = WatchHistoryItem(
    animeId = animeId,
    title = title,
    episode = episode,
    posterUrl = posterUrl,
    screenshotUrl = null,
    watchedAtSec = watchedAtSec,
    positionSec = positionSec,
    durationSec = durationSec,
)

/**
 * Локальная фильтрация и сортировка закэшированной истории: сеть не дёргаем.
 *
 * Поиск идёт по названию тайтла без учёта регистра. Свежие записи — по убыванию `date`,
 * старые — по возрастанию; записи без даты уходят в конец списка в обоих режимах.
 */
private fun filterAndSortHistory(
    items: List<WatchHistoryItem>,
    query: String,
    sort: HistorySort,
): List<WatchHistoryItem> {
    val trimmed = query.trim()
    val found = if (trimmed.isEmpty()) {
        items
    } else {
        items.filter { it.title.contains(trimmed, ignoreCase = true) }
    }
    return when (sort) {
        HistorySort.NEWEST -> found.sortedWith(
            compareByDescending<WatchHistoryItem> { it.watchedAtSec ?: Long.MIN_VALUE },
        )

        HistorySort.OLDEST -> found.sortedWith(
            compareBy<WatchHistoryItem> { it.watchedAtSec ?: Long.MAX_VALUE },
        )
    }
}

/** Пометка «данные с диска»: оранжевая плашка с точкой, заметная в обеих темах. */
@Composable
private fun LocalBadge(text: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(YummyPalette.AccentSoft)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(YummyPalette.Accent))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = YummyPalette.Accent,
        )
    }
}

/** Пояснение, почему вместо сервера показан снимок с диска. */
@Composable
private fun LocalNotice(message: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(YummyPalette.Surface)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("⚠", style = MaterialTheme.typography.labelLarge, color = YummyPalette.RatingStar)
        Text(message, style = MaterialTheme.typography.bodySmall, color = YummyPalette.TextDim)
    }
}

/** Строка поиска (мгновенный локальный фильтр) и переключатель сортировки. */
@Composable
private fun SearchAndSort(
    query: String,
    onQueryChange: (String) -> Unit,
    sort: HistorySort,
    onSortChange: (HistorySort) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.width(360.dp),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            placeholder = { Text("Поиск по названию", color = YummyPalette.TextFaint) },
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = YummyPalette.TextDim)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Очистить поиск", tint = YummyPalette.TextDim)
                    }
                }
            },
        )
        Spacer(Modifier.weight(1f))
        Text("Сортировка", style = MaterialTheme.typography.labelMedium, color = YummyPalette.TextDim)
        HistorySort.entries.forEach { option ->
            SortChip(label = option.label, selected = sort == option, onClick = { onSortChange(option) })
        }
    }
}

/** Чип сортировки: выбранный подсвечен оранжевой рамкой, как чипы в «Моих списках». */
@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) YummyPalette.AccentSoft else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) YummyPalette.Accent else YummyPalette.Divider,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) YummyPalette.Accent else YummyPalette.TextDim,
        )
    }
}

/** Состояние «нужен вход»: история yani доступна только авторизованному пользователю. */
@Composable
private fun LoginRequiredView(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("👤", style = MaterialTheme.typography.displayMedium)
            Text("Войдите в профиль", style = MaterialTheme.typography.headlineMedium, color = YummyPalette.Text)
            Text(
                "История просмотров хранится в аккаунте yani. Откройте раздел «Профиль» на левой панели " +
                    "и войдите по логину и паролю или вставьте токен сессии — после входа здесь появится " +
                    "список просмотренных серий.",
                style = MaterialTheme.typography.bodyMedium,
                color = YummyPalette.TextDim,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Пустая история или пустой результат поиска по истории. */
@Composable
private fun EmptyHistoryView(title: String, hint: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("🕘", style = MaterialTheme.typography.displayMedium, color = YummyPalette.TextFaint)
            Text(title, style = MaterialTheme.typography.titleLarge, color = YummyPalette.Text)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = YummyPalette.TextDim, textAlign = TextAlign.Center)
        }
    }
}

/** Русское склонение для подписи «N записей». */
private fun recordsCount(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> "записей"
        mod10 == 1 -> "запись"
        mod10 in 2..4 -> "записи"
        else -> "записей"
    }
    return "$count $word"
}
