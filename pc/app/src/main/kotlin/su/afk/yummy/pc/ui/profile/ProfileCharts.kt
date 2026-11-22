// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import su.afk.yummy.pc.ui.theme.YummyPalette

/** Текст на оранжевой плашке (акцентный фон) — тёмный, как в остальном клиенте. */
private val OnAccentText = Color(0xFF1A0F07)

/** Размер клетки мини-календаря активности. */
private val CalendarCellSize = 30.dp

/** Подписи дней недели мини-календаря: неделя начинается с понедельника. */
private val WeekdayLabels = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/** Карточка статистики на тёмной поверхности — общая оболочка секций профиля. */
@Composable
internal fun StatisticsCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(YummyPalette.Surface)
            .border(1.dp, YummyPalette.Divider, shape)
            .padding(20.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = YummyPalette.Text,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = YummyPalette.TextDim,
            )
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/**
 * Донат-круг на Canvas: дорожка + дуги сегментов, в центре — крупная подпись.
 *
 * @param slices сегменты диаграммы; нулевые и пустые значения не рисуются.
 * @param centerTitle крупный текст в центре (часы, количество тайтлов или «Топ-6»).
 */
@Composable
internal fun DonutChart(
    slices: List<ChartSlice>,
    centerTitle: String,
    modifier: Modifier = Modifier,
    centerCaption: String? = null,
    ringWidth: Dp = 16.dp,
) {
    val visible = slices.filter { it.value > 0f }
    val total = visible.sumOf { it.value.toDouble() }.toFloat()
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = ringWidth.toPx()
            val diameter = (size.minDimension - stroke).coerceAtLeast(0f)
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            drawArc(
                color = YummyPalette.SurfaceHigh,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )
            if (total <= 0f) return@Canvas

            // Небольшой зазор между сегментами; у единственного сегмента зазора нет.
            val gap = if (visible.size > 1) 3f else 0f
            var startAngle = -90f
            visible.forEach { slice ->
                val sweep = 360f * slice.value / total
                drawArc(
                    color = slice.color,
                    startAngle = startAngle + gap / 2f,
                    sweepAngle = (sweep - gap).coerceAtLeast(1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
                startAngle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                centerTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = YummyPalette.Text,
            )
            if (!centerCaption.isNullOrBlank()) {
                Text(
                    centerCaption,
                    style = MaterialTheme.typography.labelSmall,
                    color = YummyPalette.TextDim,
                )
            }
        }
    }
}

/** Легенда доната: цветная точка, подпись и значение справа. */
@Composable
internal fun DonutLegend(
    slices: List<ChartSlice>,
    valueText: (ChartSlice) -> String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        slices.filter { it.value > 0f }.forEach { slice ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(slice.color),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    slice.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = YummyPalette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    valueText(slice),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = YummyPalette.Text,
                )
            }
        }
    }
}

/** Заглушка внутри карточки статистики: загрузка, ошибка или пустые данные. */
@Composable
internal fun ChartPlaceholder(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = YummyPalette.TextDim,
            textAlign = TextAlign.Center,
        )
    }
}

/** Мини-календарь активности: 5 строк-недель × 7 колонок Пн–Вс, дни числами. */
@Composable
internal fun ActivityCalendar(days: List<ActivityDay>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            WeekdayLabels.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.width(CalendarCellSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = YummyPalette.TextFaint,
                    textAlign = TextAlign.Center,
                )
            }
        }
        days.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day -> ActivityDayCell(day) }
            }
        }
    }
}

/** Клетка календаря: число + подсветка по интенсивности просмотров; сегодня обведено акцентом. */
@Composable
private fun ActivityDayCell(day: ActivityDay) {
    val shape = RoundedCornerShape(7.dp)
    val todayShape = RoundedCornerShape(8.dp)
    val background = when {
        day.isFuture -> YummyPalette.SurfaceHigh.copy(alpha = 0.35f)
        day.count <= 0 -> YummyPalette.SurfaceHigh
        // 1–2 записи — светлее, 3 и больше — акцент.
        day.count <= 2 -> YummyPalette.Accent.copy(alpha = 0.35f)
        else -> YummyPalette.Accent.copy(alpha = 0.85f)
    }
    val textColor = when {
        day.isFuture -> YummyPalette.TextFaint.copy(alpha = 0.6f)
        day.count >= 3 -> OnAccentText
        day.count > 0 -> YummyPalette.Text
        else -> YummyPalette.TextDim
    }
    // У сегодняшнего дня акцентное кольцо с зазором: подсветка активности остаётся видна внутри.
    val cell = if (day.isToday) {
        Modifier
            .size(CalendarCellSize)
            .border(1.5.dp, YummyPalette.Accent, todayShape)
            .padding(2.dp)
    } else {
        Modifier.size(CalendarCellSize)
    }
    Box(
        cell
            .clip(shape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
    }
}

/** Чип стрика: «🔥 3 дня подряд». */
@Composable
internal fun StreakChip(streak: Int) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(YummyPalette.AccentSoft)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("🔥", style = MaterialTheme.typography.titleSmall)
        Text(
            "${daysWord(streak)} подряд",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = YummyPalette.Accent,
        )
    }
}

/**
 * Гистограмма оценок 1..10: столбики рисуются на Canvas, подписи — рядом.
 *
 * Высота столбика пропорциональна количеству оценок; у самой частой оценки — полная яркость.
 * Над столбиками показано количество, снизу — шкала 1..10.
 */
@Composable
internal fun RatingHistogram(histogram: List<Int>, modifier: Modifier = Modifier) {
    val maxCount = histogram.maxOrNull() ?: 0
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            histogram.forEach { count ->
                Text(
                    if (count > 0) count.toString() else "",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = YummyPalette.TextFaint,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(92.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val slot = size.width / 10f
                val barWidth = slot * 0.62f
                val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                // Базовая линия, чтобы шкала читалась даже при малом числе оценок.
                drawLine(
                    color = YummyPalette.Divider,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
                histogram.forEachIndexed { index, count ->
                    val x = slot * index + (slot - barWidth) / 2f
                    if (count <= 0 || maxCount <= 0) {
                        drawRoundRect(
                            color = YummyPalette.SurfaceHigh,
                            topLeft = Offset(x, size.height - 3.dp.toPx()),
                            size = Size(barWidth, 3.dp.toPx()),
                            cornerRadius = radius,
                        )
                        return@forEachIndexed
                    }
                    val ratio = count.toFloat() / maxCount
                    val barHeight = (size.height * ratio).coerceAtLeast(4.dp.toPx())
                    drawRoundRect(
                        color = YummyPalette.Accent.copy(alpha = if (count == maxCount) 1f else 0.7f),
                        topLeft = Offset(x, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = radius,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            (1..10).forEach { rating ->
                Text(
                    rating.toString(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = YummyPalette.TextDim,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
