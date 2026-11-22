// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.top

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import su.afk.yummy.pc.api.AnimeCard
import su.afk.yummy.pc.api.AnimeType
import su.afk.yummy.pc.api.YaniRepository
import su.afk.yummy.pc.ui.components.ErrorView
import su.afk.yummy.pc.ui.components.LoadState
import su.afk.yummy.pc.ui.components.LoadingView
import su.afk.yummy.pc.ui.components.PosterCard
import su.afk.yummy.pc.ui.theme.YummyPalette

/** Топ тайтлов: вкладки по типам + сетка с номерами мест. */
@Composable
fun TopScreen(
    repository: YaniRepository,
    initialType: Int?,
    onOpen: (Int) -> Unit,
) {
    var selectedType by remember { mutableStateOf(initialType) }
    var items by remember { mutableStateOf<List<AnimeCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val types by produceState<List<AnimeType>>(emptyList()) {
        value = runCatching { repository.types() }.getOrDefault(emptyList())
    }

    LaunchedEffect(selectedType) {
        loading = true
        error = null
        offset = 0
        try {
            items = repository.top(type = selectedType, limit = 30, offset = 0)
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Text(
            "Топ",
            style = MaterialTheme.typography.headlineLarge,
            color = YummyPalette.Text,
            modifier = Modifier.padding(top = 20.dp, bottom = 12.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            TypeTab("Все", selectedType == null) { selectedType = null }
            types.forEach { type ->
                TypeTab(type.name, selectedType == type.value) { selectedType = type.value }
            }
        }

        when {
            loading -> LoadingView("Загружаем топ…")
            error != null -> ErrorView(error!!) { selectedType = selectedType }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 210.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(items, key = { it.id }) { card ->
                    PosterCard(card, onClick = { onOpen(card.id) }, width = 210.dp, posterHeight = 296.dp)
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        OutlinedButton(
                            onClick = {
                                if (loadingMore) return@OutlinedButton
                                loadingMore = true
                                scope.launch {
                                    val nextOffset = offset + 30
                                    runCatching { repository.top(type = selectedType, limit = 30, offset = nextOffset) }
                                        .onSuccess {
                                            items = items + it
                                            offset = nextOffset
                                        }
                                    loadingMore = false
                                }
                            },
                        ) { Text(if (loadingMore) "Загружаем…" else "Показать ещё") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) YummyPalette.Accent else YummyPalette.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (selected) androidx.compose.ui.graphics.Color(0xFF1A0F07) else YummyPalette.Text,
        )
    }
}
