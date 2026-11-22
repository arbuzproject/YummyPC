// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import su.afk.yummy.pc.api.ScheduleDay
import su.afk.yummy.pc.api.ScheduleItem
import su.afk.yummy.pc.api.YaniRepository
import su.afk.yummy.pc.ui.components.ErrorView
import su.afk.yummy.pc.ui.components.LoadState
import su.afk.yummy.pc.ui.components.LoadingView
import su.afk.yummy.pc.ui.components.PosterCard
import su.afk.yummy.pc.ui.components.SmoothRow
import su.afk.yummy.pc.ui.theme.YummyPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU_LOCALE = Locale.forLanguageTag("ru-RU")
private val RU_MONTHS = arrayOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
private val RU_DAYS = arrayOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
private fun ruDayLabel(date: java.time.LocalDate): String =
    "${date.dayOfMonth} ${RU_MONTHS[date.monthValue - 1]}, ${RU_DAYS[date.dayOfWeek.value - 1]}"
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", RU_LOCALE)

/** Расписание выхода серий у онгоингов. */
@Composable
fun ScheduleScreen(repository: YaniRepository, onOpen: (Int) -> Unit) {
    var reloadKey by remember { mutableStateOf(0) }
    val state by produceState<LoadState<List<ScheduleDay>>>(LoadState.Loading, repository, reloadKey) {
        value = LoadState.Loading
        value = try {
            LoadState.Data(repository.schedule())
        } catch (e: Exception) {
            LoadState.Error(e.message ?: e.toString())
        }
    }

    when (val current = state) {
        is LoadState.Loading -> LoadingView("Загружаем расписание…")
        is LoadState.Error -> ErrorView(current.message) { reloadKey++ }
        is LoadState.Data -> LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    "Расписание",
                    style = MaterialTheme.typography.headlineLarge,
                    color = YummyPalette.Text,
                    modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp),
                )
            }
            items(current.value) { day ->
                Text(
                    text = formatDay(day),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = YummyPalette.Text,
                    modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 6.dp),
                )
                SmoothRow(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp)) {
                    items(day.items, key = { it.card.id }) { item ->
                        PosterCard(
                            card = item.card,
                            onClick = { onOpen(item.card.id) },
                            width = 168.dp,
                            posterHeight = 236.dp,
                        )
                    }
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

private fun formatDay(day: ScheduleDay): String {
    if (day.timestampSec <= 0L) return "Без даты"
    val date = Instant.ofEpochSecond(day.timestampSec).atZone(ZoneId.systemDefault())
    return ruDayLabel(date.toLocalDate())
}

private fun formatNextEpisode(item: ScheduleItem): String {
    val timestamp = item.nextEpisodeAtSec ?: return ""
    if (timestamp <= 0L) return ""
    val time = Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault())
    return "след. серия в ${TIME_FORMAT.format(time)}"
}
