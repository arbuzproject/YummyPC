// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.afk.yummy.pc.api.AnimeCard
import su.afk.yummy.pc.api.UserListEntry
import su.afk.yummy.pc.api.UserListStatuses
import su.afk.yummy.pc.ui.components.PosterCard
import su.afk.yummy.pc.ui.theme.YummyPalette

/** Ширина карточки и высота постера — как в сетках топа и поиска, чтобы ритм совпадал. */
private val CardWidth: Dp = 210.dp
private val PosterHeight: Dp = 296.dp

/** Насколько чип статуса поднят от верха карточки: у нижнего края постера. */
private val StatusChipTopOffset: Dp = PosterHeight - 40.dp

/** Красная точка для «Брошено» — статус видно даже без чтения подписи. */

/**
 * Карточка тайтла из «Моих списков».
 *
 * Основа — общий [PosterCard] (постер, название, год и тип), поэтому карточки списков выглядят
 * так же, как в топе и поиске. Сверху слева — номер по порядку в текущей вкладке, сверху справа —
 * личная оценка пользователя (если он её ставил), у нижнего края постера — чип статуса списка.
 * Клик открывает страницу тайтла.
 */
@Composable
fun UserListCard(entry: UserListEntry, position: Int, onClick: () -> Unit) {
    val card = remember(entry, position) {
        AnimeCard(
            id = entry.animeId,
            title = entry.title,
            posterUrl = entry.posterUrl,
            // В бейдже показываем именно оценку пользователя, а не общий рейтинг сайта.
            rating = entry.userRating.takeIf { it > 0 },
            year = entry.year,
            typeName = entry.typeName,
            rank = position,
        )
    }
    Box(Modifier.width(CardWidth)) {
        PosterCard(card = card, onClick = onClick, width = CardWidth, posterHeight = PosterHeight)
        ListStatusChip(
            statusKey = entry.statusKey,
            modifier = Modifier.align(Alignment.TopStart).offset(x = 8.dp, y = StatusChipTopOffset),
        )
    }
}

/** Плашка статуса списка поверх постера: цветная точка + название списка. */
@Composable
private fun ListStatusChip(statusKey: String, modifier: Modifier = Modifier) {
    val label = UserListStatuses.ALL.firstOrNull { it.href == statusKey }?.title ?: "В списке"
    val dotColor = when (statusKey) {
        "watch_now", "watching" -> Color(0xFFF4665C)
        "will" -> Color(0xFF8E7BE8)
        "watched" -> Color(0xFF35C77B)
        "lost", "dropped" -> Color(0xFF7C8A99)
        "postponed" -> Color(0xFFF2C744)
        "fav" -> Color(0xFFD65CE0)
        else -> YummyPalette.TextFaint
    }
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xCC101014))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (statusKey == "fav") {
            Text("★", color = YummyPalette.RatingStar, fontSize = 10.sp)
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
        }
        Text(label, color = YummyPalette.Text, style = MaterialTheme.typography.labelMedium)
    }
}
