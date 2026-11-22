// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.afk.yummy.pc.api.AnimeCard
import su.afk.yummy.pc.ui.PosterLoader
import su.afk.yummy.pc.ui.theme.YummyPalette

/** Состояние загрузки данных для экранов. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Data<T>(val value: T) : LoadState<T>
    data class Error(val message: String) : LoadState<Nothing>
}

/** Постер с плавным появлением и плейсхолдером, если картинки нет. */
@Composable
fun PosterImage(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { bitmap = PosterLoader.load(url) }
    Box(modifier.background(YummyPalette.SurfaceHigh), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        } else {
            Text("✦", color = YummyPalette.TextFaint, fontSize = 22.sp)
        }
    }
}

/** Рейтинг в тёмной плашке, как в ТВ-версии. */
@Composable
fun RatingBadge(value: Double?, modifier: Modifier = Modifier) {
    if (value == null) return
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xCC101014))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("★", color = YummyPalette.RatingStar, fontSize = 11.sp)
        Text(
            "%.1f".format(value),
            color = YummyPalette.Text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Номер места в топе (`#1`). */
@Composable
fun RankBadge(rank: Int?, modifier: Modifier = Modifier) {
    if (rank == null) return
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xCC101014))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text("#$rank", color = YummyPalette.Text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

/** Плашка-чип (год, тип, статус, возраст). */
@Composable
fun MetaChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(YummyPalette.SurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, color = YummyPalette.Text, style = MaterialTheme.typography.labelMedium)
    }
}

/** Карточка тайтла для рядов и сеток: постер с бейджами + подпись под ним. */
@Composable
fun PosterCard(
    card: AnimeCard,
    onClick: () -> Unit,
    width: Dp = 190.dp,
    posterHeight: Dp = 268.dp,
    showTitle: Boolean = true,
) {
    val scale = su.afk.yummy.pc.api.YummyConfig.cardScale
    Column(
        Modifier
            .width(width * scale)
            .clip(RoundedCornerShape(12.dp))
            .background(YummyPalette.Surface)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(posterHeight * scale)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            PosterImage(card.posterUrl, Modifier.fillMaxSize())
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                RankBadge(card.rank)
                Spacer(Modifier.width(4.dp))
                if (su.afk.yummy.pc.api.YummyConfig.showRatings) {
                    RatingBadge(card.rating)
                }
            }
        }
        if (showTitle) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = YummyPalette.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    card.year?.toString(),
                    card.typeName,
                    card.episodesCount?.let { "$it эп." },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = YummyPalette.TextDim)
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(YummyPalette.Accent),
            )
            Text(title, style = MaterialTheme.typography.headlineSmall, color = YummyPalette.Text)
        }
        action?.invoke()
    }
}

@Composable
fun LoadingView(text: String = "Загрузка…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = YummyPalette.Accent, modifier = Modifier.width(28.dp).height(28.dp))
            Text(text, color = YummyPalette.TextDim, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text("Не удалось загрузить данные", style = MaterialTheme.typography.titleLarge, color = YummyPalette.Text)
            Text(message, style = MaterialTheme.typography.bodySmall, color = YummyPalette.TextDim)
            androidx.compose.material3.OutlinedButton(onClick = onRetry) { Text("Повторить") }
        }
    }
}

/** Прямоугольник-плейсхолдер на время загрузки сетки. */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(YummyPalette.SurfaceHigh),
    )
}
