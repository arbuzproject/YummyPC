// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import su.afk.yummy.pc.api.ProfileStats
import su.afk.yummy.pc.ui.theme.YummyPalette

/** Тёмный текст на оранжевой кнопке. */
private val OnAccentText = Color(0xFF1A0F07)

/** Карточка «Активность»: мини-календарь 5 недель + чип стрика. */
@Composable
internal fun ActivityCard(summary: ActivitySummary?, modifier: Modifier = Modifier) {
    StatisticsCard(
        title = "Активность",
        modifier = modifier,
        subtitle = "Просмотры за последние 5 недель",
    ) {
        if (summary == null) {
            ChartPlaceholder(
                "Загружаем историю просмотров…",
                Modifier.fillMaxWidth().height(180.dp),
            )
            return@StatisticsCard
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ActivityCalendar(summary.days)
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StreakChip(summary.streak)
                Text(
                    "${watchesWord(summary.totalWatches)} за 5 недель",
                    style = MaterialTheme.typography.bodySmall,
                    color = YummyPalette.TextDim,
                )
                Text(
                    plural(summary.activeDays, "активный день", "активных дня", "активных дней"),
                    style = MaterialTheme.typography.bodySmall,
                    color = YummyPalette.TextFaint,
                )
                Text(
                    "Подсветка — количество просмотров: 1–2 светлее, 3+ акцентом",
                    style = MaterialTheme.typography.labelSmall,
                    color = YummyPalette.TextFaint,
                )
            }
        }
    }
}

/** Карточка «Время просмотра»: донат с часами в центре и легенда по типам (или одна строка). */
@Composable
internal fun WatchTimeCard(summary: WatchTimeSummary?, modifier: Modifier = Modifier) {
    StatisticsCard(
        title = "Время просмотра",
        modifier = modifier,
        subtitle = "По истории просмотров",
    ) {
        if (summary == null) {
            ChartPlaceholder(
                "Считаем время просмотра…",
                Modifier.fillMaxWidth().height(180.dp),
            )
            return@StatisticsCard
        }
        // Без разбивки по типам (история yani не отдаёт тип) — один сегмент и строка «Просмотрено».
        val slices = summary.slices.ifEmpty {
            if (summary.totalSeconds > 0) {
                listOf(ChartSlice("Просмотрено", summary.totalSeconds.toFloat(), YummyPalette.Accent))
            } else {
                emptyList()
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DonutChart(
                slices = slices,
                centerTitle = formatHours(summary.totalSeconds),
                centerCaption = "всего",
                modifier = Modifier.size(150.dp),
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (summary.typesKnown && summary.slices.isNotEmpty()) {
                    DonutLegend(
                        slices = summary.slices,
                        valueText = { formatHours(it.value.toLong()) },
                    )
                } else {
                    Text(
                        "Просмотрено: ${formatHours(summary.totalSeconds)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = YummyPalette.Text,
                    )
                }
                Text(
                    "За 7 дней: ${formatHours(summary.weekSeconds)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = YummyPalette.TextDim,
                )
                Text(
                    "${recordsWord(summary.totalRecords)} в истории",
                    style = MaterialTheme.typography.labelSmall,
                    color = YummyPalette.TextFaint,
                )
            }
        }
    }
}

/** Карточка «Продолжительность по спискам»: донат по количеству тайтлов в статусах. */
@Composable
internal fun ListsDurationCard(
    summary: ListSummary?,
    emptyHint: String,
    modifier: Modifier = Modifier,
) {
    StatisticsCard(
        title = "Продолжительность по спискам",
        modifier = modifier,
        subtitle = "Тайтлы в ваших списках",
    ) {
        if (summary == null || summary.slices.isEmpty() || summary.total <= 0) {
            ChartPlaceholder(emptyHint, Modifier.fillMaxWidth().height(180.dp))
            return@StatisticsCard
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DonutChart(
                slices = summary.slices,
                centerTitle = summary.total.toString(),
                centerCaption = titlesWord(summary.total).substringAfter(' '),
                modifier = Modifier.size(150.dp),
            )
            DonutLegend(
                slices = summary.slices,
                valueText = { titlesWord(it.value.toInt()) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Карточка «Жанры»: донат топ-6 жанров по количеству тайтлов из списков. */
@Composable
internal fun GenresCard(
    summary: GenreSummary?,
    emptyHint: String,
    modifier: Modifier = Modifier,
) {
    val topCount = summary?.slices?.count { it.label != "Другие" } ?: 0
    StatisticsCard(
        title = "Жанры",
        modifier = modifier,
        subtitle = summary?.let { "Уникальных жанров в списках: ${it.genreCount}" } ?: "Топ жанров по тайтлам",
    ) {
        if (summary == null || summary.slices.isEmpty() || topCount == 0) {
            ChartPlaceholder(emptyHint, Modifier.fillMaxWidth().height(180.dp))
            return@StatisticsCard
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DonutChart(
                slices = summary.slices,
                centerTitle = "Топ-$topCount",
                centerCaption = "жанров",
                modifier = Modifier.size(150.dp),
            )
            DonutLegend(
                slices = summary.slices,
                valueText = { slice -> slice.valueLabel ?: titlesWord(slice.value.toInt()) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Карточка «Оценки»: средняя оценка и гистограмма 1..10. */
@Composable
internal fun RatingsCard(
    summary: RatingSummary?,
    emptyHint: String,
    modifier: Modifier = Modifier,
) {
    val average = summary?.average
    val title = if (average != null) "Оценки (Средняя ${formatAverage(average)})" else "Оценки"
    StatisticsCard(
        title = title,
        modifier = modifier,
        subtitle = summary?.takeIf { it.rated > 0 }?.let { "${ratingsWord(it.rated)} из списков" }
            ?: "Личные оценки тайтлов",
    ) {
        if (summary == null || summary.rated == 0 || average == null) {
            ChartPlaceholder(emptyHint, Modifier.fillMaxWidth().height(180.dp))
            return@StatisticsCard
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatValue(formatAverage(average), "средняя оценка", accent = true)
            StatValue(summary.rated.toString(), "оценено тайтлов")
        }
        Spacer(Modifier.height(16.dp))
        RatingHistogram(summary.histogram)
    }
}

/** Карточка «Сообщество»: счётчики друзей, рецензий, комментариев, новостей и коллекций. */
@Composable
internal fun CommunityCard(
    stats: ProfileStats?,
    onOpenLists: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StatisticsCard(
        title = "Сообщество",
        modifier = modifier,
        subtitle = "Ваш след на YummyAnime",
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CommunityChip("Друзья", stats?.friends)
            CommunityChip("Рецензии", stats?.reviews)
            // Отдельного счётчика комментариев API не отдаёт — показываем рецензии, как в ТВ-версии.
            CommunityChip("Комментарии", stats?.reviews)
            CommunityChip("Новости", stats?.posts)
            CommunityChip("Коллекции", stats?.collections)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onOpenLists,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = YummyPalette.Accent,
                    contentColor = OnAccentText,
                ),
                modifier = Modifier.height(44.dp),
            ) {
                Text("Мои списки", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Счётчик-чип сообщества: подпись и число (прочерк, пока данные не пришли). */
@Composable
private fun CommunityChip(label: String, value: Int?) {
    Row(
        Modifier
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(YummyPalette.SurfaceHigh)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = YummyPalette.TextDim,
        )
        Text(
            value?.toString() ?: "—",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = YummyPalette.Text,
        )
    }
}

/** Крупное число с подписью — для карточки оценок. */
@Composable
private fun StatValue(value: String, caption: String, accent: Boolean = false) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = if (accent) YummyPalette.Accent else YummyPalette.Text,
        )
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = YummyPalette.TextDim,
        )
    }
}
