// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import su.afk.yummy.pc.ui.LocalWindowControl
import su.afk.yummy.pc.ui.theme.YummyPalette

/**
 * Ряд карточек с прокруткой стрелками.
 *
 * Стрелки показываются только когда курсор рядом с краем ряда (как в ТВ-интерфейсе),
 * колесо мыши ряд не листает.
 */
@Composable
fun SmoothRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp),
    spacing: Dp = 14.dp,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val windowControl = LocalWindowControl.current
    var pointerX by remember { mutableStateOf<Float?>(null) }
    var rowOrigin by remember { mutableStateOf<Offset?>(null) }
    var rowWidthPx by remember { mutableStateOf(0) }
    val edgePx = 90f

    // Глобальное отслеживание мыши: Compose не получает Move над нативным видео/при быстром движении.
    LaunchedEffect(windowControl.window) {
        val window = windowControl.window ?: return@LaunchedEffect
        while (true) {
            delay(120)
            val screenPointer = runCatching { java.awt.MouseInfo.getPointerInfo()?.location }.getOrNull()
            val origin = rowOrigin
            if (screenPointer == null || origin == null) {
                pointerX = null
                continue
            }
            val windowOrigin = runCatching { window.locationOnScreen }.getOrNull()
            if (windowOrigin == null) {
                pointerX = null
                continue
            }
            val localX = (screenPointer.x - windowOrigin.x).toFloat() - origin.x
            val localY = (screenPointer.y - windowOrigin.y).toFloat() - origin.y
            pointerX = if (localX in 0f..rowWidthPx.toFloat() && localY in 0f..1600f) localX else null
        }
    }

    Box(modifier) {
        LazyRow(
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    rowWidthPx = coordinates.size.width
                    rowOrigin = coordinates.positionInWindow()
                },
            content = content,
        )
        val x = pointerX
        val canBack = state.canScrollBackward
        val canForward = state.canScrollForward
        if (x != null && x < edgePx && canBack) {
            RowArrow(forward = false, modifier = Modifier.align(Alignment.CenterStart)) {
                scope.launch { state.animateScrollBy(-900f) }
            }
        }
        if (x != null && x > rowWidthPx - edgePx && rowWidthPx > 0 && canForward) {
            RowArrow(forward = true, modifier = Modifier.align(Alignment.CenterEnd)) {
                scope.launch { state.animateScrollBy(900f) }
            }
        }
    }
}

@Composable
private fun RowArrow(forward: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0xCC1C1C22))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (forward) Icons.Filled.ChevronRight else Icons.Filled.ChevronLeft,
            contentDescription = if (forward) "Дальше" else "Назад",
            tint = YummyPalette.Text,
            modifier = Modifier.size(26.dp),
        )
    }
}
