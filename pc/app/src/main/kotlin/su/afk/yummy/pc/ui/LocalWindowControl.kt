// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui

import androidx.compose.runtime.compositionLocalOf

/** Управление окном из глубины дерева композиции (плеер, настройки). */
data class WindowControl(
    val isFullscreen: Boolean = false,
    val toggleFullscreen: () -> Unit = {},
    val window: java.awt.Window? = null,
)

val LocalWindowControl = compositionLocalOf { WindowControl() }
