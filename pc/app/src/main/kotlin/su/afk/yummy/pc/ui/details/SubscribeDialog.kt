// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import su.afk.yummy.pc.api.AnimePage
import su.afk.yummy.pc.api.VideoRef
import su.afk.yummy.pc.api.YaniAnimeExtrasApi
import su.afk.yummy.pc.api.YummyConfig
import su.afk.yummy.pc.ui.theme.YummyPalette

/**
 * Одна опция подписки: пара «озвучка + балансер» у тайтла.
 *
 * Подписка на yani живёт не у отдельной серии, а у группы видео этой озвучки/плеера, поэтому
 * в диалоге показываем группы, а на сервер отправляем id любой серии из группы (тот, что уже
 * помечен `subscribed`, если он есть).
 *
 * @property key стабильный ключ группы для состояния «запрос в полёте» и сравнения видео.
 * @property dubbing название озвучки, как его отдал yani.
 * @property player название плеера/балансера; пустое — сервер не прислал.
 * @property videoId id серии, по которой сервер поймёт, какую озвучку подписывать.
 * @property episodesCount сколько серий этой озвучки доступно.
 * @property subscribed текущее состояние подписки (из `subscribed` в `/anime/{id}/videos`).
 * @property views суммарные просмотры группы — по ним сортируем список, как в ТВ-клиенте.
 */
private data class SubscriptionOption(
    val key: String,
    val dubbing: String,
    val player: String,
    val videoId: Int,
    val episodesCount: Int,
    val subscribed: Boolean,
    val views: Int,
)

/**
 * Диалог подписки на новые серии озвучки — колокольчик рядом с названием тайтла.
 *
 * Список строим из `page.videos`: yani отдаёт одну запись на «серия × озвучка × плеер»,
 * поэтому для подписки группируем их по паре «озвучка + плеер» (ключ [videoKey]) и
 * показываем одной кнопкой «Подписаться/Отписаться». Состояние берём из поля `subscribed`
 * видео — оно приходит только авторизованному пользователю; гостю показываем подсказку,
 * что подписка доступна после входа.
 *
 * После успешного запроса обновляем переданный список видео через [onVideosChange], чтобы
 * иконка колокольчика на странице тайтла и повторное открытие диалога показывали новое
 * состояние. Неудачная попытка показывает причину текстом, состояние подписки не меняется.
 *
 * @param page страница тайтла: нужны название и список видео.
 * @param videos текущее состояние видео (обновляется после каждой подписки/отписки).
 * @param onVideosChange колбэк с новым списком видео после успешной мутации.
 * @param onDismiss закрыть диалог (кнопка «Закрыть» или клик мимо).
 */
@Composable
internal fun SubscribeDialog(
    page: AnimePage,
    videos: List<VideoRef>,
    onVideosChange: (List<VideoRef>) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val api = remember { YaniAnimeExtrasApi() }
    val authorized = YummyConfig.isAuthorized

    // Ключ опции, по которой сейчас идёт запрос: кнопку «Подписаться/Отписаться» блокируем,
    // чтобы повторный клик не отправил второй PUT/DELETE поверх первого.
    var pendingKey by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    val options = remember(videos) { videos.toSubscriptionOptions() }
    val groups = remember(options) { options.groupBy { it.dubbing } }

    fun toggle(option: SubscriptionOption) {
        if (pendingKey != null) return
        val target = !option.subscribed
        pendingKey = option.key
        failed = false
        message = if (target) "Подписываемся…" else "Отписываемся…"
        scope.launch {
            try {
                api.setSubscribed(option.videoId, target)
                onVideosChange(
                    videos.map { video ->
                        if (videoKey(video) == option.key) video.copy(subscribed = target) else video
                    },
                )
                message = if (target) {
                    "Подписка оформлена: ${option.dubbing}"
                } else {
                    "Подписка отменена: ${option.dubbing}"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed = true
                message = mutationErrorText(target, error)
            } finally {
                pendingKey = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = null,
                    tint = YummyPalette.Accent,
                    modifier = Modifier.width(22.dp).height(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Подписки на озвучку",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = YummyPalette.Text,
                    )
                    Text(
                        page.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = YummyPalette.TextDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!authorized) {
                    Text(
                        "Войдите в аккаунт, чтобы подписаться на новые серии. Подписка приходит " +
                            "вместе с ответом сервера только авторизованному пользователю.",
                        style = MaterialTheme.typography.bodySmall,
                        color = YummyPalette.Accent,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                when {
                    options.isEmpty() -> Text(
                        "Озвучки не найдены",
                        style = MaterialTheme.typography.bodyMedium,
                        color = YummyPalette.TextDim,
                    )

                    else -> groups.forEach { (dubbing, options) ->
                        Text(
                            dubbing,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = YummyPalette.Text,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                        options.forEach { option ->
                            SubscriptionOptionRow(
                                option = option,
                                pending = pendingKey != null,
                                enabled = authorized,
                                onToggle = { toggle(option) },
                            )
                        }
                    }
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) YummyPalette.Accent else YummyPalette.Success,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть", style = MaterialTheme.typography.labelLarge, color = YummyPalette.Accent)
            }
        },
        containerColor = YummyPalette.BackgroundElevated,
        titleContentColor = YummyPalette.Text,
        textContentColor = YummyPalette.Text,
    )
}

/** Строка опции: озвучка-группа уже выведена заголовком, здесь плеер, число серий и кнопка. */
@Composable
private fun SubscriptionOptionRow(
    option: SubscriptionOption,
    pending: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (option.subscribed) YummyPalette.AccentSoft else YummyPalette.Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                option.player.ifBlank { "Источник" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = YummyPalette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (option.episodesCount > 1) "Серий: ${option.episodesCount}" else "1 серия",
                style = MaterialTheme.typography.labelSmall,
                color = YummyPalette.TextDim,
                maxLines = 1,
            )
        }
        OutlinedButton(
            onClick = onToggle,
            enabled = enabled && !pending,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                if (option.subscribed) "Отписаться" else "Подписаться",
                style = MaterialTheme.typography.labelMedium,
                color = if (option.subscribed) YummyPalette.Accent else YummyPalette.Text,
            )
        }
    }
}

/** Озвучки и плееры, по одной записи на пару: так yani хранит подписку (серия не важна). */
private fun List<VideoRef>.toSubscriptionOptions(): List<SubscriptionOption> =
    filter { it.videoId > 0 }
        .groupBy(::videoKey)
        .map { (key, group) ->
            val first = group.first()
            SubscriptionOption(
                key = key,
                dubbing = first.dubbing.trim().ifBlank { "Без озвучки" },
                player = first.player.trim(),
                // Отписываться надёжнее серией, которую сервер сам пометил подписанной.
                videoId = group.firstOrNull { it.subscribed }?.videoId ?: group.first().videoId,
                episodesCount = group.map { it.episode }.distinct().size,
                subscribed = group.any { it.subscribed },
                views = group.sumOf { it.views ?: 0 },
            )
        }
        // Сначала популярные озвучки: у них больше просмотров, как в списке на сайте.
        .sortedWith(compareByDescending<SubscriptionOption> { it.views }.thenBy { it.dubbing }.thenBy { it.player })

/** Ключ группы подписки: одна озвучка на одном балансере (регистр и пробелы не важны). */
private fun videoKey(video: VideoRef): String =
    "${video.dubbing.trim().lowercase()}|${video.player.trim().lowercase()}"

/** Текст ошибки подписки: короткая причина вместо многострочного текста исключения Ktor. */
private fun mutationErrorText(subscribe: Boolean, error: Throwable): String {
    val action = if (subscribe) "подписаться" else "отписаться"
    val raw = error.message.orEmpty()
    val reason = when {
        raw.contains("401") -> "нужен вход в аккаунт"
        raw.contains("403") -> "нет доступа к этой озвучке"
        raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) ->
            "сервер не ответил, попробуйте ещё раз"
        raw.isBlank() -> "ошибка сети"
        else -> raw.substringBefore(". Text:").substringBefore(" Text:").trim().take(160)
    }
    return "Не удалось $action: $reason"
}
