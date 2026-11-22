// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import su.afk.yummy.pc.api.WatchHistoryItem
import su.afk.yummy.pc.ui.components.PosterImage
import su.afk.yummy.pc.ui.theme.YummyPalette
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** Высота карточки-строки: одинаковая у всех записей, чтобы список выглядел ровным. */
private val CardHeight: Dp = 128.dp

/** Внутренние отступы карточки: постер и колонка текста стоят в одном ритме. */
private val CardPadding: Dp = 12.dp

/** Постер истории уже, чем в сетках: карточка здесь горизонтальная. */
private val PosterWidth: Dp = 76.dp

/** Короткие названия месяцев без точки: CLDR для ru даёт «нояб.», а в подписи нужен «ноя». */
private val RU_MONTHS_SHORT: List<String> =
    listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

/** Ширина колонки с процентом просмотра. */
private val PercentWidth: Dp = 40.dp

/**
 * Дата просмотра «22 ноя 2026, 16:40» из unix-секунд.
 *
 * yani присылает `date` в секундах; null или неположительное значение означает «даты нет».
 * Месяц берём из [RU_MONTHS_SHORT], чтобы подпись совпадала с макетом без точки после сокращения.
 */
internal fun formatWatchedAt(seconds: Long?): String? {
    val value = seconds?.takeIf { it > 0 } ?: return null
    val date = Instant.ofEpochSecond(value).atZone(ZoneId.systemDefault())
    val month = RU_MONTHS_SHORT[date.monthValue - 1]
    return String.format(
        Locale.ROOT,
        "%d %s %d, %02d:%02d",
        date.dayOfMonth,
        month,
        date.year,
        date.hour,
        date.minute,
    )
}

/**
 * Карточка записи истории просмотра: постер, название, серия, дата и прогресс.
 *
 * Клик по всей карточке открывает страницу тайтла ([onClick]). Если сервер не прислал
 * длительность серии, полоса прогресса не рисуется — вместо неё показывается позиция остановки.
 *
 * @param item запись истории из [su.afk.yummy.pc.api.YaniHistoryApi].
 * @param onClick открыть страницу тайтла по его id.
 */
@Composable
fun HistoryCard(item: WatchHistoryItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(CardHeight)
            .clip(RoundedCornerShape(14.dp))
            .background(YummyPalette.Surface)
            .clickable(onClick = onClick)
            .padding(CardPadding),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(PosterWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp)),
        ) {
            PosterImage(item.posterUrl, Modifier.fillMaxSize())
        }
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = YummyPalette.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.episode.isNotBlank()) {
                        Text(
                            "Серия ${item.episode}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = YummyPalette.Accent,
                        )
                    }
                    formatWatchedAt(item.watchedAtSec)?.let { watchedAt ->
                        Text(watchedAt, style = MaterialTheme.typography.labelSmall, color = YummyPalette.TextDim)
                    }
                }
            }
            WatchProgress(item)
        }
        Text(
            "›",
            style = MaterialTheme.typography.headlineSmall,
            color = YummyPalette.TextFaint,
        )
    }
}

/** Нижняя строка карточки: доля просмотра или позиция остановки. */
@Composable
private fun WatchProgress(item: WatchHistoryItem) {
    if (item.durationSec <= 0) {
        if (item.positionSec > 0) {
            Text(
                "Остановились на ${clockPosition(item.positionSec)}",
                style = MaterialTheme.typography.labelSmall,
                color = YummyPalette.TextDim,
            )
        }
        return
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(YummyPalette.SurfaceHover),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(item.progress)
                    .height(4.dp)
                    .background(YummyPalette.Accent),
            )
        }
        Text(
            "${(item.progress * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = YummyPalette.TextDim,
            modifier = Modifier.width(PercentWidth),
        )
    }
}

/** Секунды в «мм:сс» или «ч:мм:сс» — подпись позиции, когда длительность неизвестна. */
private fun clockPosition(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
