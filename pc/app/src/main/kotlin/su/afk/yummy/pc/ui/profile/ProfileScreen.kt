// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import su.afk.yummy.pc.api.ContinueWatchingItem
import su.afk.yummy.pc.api.ProfileStats
import su.afk.yummy.pc.api.UserListEntry
import su.afk.yummy.pc.api.UserProfile
import su.afk.yummy.pc.api.WatchHistoryItem
import su.afk.yummy.pc.api.YaniAuthApi
import su.afk.yummy.pc.api.YaniHistoryApi
import su.afk.yummy.pc.api.YaniRepository
import su.afk.yummy.pc.api.YaniUserListsApi
import su.afk.yummy.pc.api.YummyConfig
import su.afk.yummy.pc.ui.components.PosterImage
import su.afk.yummy.pc.ui.theme.YummyPalette
import kotlin.math.roundToInt

/** Светлый текст на затемнённом баннере (баннер всегда тёмный, независимо от темы). */
private val BannerText = Color(0xFFF2F2F5)
private val BannerTextDim = Color(0xB3F2F2F5)
private val BannerTextFaint = Color(0x80F2F2F5)
private val BannerOnAccent = Color(0xFF1A0F07)

/** Тёмный градиент-подложка, если у пользователя нет баннера. */
private val BannerFallbackBrush = Brush.linearGradient(
    listOf(Color(0xFF3A2A1E), Color(0xFF1C1712), Color(0xFF101014)),
)

/** Высота широкой карточки-баннера. */
private val BannerHeight = 180.dp

/** Табы экрана профиля. */
private enum class ProfileTab(val title: String) {
    STATISTICS("Статистика"),
    NOTIFICATIONS("Уведомления"),
}

/**
 * Экран «Профиль» в стиле ТВ-версии YummyTV.
 *
 * Сверху — широкий баннер с аватаром, ником, днём рождения/полом и кнопкой выхода.
 * Ниже — табы «Статистика»/«Уведомления». На вкладке статистики: мини-календарь активности
 * со стриком, донат времени просмотра, донат по спискам, топ жанров, гистограмма оценок
 * и счётчики сообщества. Внизу — «Продолжить просмотр» из `/video/last-watches`.
 *
 * Все данные берутся существующими API: профиль и last-watches из [YaniAuthApi],
 * списки/счётчики из [YaniUserListsApi], история просмотров из [YaniHistoryApi].
 * Без авторизации экран показывает форму входа.
 *
 * @param onOpenAnime открыть страницу тайтла по его id.
 * @param onOpenLists перейти к экрану «Мои списки».
 */
@Composable
fun ProfileScreen(
    repository: YaniRepository,
    onOpenAnime: (Int) -> Unit,
    onOpenLists: () -> Unit = {},
) {
    val auth = remember { YaniAuthApi() }
    val listsApi = remember { YaniUserListsApi() }
    val historyApi = remember { YaniHistoryApi() }
    val scope = rememberCoroutineScope()

    var authorized by remember { mutableStateOf(YummyConfig.isAuthorized) }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loginBusy by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var profileError by remember { mutableStateOf<String?>(null) }
    var listsError by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(ProfileTab.STATISTICS) }

    // Профиль: ник, аватар, баннер, день рождения и пол.
    val profile by produceState<UserProfile?>(null, authorized) {
        value = if (!authorized) {
            null
        } else {
            runCatching { auth.profile() }
                .onSuccess {
                    profileError = null
                    YummyConfig.updateUserId(it.id)
                }
                .onFailure { profileError = it.message ?: "Не удалось загрузить профиль" }
                .getOrNull()
        }
    }
    val userId = profile?.id ?: YummyConfig.userId

    // «Продолжить просмотр» — последние серии с сервера.
    val continueWatching by produceState<List<ContinueWatchingItem>>(emptyList(), authorized) {
        value = if (authorized) {
            runCatching { auth.lastWatches() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    // История просмотров — календарь активности, стрик и время просмотра.
    val history by produceState<List<WatchHistoryItem>?>(null, authorized) {
        value = if (authorized) {
            runCatching { historyApi.history(limit = 300) }.getOrDefault(emptyList())
        } else {
            null
        }
    }

    // Списки: статусы, жанры и личные оценки тайтлов.
    val lists by produceState<List<UserListEntry>?>(null, authorized, userId) {
        listsError = null
        value = when {
            !authorized || userId <= 0 -> null
            else -> runCatching { listsApi.lists(userId) }
                .onFailure { listsError = it.message ?: "Не удалось загрузить списки" }
                .getOrDefault(emptyList())
        }
    }

    // Счётчики профиля: друзья, рецензии, коллекции, новости.
    val stats by produceState<ProfileStats?>(null, authorized, userId) {
        value = if (authorized && userId > 0) {
            runCatching { listsApi.stats(userId) }.getOrNull()
        } else {
            null
        }
    }

    if (!authorized) {
        LoginForm(
            login = login,
            password = password,
            busy = loginBusy,
            error = loginError,
            onLoginChange = { login = it },
            onPasswordChange = { password = it },
            onSubmit = {
                loginBusy = true
                loginError = null
                scope.launch {
                    val result = auth.login(login.trim(), password)
                    loginBusy = false
                    result.fold(
                        onSuccess = {
                            authorized = true
                            password = ""
                        },
                        onFailure = { loginError = it.message ?: "Не удалось войти" },
                    )
                }
            },
        )
        return
    }

    val activity = remember(history) { history?.let { activitySummary(it) } }
    val watchTime = remember(history, lists) { history?.let { watchTimeSummary(it, lists.orEmpty()) } }
    val listsSummary = remember(lists) { lists?.let { listSummary(it) } }
    val genres = remember(lists) { lists?.let { genreSummary(it) } }
    val ratings = remember(lists) { lists?.let { ratingSummary(it) } }
    val listsHint = when {
        lists == null -> "Загружаем ваши списки…"
        listsError != null -> "Не удалось загрузить списки"
        else -> "В списках пока нет тайтлов"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 22.dp),
    ) {
        ProfileBanner(
            user = profile,
            statusHint = if (profileError != null) "Не удалось загрузить профиль" else "Загружаем профиль…",
            onLogout = {
                scope.launch {
                    auth.logout()
                    profileError = null
                    listsError = null
                    authorized = false
                }
            },
        )
        Spacer(Modifier.height(20.dp))
        ProfileTabs(selected = tab, onSelect = { tab = it })
        Spacer(Modifier.height(18.dp))
        when (tab) {
            ProfileTab.STATISTICS -> StatisticsTab(
                activity = activity,
                watchTime = watchTime,
                listsSummary = listsSummary,
                genres = genres,
                ratings = ratings,
                stats = stats,
                listsHint = listsHint,
                onOpenLists = onOpenLists,
            )

            ProfileTab.NOTIFICATIONS -> NotificationsPlaceholder()
        }
        if (continueWatching.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            ContinueWatchingSection(continueWatching, onOpenAnime)
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** Широкая карточка-баннер: обложка, аватар, ник, день рождения/пол и выход. */
@Composable
private fun ProfileBanner(
    user: UserProfile?,
    statusHint: String,
    onLogout: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val birthday = formatBirthday(user?.birthday)
    val sex = formatSex(user?.sex)
    Box(
        Modifier
            .fillMaxWidth()
            .height(BannerHeight)
            .clip(shape)
            .background(BannerFallbackBrush),
    ) {
        user?.bannerUrl?.let { banner ->
            PosterImage(banner, Modifier.fillMaxSize(), ContentScale.Crop)
        }
        // Затемнение слева направо, чтобы текст и кнопка читались поверх любого баннера.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0xE0101014),
                        0.6f to Color(0x8C14141A),
                        1f to Color(0x4014141A),
                    ),
                ),
        )
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            ProfileAvatar(user?.avatarUrl)
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    user?.nickname?.takeIf { it.isNotBlank() } ?: "Аккаунт yani",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = BannerText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (user == null) {
                    Text(
                        statusHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BannerTextDim,
                    )
                } else {
                    ProfileMetaLine(birthday = birthday, sex = sex)
                }
                user?.about?.takeIf { it.isNotBlank() }?.let { about ->
                    Text(
                        about,
                        style = MaterialTheme.typography.bodySmall,
                        color = BannerTextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            LogoutCard(onLogout)
        }
    }
}

/** Круглый аватар 110 dp с кольцом. */
@Composable
private fun ProfileAvatar(avatarUrl: String?) {
    val shape = CircleShape
    Box(
        Modifier
            .size(110.dp)
            .clip(shape)
            .background(YummyPalette.SurfaceHigh)
            .border(3.dp, Color(0x33FFFFFF), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarUrl != null) {
            PosterImage(avatarUrl, Modifier.fillMaxSize())
        } else {
            Text("👤", style = MaterialTheme.typography.displayMedium)
        }
    }
}

/** Строка под ником: «📅 24 июля 2026 • Мужской». */
@Composable
private fun ProfileMetaLine(birthday: String?, sex: String?) {
    if (birthday == null && sex == null) {
        Text("Аккаунт YummyAnime", style = MaterialTheme.typography.bodyMedium, color = BannerTextDim)
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (birthday != null) {
            Icon(
                Icons.Outlined.CalendarMonth,
                contentDescription = null,
                tint = BannerTextDim,
                modifier = Modifier.size(17.dp),
            )
            Text(birthday, style = MaterialTheme.typography.bodyMedium, color = BannerTextDim)
        }
        if (birthday != null && sex != null) {
            Text("•", style = MaterialTheme.typography.bodyMedium, color = BannerTextFaint)
        }
        if (sex != null) {
            Text(sex, style = MaterialTheme.typography.bodyMedium, color = BannerTextDim)
        }
    }
}

/** Большая кнопка-карточка «Выйти» с иконкой выхода. */
@Composable
private fun LogoutCard(onLogout: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .width(330.dp)
            .clip(shape)
            .background(Color(0xB3101014))
            .border(1.dp, Color(0x26FFFFFF), shape)
            .clickable(onClick = onLogout)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(YummyPalette.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Logout,
                contentDescription = "Выйти",
                tint = BannerOnAccent,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                "Выйти",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = BannerText,
            )
            Text(
                "Локальная библиотека и прогресс останутся на устройстве",
                style = MaterialTheme.typography.labelSmall,
                color = BannerTextFaint,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Табы профиля: «Статистика» активна по умолчанию, «Уведомления» — заглушка. */
@Composable
private fun ProfileTabs(selected: ProfileTab, onSelect: (ProfileTab) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileTab.entries.forEach { tab ->
            ProfileTabChip(
                title = tab.title,
                selected = selected == tab,
                onClick = { onSelect(tab) },
            )
        }
    }
}

/** Чип-таб в стиле вкладок ТВ-версии. */
@Composable
private fun ProfileTabChip(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) YummyPalette.Accent else YummyPalette.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (selected) BannerOnAccent else YummyPalette.Text,
        )
    }
}

/** Вкладка «Статистика»: карточки секций и «Продолжить просмотр» ниже. */
@Composable
private fun StatisticsTab(
    activity: ActivitySummary?,
    watchTime: WatchTimeSummary?,
    listsSummary: ListSummary?,
    genres: GenreSummary?,
    ratings: RatingSummary?,
    stats: ProfileStats?,
    listsHint: String,
    onOpenLists: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ActivityCard(activity)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WatchTimeCard(watchTime, Modifier.weight(1f).height(300.dp))
            ListsDurationCard(listsSummary, listsHint, Modifier.weight(1f).height(300.dp))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GenresCard(genres, listsHint, Modifier.weight(1f).height(300.dp))
            RatingsCard(ratings, listsHint, Modifier.weight(1f).height(300.dp))
        }
        CommunityCard(stats, onOpenLists)
    }
}

/** Заглушка вкладки «Уведомления»: раздел появится позже, экран не падает. */
@Composable
private fun NotificationsPlaceholder() {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(shape)
            .background(YummyPalette.Surface)
            .border(1.dp, YummyPalette.Divider, shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text("🔔", style = MaterialTheme.typography.displayMedium)
            Text(
                "Уведомления появятся здесь",
                style = MaterialTheme.typography.titleLarge,
                color = YummyPalette.Text,
            )
            Text(
                "Ответы на комментарии, рецензии и новости сообщества — в этом разделе.",
                style = MaterialTheme.typography.bodySmall,
                color = YummyPalette.TextDim,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Блок «Продолжить просмотр»: горизонтальный ряд карточек из last-watches. */
@Composable
private fun ContinueWatchingSection(
    items: List<ContinueWatchingItem>,
    onOpenAnime: (Int) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(YummyPalette.Accent),
            )
            Text(
                "Продолжить просмотр",
                style = MaterialTheme.typography.headlineSmall,
                color = YummyPalette.Text,
            )
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(items, key = { it.animeId }) { item ->
                ContinueWatchingCard(item, onOpenAnime)
            }
        }
    }
}

/** Карточка «Продолжить просмотр»: постер, название, серия и прогресс. */
@Composable
private fun ContinueWatchingCard(
    item: ContinueWatchingItem,
    onOpenAnime: (Int) -> Unit,
) {
    Row(
        Modifier
            .width(320.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(YummyPalette.Surface)
            .clickable { onOpenAnime(item.animeId) }
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(86.dp)
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            PosterImage(item.posterUrl, Modifier.fillMaxSize())
        }
        Column(
            Modifier
                .weight(1f)
                .height(120.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = YummyPalette.Text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.episode.isNotBlank()) {
                    Text(
                        "Серия ${item.episode}",
                        style = MaterialTheme.typography.labelSmall,
                        color = YummyPalette.TextDim,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(YummyPalette.SurfaceHover),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(item.progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(YummyPalette.Accent),
                    )
                }
                Text(
                    "${(item.progress.coerceIn(0f, 1f) * 100).roundToInt()}% просмотрено",
                    style = MaterialTheme.typography.labelSmall,
                    color = YummyPalette.TextFaint,
                )
            }
        }
    }
}

/** Форма входа в аккаунт yani (экран доступен и без авторизации). */
@Composable
private fun LoginForm(
    login: String,
    password: String,
    busy: Boolean,
    error: String?,
    onLoginChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(YummyPalette.Surface)
                .border(1.dp, YummyPalette.Divider, RoundedCornerShape(20.dp))
                .padding(30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Вход в аккаунт yani",
                    style = MaterialTheme.typography.headlineLarge,
                    color = YummyPalette.Text,
                )
                Text(
                    "Логин и пароль от сайта yummyani.me. Вход нужен для статистики профиля, истории и списков.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = YummyPalette.TextDim,
                )
            }
            OutlinedTextField(
                value = login,
                onValueChange = onLoginChange,
                label = { Text("Логин или e-mail") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onSubmit,
                    enabled = !busy && login.isNotBlank() && password.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = YummyPalette.Accent,
                        contentColor = BannerOnAccent,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(44.dp),
                ) {
                    Text(if (busy) "Входим…" else "Войти", fontWeight = FontWeight.Bold)
                }
                error?.let {
                    Text(
                        it,
                        color = Color(0xFFFF8A65),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
