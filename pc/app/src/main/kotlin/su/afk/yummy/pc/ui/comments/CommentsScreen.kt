// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.comments

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import su.afk.yummy.pc.api.CommentItem
import su.afk.yummy.pc.api.CommentSort
import su.afk.yummy.pc.api.YaniCommentsApi
import su.afk.yummy.pc.ui.components.ErrorView
import su.afk.yummy.pc.ui.components.LoadingView
import su.afk.yummy.pc.ui.components.PosterImage
import su.afk.yummy.pc.ui.theme.YummyPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val COMMENT_DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale("ru"))

/** Экран комментариев к тайтлу: чтение, сортировка, подгрузка страниц. */
@Composable
fun CommentsScreen(
    animeId: Int,
    animeTitle: String,
    onBack: () -> Unit,
) {
    val api = remember { YaniCommentsApi() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var sort by remember { mutableStateOf(CommentSort.NEW) }
    var items by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun loadFirst(newSort: CommentSort) {
        loading = true
        error = null
        try {
            val page = api.comments(animeId, skip = 0, sort = newSort)
            items = page.comments
            hasMore = page.hasMore
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(animeId, sort) { loadFirst(sort) }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        TextButton(onClick = onBack) { Text("← Назад") }
        Text("Комментарии", style = MaterialTheme.typography.headlineLarge, color = YummyPalette.Text)
        Text(
            animeTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = YummyPalette.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SortChip("Новые", sort == CommentSort.NEW) { sort = CommentSort.NEW }
            SortChip("Старые", sort == CommentSort.OLD) { sort = CommentSort.OLD }
        }
        Spacer(Modifier.height(14.dp))

        when {
            loading -> LoadingView("Загружаем комментарии…")
            error != null -> ErrorView(error!!) { scope.launch { loadFirst(sort) } }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Комментариев пока нет", color = YummyPalette.TextDim)
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { comment -> CommentCard(comment) }
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        when {
                            loadingMore -> CircularProgressIndicator(
                                color = YummyPalette.Accent,
                                modifier = Modifier.size(28.dp),
                            )
                            hasMore -> OutlinedButton(
                                onClick = {
                                    if (loadingMore) return@OutlinedButton
                                    loadingMore = true
                                    scope.launch {
                                        try {
                                            val page = api.comments(animeId, skip = items.size, sort = sort)
                                            items = items + page.comments
                                            hasMore = page.hasMore
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            hasMore = false
                                        } finally {
                                            loadingMore = false
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                            ) { Text("Загрузить ещё", color = YummyPalette.Text) }
                            else -> Text("Больше комментариев нет", color = YummyPalette.TextDim)
                        }
                    }
                }
            }
        }
    }

    // Автоподгрузка при прокрутке к концу.
    LaunchedEffect(listState, hasMore, loadingMore, items.size) {
        if (!hasMore || loadingMore || items.isEmpty() || loading) return@LaunchedEffect
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (last >= items.size - 3) {
            loadingMore = true
            try {
                val page = api.comments(animeId, skip = items.size, sort = sort)
                items = items + page.comments
                hasMore = page.hasMore
            } catch (_: Exception) {
                hasMore = false
            } finally {
                loadingMore = false
            }
        }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Color(0xFF1A0F07) else YummyPalette.Text,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) YummyPalette.Accent else YummyPalette.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun CommentCard(comment: CommentItem) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(YummyPalette.Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(YummyPalette.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (comment.avatarUrl != null) {
                    PosterImage(comment.avatarUrl, Modifier.fillMaxSize())
                } else {
                    Text(comment.authorName.take(1).uppercase(), color = YummyPalette.Accent)
                }
            }
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        comment.authorName.ifBlank { "Аноним" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = YummyPalette.Text,
                    )
                    if (comment.roles.any { it == "moderator" || it == "admin" }) {
                        Text("модератор", style = MaterialTheme.typography.labelSmall, color = YummyPalette.Accent)
                    }
                }
                Text(commentDate(comment.createdAtSec), style = MaterialTheme.typography.labelSmall, color = YummyPalette.TextDim)
            }
            Text(
                "👍 ${comment.likes}  👎 ${comment.dislikes}",
                style = MaterialTheme.typography.labelSmall,
                color = YummyPalette.TextDim,
            )
        }
        Text(comment.text, style = MaterialTheme.typography.bodyMedium, color = YummyPalette.Text)
        if (comment.childrenCount > 0) {
            Text(
                "Ответов: ${comment.childrenCount}",
                style = MaterialTheme.typography.labelSmall,
                color = YummyPalette.Accent,
            )
        }
    }
}

private fun commentDate(unixSec: Long): String {
    if (unixSec <= 0) return ""
    return Instant.ofEpochSecond(unixSec).atZone(ZoneId.systemDefault()).format(COMMENT_DATE)
}


