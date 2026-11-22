// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import su.afk.yummy.pc.api.YummyConfig
import su.afk.yummy.pc.player.MpvBridgeFactory
import su.afk.yummy.pc.player.MpvControllerBridge
import su.afk.yummy.pc.ui.App
import su.afk.yummy.pc.ui.LocalWindowControl
import su.afk.yummy.pc.ui.Screen
import su.afk.yummy.pc.ui.WindowControl
import su.afk.yummy.pc.ui.chrome.WindowTitleBar
import su.afk.yummy.pc.ui.theme.YummyPalette

/**
 * Точка входа desktop-клиента.
 *
 * Служебные аргументы: --smoke, --screen=..., --anime=<id>, --query=<текст>, --autoplay,
 * --player=<имя>, --panel=sources|info, --fullscreen, --dump-skips=<id>, --dump-sources=<id>/<ep>.
 */
fun main(args: Array<String>) {
    val smoke = args.contains("--smoke")
    val screenArg = args.firstOrNull { it.startsWith("--screen=") }?.substringAfter('=')
    val animeId = args.firstOrNull { it.startsWith("--anime=") }?.substringAfter('=')?.toIntOrNull()
    val query = args.firstOrNull { it.startsWith("--query=") }?.substringAfter('=').orEmpty()
    val autoPlay = args.contains("--autoplay")
    val autoPlayer = args.firstOrNull { it.startsWith("--player=") }?.substringAfter('=').orEmpty()
    val playerPanel = args.firstOrNull { it.startsWith("--panel=") }?.substringAfter('=').orEmpty()
    val startFullscreen = args.contains("--fullscreen")

    YummyConfig.load()
    su.afk.yummy.pc.ui.theme.YummyThemeController.refreshFromConfig()
    MpvBridgeFactory.factory = { pipeName -> MpvControllerBridge(pipeName) }

    args.firstOrNull { it.startsWith("--dump-skips=") }?.substringAfter('=')?.toIntOrNull()?.let { id ->
        runBlocking {
            val page = su.afk.yummy.pc.api.YaniRepository().page(id, force = true)
            println("page: ${page.title} (${page.videos.size} видео)")
            page.videos.distinctBy { it.episode }.take(12).forEach { video ->
                println(
                    "ep=${video.episode} player=${video.player} " +
                        "opening=${video.openingSkip?.let { "${it.startSec}+${it.lengthSec}" }} " +
                        "ending=${video.endingSkip?.let { "${it.startSec}+${it.lengthSec}" }}",
                )
            }
        }
        return
    }
    args.firstOrNull { it.startsWith("--dump-sources=") }?.substringAfter('=')?.let { spec ->
        val (animePart, episodePart) = spec.split('/').take(2)
        val id = animePart.toIntOrNull() ?: 0
        val episode = episodePart.toIntOrNull() ?: 1
        runBlocking {
            val result = runCatching { su.afk.yummy.pc.api.ExternalSourcesApi().sources(id, episode) }
            println("sources($id/$episode): ${result.getOrNull()} / error=${result.exceptionOrNull()?.message}")
        }
        return
    }

    val initialScreen: Screen = when {
        screenArg == "details" && animeId != null -> Screen.Details(animeId)
        screenArg == "search" -> Screen.Search
        screenArg == "top" -> Screen.Top
        screenArg == "schedule" -> Screen.Schedule
        screenArg == "profile" -> Screen.Profile
        screenArg == "lists" -> Screen.Lists
        screenArg == "collections" -> Screen.Collections
        screenArg == "history" -> Screen.History
        screenArg == "settings" -> Screen.Settings
        screenArg == "news" -> Screen.News
        screenArg == "comments" && animeId != null -> Screen.Comments(animeId, "")
        else -> Screen.Home
    }

    application {
        var fullscreen by remember { mutableStateOf(startFullscreen) }
        val windowState = rememberWindowState(
            width = 1440.dp,
            height = 900.dp,
            placement = if (startFullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating,
        )

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "YummyPC — неофициальный клиент YummyAnime",
            // Заголовок окна рисует само приложение (WindowTitleBar), системную рамку Windows прячем.
            undecorated = true,
            // F11/Esc обрабатываются на уровне окна: фокус клавиатуры в Compose обычно принадлежит
            // контенту окна, поэтому AWT KeyListener у JFrame эти события не получает.
            onPreviewKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.F11 -> {
                            fullscreen = !fullscreen
                            true
                        }

                        Key.Escape -> {
                            if (fullscreen) {
                                fullscreen = false
                                true
                            } else {
                                false
                            }
                        }

                        else -> false
                    }
                }
            },
        ) {
            DisposableEffect(window) {
                runCatching {
                    javaClass.classLoader?.getResourceAsStream("icon.png")?.use { stream ->
                        window.iconImage = javax.imageio.ImageIO.read(stream)
                    }
                }
                onDispose { }
            }
            CompositionLocalProvider(
                LocalWindowControl provides WindowControl(
                    isFullscreen = fullscreen,
                    toggleFullscreen = { fullscreen = !fullscreen },
                    window = window,
                ),
            ) {
                LaunchedEffect(fullscreen) {
                    windowState.placement = if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
                }
                // Кастомный заголовок — часть интерфейса приложения; в полноэкранном режиме скрыт.
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(YummyPalette.Background),
                ) {
                    WindowTitleBar(
                        window = window,
                        onCloseRequest = ::exitApplication,
                        isFullscreen = fullscreen,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        App(
                            initialScreen = initialScreen,
                            initialQuery = query,
                            autoPlay = autoPlay,
                            autoPlayer = autoPlayer,
                            playerPanel = playerPanel,
                        )
                    }
                }
            }
            DisposableEffect(window) {
                val listener = object : java.awt.event.KeyAdapter() {
                    override fun keyPressed(e: java.awt.event.KeyEvent) {
                        when (e.keyCode) {
                            java.awt.event.KeyEvent.VK_F11 -> {
                                fullscreen = !fullscreen
                                e.consume()
                            }
                            java.awt.event.KeyEvent.VK_ESCAPE -> {
                                if (fullscreen) {
                                    fullscreen = false
                                    e.consume()
                                }
                            }
                        }
                    }
                }
                window.addKeyListener(listener)
                onDispose { window.removeKeyListener(listener) }
            }
        }

        if (smoke) {
            LaunchedEffect(Unit) {
                delay(4000)
                println("SMOKE OK: окно открылось")
                kotlin.system.exitProcess(0)
            }
        }
    }
}
