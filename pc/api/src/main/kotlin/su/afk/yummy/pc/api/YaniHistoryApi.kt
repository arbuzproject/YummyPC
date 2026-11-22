// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Одна запись истории просмотров yani: серия, которую пользователь смотрел.
 *
 * @property animeId id тайтла на yani — по нему открывается страница тайтла.
 * @property title название тайтла, как его отдал сервер.
 * @property episode номер или название серии строкой (`2`, `OVA 1`); пустая строка — сервер не прислал.
 * @property posterUrl абсолютная ссылка на постер (сервер отдаёт протокол-относительную ссылку).
 * @property screenshotUrl кадр из серии, если сервер его сохранил; иначе null.
 * @property watchedAtSec unix-время просмотра в секундах; null — дата неизвестна.
 * @property positionSec позиция остановки в серии, секунды от начала.
 * @property durationSec длительность серии в секундах; 0 — длительность неизвестна.
 */
data class WatchHistoryItem(
    val animeId: Int,
    val title: String,
    val episode: String,
    val posterUrl: String?,
    val screenshotUrl: String?,
    val watchedAtSec: Long?,
    val positionSec: Int,
    val durationSec: Int,
) {
    /** Доля просмотра 0..1; 0 — прогресс неизвестен (длительности нет). */
    val progress: Float
        get() = if (durationSec > 0) (positionSec.toFloat() / durationSec).coerceIn(0f, 1f) else 0f

    /** Серия досмотрена почти до конца — удобно для подписи в интерфейсе. */
    val isFinished: Boolean
        get() = durationSec > 0 && progress >= FINISHED_PROGRESS

    private companion object {
        const val FINISHED_PROGRESS = 0.95f
    }
}

/**
 * История просмотров yani: `GET /video/watch-history` (нужен вход в аккаунт).
 *
 * Ответ сервера — `{response: [...]}` с записями об уже просмотренных сериях: `anime_id`,
 * `title`, `poster {small, medium, big}`, `ep_title`, `end_time`, `duration`, `date` и
 * необязательный `screenshot`. Заголовок `Authorization: Bearer` подставляет [YaniHttp.client],
 * поэтому вызывающему коду ничего передавать не нужно.
 *
 * Сервер принимает `limit` не больше 100: на 101 и больше отвечает 400. Поэтому [history]
 * прозрачно листает историю страницами по `offset`, пока не наберёт запрошенное количество
 * или пока история не закончится. Ошибки сети и не-2xx приходят исключениями (в Ktor включён
 * `expectSuccess`), экран сам решает, что показать.
 */
class YaniHistoryApi(private val client: HttpClient = YaniHttp.client) {

    /**
     * История просмотров, начиная с самой свежей записи.
     *
     * @param limit сколько записей вернуть (по умолчанию 200 — примерно две страницы сервера).
     * @return записи, у которых есть положительный `anime_id`; записи без него пропускаются.
     */
    suspend fun history(limit: Int = 200): List<WatchHistoryItem> {
        val wanted = limit.coerceAtLeast(1)
        val collected = ArrayList<WatchHistoryItem>(wanted.coerceAtMost(MAX_PAGE_SIZE))
        var offset = 0
        var pages = 0
        while (collected.size < wanted && pages < MAX_PAGES) {
            val pageLimit = minOf(MAX_PAGE_SIZE, wanted - collected.size)
            val page = client.get("${YaniHttp.baseUrl()}/video/watch-history") {
                parameter("limit", pageLimit)
                parameter("offset", offset)
            }.body<YaniWatchHistoryResponseDto>().response
            collected += page.mapNotNull { it.toItemOrNull() }
            offset += page.size
            pages++
            // Короткая страница означает, что история закончилась.
            if (page.size < pageLimit) break
        }
        return collected
    }

    private companion object {
        /** Больше 100 yani не принимает и отвечает 400. */
        const val MAX_PAGE_SIZE = 100

        /** Предохранитель от бесконечного листания, если сервер проигнорирует offset. */
        const val MAX_PAGES = 10
    }
}

/** Ответ истории: массив записей в обёртке `response`. */
@Serializable
internal data class YaniWatchHistoryResponseDto(
    val response: List<YaniWatchHistoryDto> = emptyList(),
)

/**
 * Сырая запись истории.
 *
 * Поля намеренно описаны через [JsonElement]: yani переставляет размеры постера (`small`/`medium`/
 * `big`/`mega`/`fullsize`/`huge`), иногда отдаёт `poster` строкой, а `date` — числом или строкой.
 * Такой разбор не падает на неожиданном типе: непонятное значение просто пропускается.
 */
@Serializable
internal data class YaniWatchHistoryDto(
    @SerialName("anime_id") val animeId: JsonElement? = null,
    val title: String? = null,
    @SerialName("anime_url") val animeUrl: String? = null,
    /** Номер серии: основной вариант yani — `ep_title`. */
    @SerialName("ep_title") val episodeTitle: JsonElement? = null,
    /** Запасной вариант поля с номером серии, если сервер назовёт его `episode`. */
    val episode: JsonElement? = null,
    /** Постер: объект с размерами либо готовая строка-ссылка. */
    val poster: JsonElement? = null,
    /** Готовая ссылка на постер, если сервер решит отдать её отдельным полем. */
    @SerialName("poster_url") val posterUrl: JsonElement? = null,
    /** Кадр из серии: `{time, id, episode, sizes {small, full}}`. */
    val screenshot: JsonElement? = null,
    /** Unix-время просмотра: секунды, на всякий случай поддерживаются и миллисекунды. */
    val date: JsonElement? = null,
    /** Позиция остановки в серии, секунды от начала. */
    @SerialName("end_time") val endTime: JsonElement? = null,
    /** Запасное поле позиции. */
    val time: JsonElement? = null,
    /** Готовый прогресс, если сервер отдаст его вместо позиции: доля 0..1 или проценты. */
    val progress: JsonElement? = null,
    /** Длительность серии в секундах. */
    val duration: JsonElement? = null,
)

/** Превращает сырую запись в модель экрана; записи без валидного `anime_id` пропускаются. */
internal fun YaniWatchHistoryDto.toItemOrNull(): WatchHistoryItem? {
    val id = animeId.intValue() ?: return null
    if (id <= 0) return null
    val durationSec = (duration.intValue() ?: 0).coerceAtLeast(0)
    return WatchHistoryItem(
        animeId = id,
        title = title?.takeIf { it.isNotBlank() }
            ?: animeUrl?.takeIf { it.isNotBlank() }
            ?: "Без названия",
        episode = episodeLabel(),
        posterUrl = posterBestUrl(),
        screenshotUrl = screenshotBestUrl(),
        watchedAtSec = date.unixSeconds(),
        positionSec = positionSeconds(durationSec),
        durationSec = durationSec,
    )
}

/** Номер серии: сначала `ep_title`, затем `episode`, в последнюю очередь — кадр из серии. */
private fun YaniWatchHistoryDto.episodeLabel(): String {
    episodeTitle.textValue()?.let { return it }
    episode.textValue()?.let { return it }
    val screenshotObject = screenshot as? JsonObject ?: return ""
    return screenshotObject["episode"].textValue().orEmpty()
}

/** Размеры постера, которые yani умеет присылать; берём самый крупный из доступных. */
private val POSTER_SIZE_KEYS = listOf("big", "huge", "mega", "fullsize", "medium", "small")

/** Лучшая ссылка на постер: объект с размерами, строка-ссылка или отдельное поле `poster_url`. */
private fun YaniWatchHistoryDto.posterBestUrl(): String? {
    val element = poster
    if (element is JsonObject) {
        for (key in POSTER_SIZE_KEYS) {
            element[key].textValue()?.let { return it.absUrl() }
        }
    }
    return element.textValue()?.absUrl() ?: posterUrl.textValue()?.absUrl()
}

/** Кадр из серии: сначала полноразмерный, затем маленький. */
private fun YaniWatchHistoryDto.screenshotBestUrl(): String? {
    val sizes = (screenshot as? JsonObject)?.get("sizes") as? JsonObject ?: return null
    return (sizes["full"] ?: sizes["small"]).textValue()?.absUrl()
}

/** Позиция остановки: `end_time`, затем `time`, затем расчёт из готового прогресса. */
private fun YaniWatchHistoryDto.positionSeconds(durationSec: Int): Int {
    val direct = endTime.intValue() ?: time.intValue()
    if (direct != null && direct > 0) return direct
    val ratio = progress.doubleValue() ?: return 0
    if (durationSec <= 0) return 0
    // Сервер может прислать долю (0..1) или проценты (0..100).
    val fraction = if (ratio > 1.0) ratio / 100.0 else ratio
    return (fraction * durationSec).toInt().coerceIn(0, durationSec)
}

/** Unix-время в секундах; значения больше 1e11 считаем миллисекундами. */
private fun JsonElement?.unixSeconds(): Long? {
    val raw = this.longValue() ?: return null
    val seconds = if (raw > MILLIS_THRESHOLD) raw / 1000 else raw
    return seconds.takeIf { it > 0 }
}

/** Порог между unix-секундами и миллисекундами: секунды такого размера наступят не скоро. */
private const val MILLIS_THRESHOLD = 100_000_000_000L

/** Строковое значение примитива: работает и для строки, и для числа в JSON. */
private fun JsonElement?.textValue(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonElement?.intValue(): Int? = (this as? JsonPrimitive)?.intOrNull

private fun JsonElement?.longValue(): Long? = (this as? JsonPrimitive)?.longOrNull

private fun JsonElement?.doubleValue(): Double? = (this as? JsonPrimitive)?.doubleOrNull
