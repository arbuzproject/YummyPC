// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.Job
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import su.afk.yummy.pc.api.UserProfile
import su.afk.yummy.pc.api.LocalLibrary
import su.afk.yummy.pc.api.UpdateChecker
import su.afk.yummy.pc.api.UpdateInfo
import su.afk.yummy.pc.api.YaniApi
import su.afk.yummy.pc.api.YaniAuthApi
import su.afk.yummy.pc.api.AppInfo
import su.afk.yummy.pc.api.YaniRepository
import su.afk.yummy.pc.api.YummyConfig
import su.afk.yummy.pc.player.PlaybackRequest
import su.afk.yummy.pc.player.resolveContinuePlayback
import su.afk.yummy.pc.player.resolveHeroPlayback
import su.afk.yummy.pc.ui.comments.CommentsScreen
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import su.afk.yummy.pc.ui.components.PosterImage
import su.afk.yummy.pc.ui.details.DetailsScreen
import su.afk.yummy.pc.ui.history.HistoryScreen
import su.afk.yummy.pc.ui.home.HomeScreen
import su.afk.yummy.pc.ui.lists.ListsScreen
import su.afk.yummy.pc.ui.news.NewsScreen
import su.afk.yummy.pc.ui.player.PlayerScreen
import su.afk.yummy.pc.ui.profile.ProfileScreen
import su.afk.yummy.pc.ui.schedule.ScheduleScreen
import su.afk.yummy.pc.ui.search.SearchScreen
import su.afk.yummy.pc.ui.settings.SettingsScreen
import su.afk.yummy.pc.ui.theme.YummyPalette
import su.afk.yummy.pc.ui.theme.YummyTheme
import su.afk.yummy.pc.ui.top.TopScreen

sealed interface Screen {
    data object Home : Screen
    data object Top : Screen
    data object Schedule : Screen
    data object Search : Screen
    data object Profile : Screen
    data object Lists : Screen
    data object Collections : Screen
    data object History : Screen
    data object News : Screen
    data class Comments(val animeId: Int, val title: String) : Screen
    data object Settings : Screen
    data class Details(val id: Int) : Screen
}

@Composable
fun App(
    initialScreen: Screen = Screen.Home,
    initialQuery: String = "",
    autoPlay: Boolean = false,
    autoPlayer: String = "",
    playerPanel: String = "",
) {
    val repository = remember { YaniRepository() }
    val auth = remember { YaniAuthApi() }
    var screen by remember { mutableStateOf(initialScreen) }
    var previous by remember { mutableStateOf<Screen>(Screen.Home) }
    var playback by remember { mutableStateOf<PlaybackRequest?>(null) }
    var searchGenre by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // Экран-прокладка при быстром запуске с главной: пока идёт резолв потока.
    var quickLabel by remember { mutableStateOf<String?>(null) }
    var quickError by remember { mutableStateOf<String?>(null) }
    var quickJob by remember { mutableStateOf<Job?>(null) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateDismissed by remember { mutableStateOf(false) }

    // Проверка обновлений при запуске (последний релиз GitHub).
    LaunchedEffect(Unit) {
        updateInfo = runCatching { UpdateChecker().check() }.getOrNull()
    }

    fun quickPlay(label: String, loader: suspend () -> PlaybackRequest?) {
        if (quickLabel != null) return
        quickLabel = label
        quickError = null
        quickJob?.cancel()
        quickJob = scope.launch {
            val request = runCatching { loader() }.getOrNull()
            quickLabel = null
            if (request != null) playback = request else quickError = "Не удалось открыть плеер. Попробуйте ещё раз"
        }
    }

    // Дослать локально накопленные действия со списками, если аккаунт доступен.
    LaunchedEffect(YummyConfig.isAuthorized) {
        if (YummyConfig.isAuthorized) {
            runCatching { LocalLibrary.flush(YaniApi()) }
        }
    }

    // Аватар в рейке — из профиля yani.
    val profile by produceState<UserProfile?>(null, YummyConfig.isAuthorized) {
        value = if (YummyConfig.isAuthorized) runCatching { auth.profile() }.getOrNull() else null
    }

    fun open(next: Screen) {
        previous = screen
        screen = next
    }

    YummyTheme {
        val active = playback
        if (active != null) {
            PlayerScreen(active, initialPanel = playerPanel, onClose = { playback = null })
            return@YummyTheme
        }

        if (quickLabel != null) {
            Box(
                Modifier.fillMaxSize().background(Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator(color = YummyPalette.Accent, modifier = Modifier.size(40.dp))
                    Text(quickLabel ?: "", color = YummyPalette.Text, style = MaterialTheme.typography.titleMedium)
                    Text("Готовим видеопоток…", color = YummyPalette.TextDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val pendingUpdate = updateInfo
        if (pendingUpdate != null && !updateDismissed) {
            AlertDialog(
                onDismissRequest = { updateDismissed = true },
                title = { Text("Доступно обновление ${pendingUpdate.version}", color = YummyPalette.Text) },
                text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        Text("Текущая версия: ${AppInfo.VERSION}", color = YummyPalette.TextDim)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            pendingUpdate.notes.ifBlank { pendingUpdate.name },
                            color = YummyPalette.Text,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching {
                            java.awt.Desktop.getDesktop().browse(
                                java.net.URI(pendingUpdate.downloadUrl ?: pendingUpdate.pageUrl),
                            )
                        }
                        updateDismissed = true
                    }) { Text("Скачать", color = YummyPalette.Accent) }
                },
                dismissButton = {
                    TextButton(onClick = { updateDismissed = true }) { Text("Позже", color = YummyPalette.TextDim) }
                },
                containerColor = YummyPalette.BackgroundElevated,
            )
        }
        Row(Modifier.fillMaxSize().background(YummyPalette.Background)) {
            NavigationRail(
                current = screen,
                avatarUrl = profile?.avatarUrl,
                onSelect = { target ->
                    if (target != screen) {
                        if (target is Screen.Search) searchGenre = ""
                        screen = target
                    }
                },
            )
            Box(Modifier.fillMaxHeight().weight(1f)) {
                when (val current = screen) {
                    is Screen.Home -> HomeScreen(
                        repository = repository,
                        onOpen = { open(Screen.Details(it)) },
                        onPlayHero = { id -> quickPlay("Запускаем тайтл…") { resolveHeroPlayback(id) } },
                        onContinuePlay = { id, episode ->
                            quickPlay("Открываем серию…") { resolveContinuePlayback(id, episode) }
                        },
                    )

                    is Screen.Top -> TopScreen(
                        repository = repository,
                        initialType = null,
                        onOpen = { open(Screen.Details(it)) },
                    )

                    is Screen.Schedule -> ScheduleScreen(
                        repository = repository,
                        onOpen = { open(Screen.Details(it)) },
                    )

                    is Screen.Search -> SearchScreen(
                        repository = repository,
                        initialQuery = initialQuery,
                        initialGenre = searchGenre,
                        onOpen = { open(Screen.Details(it)) },
                    )

                    is Screen.Profile -> ProfileScreen(
                        repository = repository,
                        onOpenAnime = { open(Screen.Details(it)) },
                        onOpenLists = { open(Screen.Lists) },
                    )

                    is Screen.Collections -> su.afk.yummy.pc.ui.collections.CollectionsScreen(
                        repository = repository,
                        onOpenAnime = { open(Screen.Details(it)) },
                    )

                    is Screen.Lists -> ListsScreen(
                        onOpenAnime = { open(Screen.Details(it)) },
                    )

                    is Screen.History -> HistoryScreen(
                        onOpenAnime = { open(Screen.Details(it)) },
                    )

                    is Screen.Comments -> CommentsScreen(
                        animeId = current.animeId,
                        animeTitle = current.title,
                        onBack = { screen = previous },
                    )

                    is Screen.News -> NewsScreen(
                        onOpenAnime = { open(Screen.Details(it)) },
                    )

                    is Screen.Settings -> SettingsScreen()

                    is Screen.Details -> DetailsScreen(
                        repository = repository,
                        animeId = current.id,
                        autoPlay = autoPlay,
                        autoPlayer = autoPlayer,
                        onPlay = { playback = it },
                        onOpenAnime = { open(Screen.Details(it)) },
                        onBack = { screen = previous },
                        onOpenSearch = { genre ->
                            searchGenre = genre
                            previous = screen
                            screen = Screen.Search
                        },
                        onOpenComments = { id, title ->
                            previous = screen
                            screen = Screen.Comments(id, title)
                        },
                    )
                }
            }
        }
        if (quickLabel != null) {
            Box(
                Modifier.fillMaxSize().background(Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator(color = YummyPalette.Accent, modifier = Modifier.size(40.dp))
                    Text(quickLabel ?: "", color = YummyPalette.Text, style = MaterialTheme.typography.titleMedium)
                    Text("Готовим видеопоток…", color = YummyPalette.TextDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun NavigationRail(
    current: Screen,
    avatarUrl: String?,
    onSelect: (Screen) -> Unit,
) {
    Column(
        Modifier
            .width(92.dp)
            .fillMaxHeight()
            .background(YummyPalette.BackgroundElevated)
            .padding(vertical = 18.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (current is Screen.Profile) YummyPalette.AccentSoft else YummyPalette.SurfaceHigh)
                .clickable { onSelect(Screen.Profile) },
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl != null) {
                PosterImage(avatarUrl, Modifier.fillMaxSize())
            } else {
                Text("Я", style = MaterialTheme.typography.titleMedium, color = YummyPalette.Text)
            }
        }
        Spacer(Modifier.height(14.dp))
        RailButton(Icons.Outlined.Search, current is Screen.Search) { onSelect(Screen.Search) }
        RailButton(Icons.Outlined.Home, current is Screen.Home) { onSelect(Screen.Home) }
        RailButton(Icons.Outlined.CalendarMonth, current is Screen.Schedule) { onSelect(Screen.Schedule) }
        RailButton(Icons.Outlined.Star, current is Screen.Top) { onSelect(Screen.Top) }
        RailButton(Icons.Outlined.Bookmarks, current is Screen.Lists) { onSelect(Screen.Lists) }
        RailButton(Icons.Outlined.GridView, current is Screen.Collections) { onSelect(Screen.Collections) }
        RailButton(Icons.Outlined.WatchLater, current is Screen.History) { onSelect(Screen.History) }
        RailButton(Icons.Outlined.Newspaper, current is Screen.News) { onSelect(Screen.News) }
        Spacer(Modifier.weight(1f))
        RailButton(Icons.Outlined.Settings, current is Screen.Settings) { onSelect(Screen.Settings) }
    }
}

@Composable
private fun RailButton(icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) YummyPalette.SurfaceHigh else Color.Transparent),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) YummyPalette.Accent else YummyPalette.TextDim,
            modifier = Modifier.size(26.dp),
        )
    }
}

/** Заглушка «страница в разработке». */
@Composable
fun PlaceholderScreen(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = YummyPalette.Text)
            Text("Скоро", color = YummyPalette.TextDim)
        }
    }
}
