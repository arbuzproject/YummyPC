// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.chrome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Frame
import java.awt.Point
import java.awt.Window
import java.awt.event.WindowStateListener
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import su.afk.yummy.pc.ui.theme.YummyPalette

private val TitleBarHeight = 36.dp
private val TitleBarButtonWidth = 46.dp
private val CloseButtonHover = Color(0xFFE03B3B)

/** Иконка приложения для заголовка — декодируется один раз на процесс. */
private val appIconPainter: Painter? by lazy {
    runCatching {
        Painter::class.java.classLoader?.getResourceAsStream("icon.png")?.use { stream ->
            ImageIO.read(stream)?.toPainter()
        }
    }.getOrNull()
}

/**
 * Собственная строка заголовка окна вместо системной рамки Windows.
 *
 * Содержит иконку и название приложения, умеет перетаскивать окно и управлять его
 * состоянием (свернуть/развернуть/закрыть), повторяя привычное поведение системного заголовка.
 * При полноэкранном режиме ([isFullscreen]) не рисуется — контент занимает всё окно.
 *
 * @param window окно, созданное Compose (`FrameWindowScope.window`).
 * @param onCloseRequest запрос на закрытие приложения (обычно `::exitApplication`).
 * @param isFullscreen полноэкранный режим (F11/Esc в [su.afk.yummy.pc.main]).
 */
@Composable
fun WindowTitleBar(
    window: ComposeWindow,
    onCloseRequest: () -> Unit,
    isFullscreen: Boolean = false,
) {
    if (isFullscreen) return

    var isMaximized by remember(window) { mutableStateOf(window.isWindowMaximized()) }

    // Иконка «развернуть/восстановить» и двойной клик должны отражать и внешние изменения
    // (Win+↑, Aero Snap, системное меню окна), для этого слушаем состояние окна.
    DisposableEffect(window) {
        val listener = WindowStateListener { event ->
            isMaximized = (event.newState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
        }
        window.addWindowStateListener(listener)
        onDispose { window.removeWindowStateListener(listener) }
    }

    val toggleMaximize: () -> Unit = {
        val maximize = !window.isWindowMaximized()
        // ComposeWindow понимает extendedState так же, как WindowPlacement.Maximized.
        window.extendedState = if (maximize) Frame.MAXIMIZED_BOTH else Frame.NORMAL
        isMaximized = maximize
    }

    Column(
        Modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(YummyPalette.BackgroundElevated),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .windowDragArea(window, onDoubleClick = toggleMaximize),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    Modifier
                        .fillMaxHeight()
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon()
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "YummyPC",
                        color = YummyPalette.Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp,
                        maxLines = 1,
                    )
                }
            }
            TitleBarButton(
                glyph = TitleBarGlyph.Minimize,
                description = "Свернуть",
                onClick = { window.isMinimized = true },
            )
            TitleBarButton(
                glyph = if (isMaximized) TitleBarGlyph.Restore else TitleBarGlyph.Maximize,
                description = if (isMaximized) "Восстановить" else "Развернуть",
                onClick = toggleMaximize,
            )
            TitleBarButton(
                glyph = TitleBarGlyph.Close,
                description = "Закрыть",
                destructive = true,
                onClick = onCloseRequest,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(YummyPalette.Divider),
        )
    }
}

/** Иконка приложения: уменьшенная копия `icon.png` (как у окна в панели задач). */
@Composable
private fun AppIcon() {
    val painter = appIconPainter
    Box(
        Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(YummyPalette.Accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Y",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private enum class TitleBarGlyph { Minimize, Maximize, Restore, Close }

/** Кнопка управления окном: подсветка при наведении, у закрытия — красная. */
@Composable
private fun TitleBarButton(
    glyph: TitleBarGlyph,
    description: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background = when {
        !hovered -> Color.Transparent
        destructive -> CloseButtonHover
        else -> YummyPalette.SurfaceHover
    }
    val contentColor = when {
        !hovered -> YummyPalette.TextDim
        destructive -> Color.White
        else -> YummyPalette.Text
    }
    Box(
        Modifier
            .fillMaxHeight()
            .width(TitleBarButtonWidth)
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        ButtonGlyph(glyph, contentColor)
    }
}

/** Тонкие «виндовые» глифы рисуем сами, Material-иконки для этого слишком тяжёлые. */
@Composable
private fun ButtonGlyph(glyph: TitleBarGlyph, color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val strokeWidth = 1.dp.toPx()
        val inset = 1.5.dp.toPx()
        val stroke = Stroke(width = strokeWidth)
        when (glyph) {
            TitleBarGlyph.Minimize -> drawLine(
                color = color,
                start = Offset(inset, size.height / 2f),
                end = Offset(size.width - inset, size.height / 2f),
                strokeWidth = strokeWidth,
            )

            TitleBarGlyph.Maximize -> drawRect(
                color = color,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                style = stroke,
            )

            TitleBarGlyph.Restore -> {
                val smallInset = 2.dp.toPx()
                val side = size.width - inset * 2f - smallInset
                // Задний квадрат (смещён вправо-вверх) и передний — как в системном заголовке.
                drawRect(
                    color = color,
                    topLeft = Offset(inset + smallInset, inset),
                    size = Size(side, side),
                    style = stroke,
                )
                drawRect(
                    color = color,
                    topLeft = Offset(inset, inset + smallInset),
                    size = Size(side, side),
                    style = stroke,
                )
            }

            TitleBarGlyph.Close -> {
                drawLine(
                    color = color,
                    start = Offset(inset, inset),
                    end = Offset(size.width - inset, size.height - inset),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width - inset, inset),
                    end = Offset(inset, size.height - inset),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * Перетаскивание окна за заголовок + двойной клик по нему для разворачивания/восстановления.
 *
 * Жест не ждёт touch slop (как detectDragGestures), а двигает окно с первого пикселя:
 * для заголовка окна это привычнее. Точка захвата запоминается в экранных координатах,
 * поэтому окно едет ровно за мышью при любом DPI и при переносе между мониторами.
 * Двойной клик обрабатывает отдельный детектор, он не конфликтует с перетаскиванием.
 */
private fun Modifier.windowDragArea(window: ComposeWindow, onDoubleClick: () -> Unit): Modifier = this
    .pointerInput(window) {
        detectTapGestures(onDoubleTap = { onDoubleClick() })
    }
    .pointerInput(window) {
        val density = this
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dragWindowOrigin = window.location
            var dragPointerOrigin = pointerOnScreen(window, down.position, density)
            var dragActive = !window.isWindowMaximized()
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (!event.buttons.isPrimaryPressed) break
                if (change.position == down.position) continue
                change.consume()
                if (window.isWindowMaximized()) {
                    // Развёрнутое окно при перетаскивании сначала восстанавливаем, сохраняя
                    // указатель на том же месте заголовка.
                    val pointer = pointerOnScreen(window, change.position, density)
                    restoreWindowUnderPointer(window, pointer)
                    dragWindowOrigin = window.location
                    dragPointerOrigin = pointer
                    dragActive = !window.isWindowMaximized()
                    continue
                }
                if (!dragActive) continue
                val pointer = pointerOnScreen(window, change.position, density)
                window.location = Point(
                    dragWindowOrigin.x + pointer.x - dragPointerOrigin.x,
                    dragWindowOrigin.y + pointer.y - dragPointerOrigin.y,
                )
            }
        }
    }

/** Разворачивание и восстановление по кнопке/двойному клику. */
private fun ComposeWindow.isWindowMaximized(): Boolean =
    (extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH

/**
 * Восстанавливает развёрнутое окно так, чтобы указатель остался на том же месте заголовка,
 * и перетаскивание продолжалось без скачка.
 */
private fun restoreWindowUnderPointer(window: ComposeWindow, pointerOnScreen: Point) {
    val maximizedBounds = window.bounds
    val horizontalRatio = if (maximizedBounds.width > 0) {
        ((pointerOnScreen.x - maximizedBounds.x).toFloat() / maximizedBounds.width).coerceIn(0f, 1f)
    } else {
        0.5f
    }
    window.extendedState = Frame.NORMAL
    if (window.isWindowMaximized()) return // не удалось восстановить — оставляем как есть
    val restoredBounds = window.bounds
    if (restoredBounds.width <= 0 || restoredBounds.height <= 0) return
    window.location = Point(
        (pointerOnScreen.x - horizontalRatio * restoredBounds.width).roundToInt(),
        maximizedBounds.y,
    )
}

/** Экранная позиция указателя в координатах AWT (они же «dp» окна в Compose Desktop). */
private fun pointerOnScreen(window: Window, pointerInWindow: Offset, density: Density): Point = Point(
    window.x + (pointerInWindow.x / density.density).roundToInt(),
    window.y + (pointerInWindow.y / density.density).roundToInt(),
)
