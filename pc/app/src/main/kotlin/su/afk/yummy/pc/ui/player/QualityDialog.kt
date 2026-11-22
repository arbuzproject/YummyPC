// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import su.afk.yummy.pc.ui.theme.YummyPalette
import su.afk.yummy.pc.ui.theme.YummyTheme

/**
 * Выбор качества потока в отдельном окне.
 *
 * mpv рисует видео в нативном дочернем окне, которое перекрывает Compose-слой основного окна,
 * поэтому обычный `AlertDialog`/`DropdownMenu` оказывался под видео. `DialogWindow` — настоящее
 * отдельное окно ОС (always-on-top), оно видно поверх mpv, затемнение основного окна отсутствует.
 *
 * @param qualities доступные качества: подпись -> URL (`PlaybackRequest.qualities`).
 * @param currentUrl URL текущего потока (запасной признак выбранного качества).
 * @param currentLabel подпись текущего качества (`PlaybackRequest.qualityLabel`).
 * @param onSelect клик по качеству: (URL, подпись). Окно закрывает вызывающий.
 * @param onDismiss закрытие без выбора.
 */
@Composable
fun QualityDialog(
    qualities: List<Pair<String, String>>,
    currentUrl: String,
    currentLabel: String,
    onSelect: (url: String, label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (qualities.isEmpty()) return

    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(width = 380.dp, height = 460.dp),
        title = "Качество",
        resizable = false,
        alwaysOnTop = true,
    ) {
        YummyTheme {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(YummyPalette.BackgroundElevated)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Качество",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = YummyPalette.Text,
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    qualities.forEach { (label, url) ->
                        val selected = isCurrentQuality(
                            label = label,
                            url = url,
                            currentLabel = currentLabel,
                            currentUrl = currentUrl,
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) YummyPalette.AccentSoft else YummyPalette.Surface)
                                .clickable { onSelect(url, label) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label.ifBlank { "auto" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) YummyPalette.Accent else YummyPalette.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Text(
                                    "сейчас",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = YummyPalette.Accent,
                                    modifier = Modifier.padding(start = 10.dp),
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Закрыть", style = MaterialTheme.typography.labelLarge, color = YummyPalette.Accent)
                }
            }
        }
    }
}

/**
 * Текущее качество: подпись точнее URL — у Kodik разные подписи могут вести на один URL.
 * Если подписи нет (например, поток пришёл из другого источника), сравниваем URL.
 */
private fun isCurrentQuality(
    label: String,
    url: String,
    currentLabel: String,
    currentUrl: String,
): Boolean = if (currentLabel.isNotBlank()) {
    label == currentLabel
} else {
    url == currentUrl
}
