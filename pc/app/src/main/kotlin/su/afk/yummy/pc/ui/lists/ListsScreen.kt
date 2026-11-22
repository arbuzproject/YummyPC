// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.lists

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.ktor.client.plugins.ClientRequestException
import su.afk.yummy.pc.api.LocalLibrary
import su.afk.yummy.pc.api.StoredListEntry
import su.afk.yummy.pc.api.UserListEntry
import su.afk.yummy.pc.api.UserListStatuses
import su.afk.yummy.pc.api.YaniApi
import su.afk.yummy.pc.api.YaniAuthApi
import su.afk.yummy.pc.api.YaniUserListsApi
import su.afk.yummy.pc.api.YummyConfig
import su.afk.yummy.pc.ui.components.ErrorView
import su.afk.yummy.pc.ui.components.LoadingView
import su.afk.yummy.pc.ui.theme.YummyPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Вкладка статуса: ключ совпадает с `listHref` из ответа API, null — вкладка «Все». */
private data class StatusTab(val key: String?, val title: String)

/** Все вкладки в порядке сайта: «Все» + шесть статусов из [UserListStatuses]. */
private val STATUS_TABS: List<StatusTab> =
    listOf(StatusTab(null, "Все")) + UserListStatuses.ALL.map { StatusTab(it.href, it.title) }

/** Порядок сортировки карточек. */
private enum class ListSort(val label: String) {
    /** Исходный порядок ответа API: yani отдаёт записи в порядке добавления. */
    ADDED("Дата добавления"),
    RATING("Рейтинг"),
    TITLE("Название"),
}

/** Тёмный текст на оранжевой плашке выбранной вкладки — как в остальном клиенте. */
private val SelectedTabText = Color(0xFF1A0F07)

/** Ширина сетки: тот же ритм, что у топа и поиска. */
private val GridCardMinWidth: Dp = 210.dp

/** Ключ «Любимого» в поле `href` снимка: совпадает с `UserListEntry.statusKey`. */
private const val FAVORITE_HREF = "fav"

/** Формат даты сохранения снимка: «22.11.2026, 16:40». */
private val SAVED_AT_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault())

/**
 * Что показывает экран «Мои списки».
 *
 * Списки приходят либо с сервера ([Ready.local] = false), либо из локального снимка
 * [LocalLibrary] ([Ready.local] = true) — второй вариант включается без авторизации,
 * при ошибке сети и при устаревшем токене.
 */
private sealed interface ListsContent {
    data object Loading : ListsContent

    /**
     * @property entries тайтлы всех вкладок в порядке сервера (или их порядок в снимке).
     * @property local true — данные с диска, а не с сервера.
     * @property savedAtSec когда сохранён снимок; null — снимок с сервера.
     * @property note почему вместо сервера показан снимок; null — просто нет авторизации.
     */
    data class Ready(
        val entries: List<UserListEntry>,
        val local: Boolean,
        val savedAtSec: Long? = null,
        val note: String? = null,
    ) : ListsContent

    /** Ни сервера, ни снимка: списки доступны только после входа в профиль. */
    data object LoginRequired : ListsContent

    /** Сервер не ответил, а снимка на диске нет. */
    data class Failed(val message: String) : ListsContent
}

/**
 * Экран «Мои списки»: все пользовательские списки yani в одном окне.
 *
 * Списки приходят одним запросом (`GET /users/{id}/lists`), поэтому вкладки статусов,
 * поиск по названию и сортировка работают локально по закэшированному ответу — переключение
 * вкладок и ввод в поиск не ходят в сеть.
 *
 * При удачной загрузке ответ сохраняется в [LocalLibrary.saveLists]. Если пользователь
 * не авторизован или сервер недоступен (ошибка сети, 401, 5xx), экран показывает последний
 * сохранённый снимок с пометкой «Локальная библиотека» и кнопкой «Обновить» — тогда вкладки
 * статусов и бейджи на карточках работают по полю `href` снимка. Если снимка нет, остаётся
 * прежнее поведение: приглашение войти или ошибка с повтором.
 *
 * Удачная загрузка также досылает на сервер действия, накопленные в оффлайне
 * ([LocalLibrary.flush]).
 *
 * @param onOpenAnime открыть страницу тайтла по его id.
 */
@Composable
fun ListsScreen(onOpenAnime: (Int) -> Unit) {
    val auth = remember { YaniAuthApi() }
    val listsApi = remember { YaniUserListsApi() }
    val api = remember { YaniApi() }

    var content by remember { mutableStateOf<ListsContent>(ListsContent.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var selectedTab by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(ListSort.ADDED) }

    LaunchedEffect(reloadKey) {
        content = ListsContent.Loading
        var serverLoaded = false
        content = if (!YummyConfig.isAuthorized) {
            // Без токена сервер не ответит, но снимок с диска показать можно.
            localListsOrLogin()
        } else {
            try {
                var userId = YummyConfig.userId
                if (userId <= 0) {
                    val profile = auth.profile()
                    userId = profile.id
                    YummyConfig.updateUserId(userId)
                }
                check(userId > 0) { "Сервер не вернул id пользователя" }
                val entries = listsApi.lists(userId)
                // Удачная загрузка — свежий снимок для оффлайн-режима.
                LocalLibrary.saveLists(userId, entries)
                serverLoaded = true
                ListsContent.Ready(entries = entries, local = false)
            } catch (e: ClientRequestException) {
                if (e.response.status.value == 401) {
                    // Токен сессии устарел — как гость, но снимок показать можем.
                    localListsOrLogin()
                } else {
                    localListsOrError(
                        e.message.ifBlank { "Сервер вернул ошибку ${e.response.status.value}" },
                    )
                }
            } catch (e: Exception) {
                localListsOrError(e.message ?: e.toString())
            }
        }
        // Сервер снова ответил — досылаем то, что накопилось без сети.
        if (serverLoaded) runCatching { LocalLibrary.flush(api) }
    }

    val ready = content as? ListsContent.Ready
    val entries = ready?.entries.orEmpty()
    val local = ready?.local == true
    val counts = remember(entries) {
        STATUS_TABS.associate { tab ->
            tab.key to if (tab.key == null) entries.size else entries.count { it.statusKey == tab.key }
        }
    }
    val visible = remember(entries, selectedTab, query, sort) {
        filterAndSortEntries(entries, selectedTab, query, sort)
    }
    val subtitle = when (val current = content) {
        is ListsContent.Loading -> "Загружаем списки…"
        is ListsContent.LoginRequired -> "Нужен вход в профиль yani"
        is ListsContent.Failed -> "Не удалось загрузить списки"
        is ListsContent.Ready -> when {
            current.local -> localSubtitle(current)
            current.entries.isNotEmpty() -> ""
            else -> "Все списки аккаунта в одном месте"
        }
    }
    val canRefresh = content !is ListsContent.Loading && (ready != null || YummyConfig.isAuthorized)

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
                    Text("Мои списки", style = MaterialTheme.typography.headlineLarge, color = YummyPalette.Text)
                    if (local) LocalBadge("Локальная библиотека")
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = YummyPalette.TextDim)
            }
            if (canRefresh) {
                OutlinedButton(
                    onClick = { reloadKey++ },
                    enabled = content !is ListsContent.Loading,
                ) { Text("Обновить") }
            }
        }

        when (val current = content) {
            is ListsContent.Loading -> LoadingView("Загружаем ваши списки…")
            is ListsContent.LoginRequired -> LoginRequiredView(Modifier.weight(1f).fillMaxWidth())
            is ListsContent.Failed -> ErrorView(current.message) { reloadKey++ }
            is ListsContent.Ready -> {
                current.note?.let { message ->
                    LocalNotice("Не удалось обновить с сервера: $message. Показана сохранённая копия.")
                    Spacer(Modifier.height(12.dp))
                }
                StatusTabs(counts = counts, selected = selectedTab, onSelect = { selectedTab = it })
                Spacer(Modifier.height(14.dp))
                SearchAndSort(
                    query = query,
                    onQueryChange = { query = it },
                    sort = sort,
                    onSortChange = { sort = it },
                )
                Spacer(Modifier.height(14.dp))
                if (visible.isEmpty()) {
                    EmptyListsView(
                        title = when {
                            current.entries.isEmpty() && current.local -> "В локальной библиотеке пока пусто"
                            current.entries.isEmpty() || query.isBlank() -> "В этом списке пока ничего нет"
                            else -> "Ничего не найдено"
                        },
                        hint = when {
                            current.entries.isEmpty() && current.local ->
                                "Списки сохранятся здесь после первой удачной загрузки с сервера."
                            current.entries.isEmpty() ->
                                "Добавьте тайтлы на сайте yani.tv — они появятся здесь."
                            query.isBlank() ->
                                "Выберите другую вкладку — в этом статусе пока пусто."
                            else ->
                                "Попробуйте изменить запрос или выбрать другую вкладку."
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = GridCardMinWidth),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        itemsIndexed(
                            items = visible,
                            key = { _, entry -> "${entry.animeId}-${entry.statusKey}" },
                        ) { index, entry ->
                            UserListCard(
                                entry = entry,
                                position = index + 1,
                                onClick = { onOpenAnime(entry.animeId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Подпись под заголовком в локальном режиме: дата сохранения и сколько тайтлов в снимке.
 * Саму пометку «Локальная библиотека» показывает [LocalBadge] рядом с заголовком.
 */
private fun localSubtitle(content: ListsContent.Ready): String = listOfNotNull(
    formatSavedAt(content.savedAtSec)?.let { "Сохранено $it" },
    content.entries.takeIf { it.isNotEmpty() }?.let { titlesCount(it.size) },
).joinToString(" · ").ifBlank { "Списки из локального снапшота" }

/** Дата сохранения снимка в читаемом виде; null — даты нет. */
private fun formatSavedAt(savedAtSec: Long?): String? {
    val value = savedAtSec?.takeIf { it > 0 } ?: return null
    return SAVED_AT_FORMAT.format(Instant.ofEpochSecond(value))
}

/**
 * Последний снимок с диска в виде контента экрана.
 *
 * Сначала ищем снимок текущего пользователя: при смене аккаунта нельзя показывать чужие
 * списки. Если id неизвестен (пользователь вышел из профиля), берём самый свежий снимок —
 * он всё равно помечен как локальный.
 */
private fun readLocalLists(note: String? = null): ListsContent.Ready? {
    val userId = YummyConfig.userId
    if (userId > 0) {
        LocalLibrary.loadListsSnapshot(userId)?.let { snapshot ->
            return ListsContent.Ready(
                entries = snapshot.entries.map { it.toUserListEntry() },
                local = true,
                savedAtSec = snapshot.savedAtSec,
                note = note,
            )
        }
    }
    val latest = LocalLibrary.latestLists() ?: return null
    return ListsContent.Ready(
        entries = latest.entries.map { it.toUserListEntry() },
        local = true,
        savedAtSec = latest.savedAtSec,
        note = note,
    )
}

/** Снимок, если он есть, иначе приглашение войти. */
private fun localListsOrLogin(): ListsContent = readLocalLists() ?: ListsContent.LoginRequired

/** Снимок с пояснением, почему не удалось обновить; если снимка нет — ошибка. */
private fun localListsOrError(message: String): ListsContent =
    readLocalLists(note = message) ?: ListsContent.Failed(message)

/**
 * Запись снимка → карточка списка.
 *
 * `href` снимка — это `statusKey`: `fav` означает «Любимое», остальное — href списка с
 * сайта. Так вкладки и бейджи в локальном режиме считаются по тем же ключам, что и на сервере.
 */
private fun StoredListEntry.toUserListEntry(): UserListEntry = UserListEntry(
    animeId = id,
    title = title,
    posterUrl = posterUrl,
    rating = rating,
    year = year,
    typeName = typeName,
    listId = null,
    listHref = href,
    listTitle = null,
    isFavorite = href == FAVORITE_HREF,
    userRating = rating ?: 0.0,
)

/**
 * Локальная фильтрация и сортировка закэшированного ответа: сеть не дёргаем.
 *
 * «Дата добавления» оставляет исходный порядок ответа API, «Рейтинг» сортирует по личной
 * оценке пользователя (при её отсутствии — по рейтингу сайта), «Название» — без учёта регистра.
 */
private fun filterAndSortEntries(
    entries: List<UserListEntry>,
    statusKey: String?,
    query: String,
    sort: ListSort,
): List<UserListEntry> {
    val trimmedQuery = query.trim()
    val byStatus = if (statusKey == null) entries else entries.filter { it.statusKey == statusKey }
    val found = if (trimmedQuery.isEmpty()) {
        byStatus
    } else {
        byStatus.filter { it.title.contains(trimmedQuery, ignoreCase = true) }
    }
    return when (sort) {
        ListSort.ADDED -> found
        ListSort.RATING -> found.sortedWith(
            compareByDescending<UserListEntry> { it.userRating.takeIf { rating -> rating > 0 } ?: it.rating ?: 0.0 }
                .thenByDescending { it.rating ?: 0.0 },
        )
        ListSort.TITLE -> found.sortedWith(
            compareBy<UserListEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.title },
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

/** Вкладки статусов с счётчиками; при нехватке ширины ряд прокручивается колесом. */
@Composable
private fun StatusTabs(counts: Map<String?, Int>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        STATUS_TABS.forEach { tab ->
            StatusTabChip(
                title = tab.title,
                count = counts[tab.key] ?: 0,
                selected = selected == tab.key,
                onClick = { onSelect(tab.key) },
            )
        }
    }
}

/** Вкладка статуса в стиле вкладок топа: оранжевая плашка у выбранной + счётчик. */
@Composable
private fun StatusTabChip(title: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) YummyPalette.Accent else YummyPalette.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (selected) SelectedTabText else YummyPalette.Text,
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) SelectedTabText.copy(alpha = 0.14f) else YummyPalette.SurfaceHover)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (selected) SelectedTabText else YummyPalette.TextDim,
            )
        }
    }
}

/** Строка поиска (мгновенный локальный фильтр) и переключатель сортировки. */
@Composable
private fun SearchAndSort(
    query: String,
    onQueryChange: (String) -> Unit,
    sort: ListSort,
    onSortChange: (ListSort) -> Unit,
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
        ListSort.entries.forEach { option ->
            SortChip(label = option.label, selected = sort == option, onClick = { onSortChange(option) })
        }
    }
}

/** Чип сортировки: выбранный подсвечен оранжевой рамкой. */
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

/** Состояние «нужен вход»: списки yani доступны только авторизованному пользователю. */
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
                "Списки хранятся в аккаунте yani. Откройте раздел «Профиль» на левой панели и войдите " +
                    "по логину и паролю или вставьте токен сессии — после входа здесь появятся ваши тайтлы.",
                style = MaterialTheme.typography.bodyMedium,
                color = YummyPalette.TextDim,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Пустое состояние списка или поиска. */
@Composable
private fun EmptyListsView(title: String, hint: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("✦", style = MaterialTheme.typography.displayMedium, color = YummyPalette.TextFaint)
            Text(title, style = MaterialTheme.typography.titleLarge, color = YummyPalette.Text)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = YummyPalette.TextDim, textAlign = TextAlign.Center)
        }
    }
}

/** Русское склонение для подписи «N тайтлов». */
private fun titlesCount(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> "тайтлов"
        mod10 == 1 -> "тайтл"
        mod10 in 2..4 -> "тайтла"
        else -> "тайтлов"
    }
    return "$count $word"
}
