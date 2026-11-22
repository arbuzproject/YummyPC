// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Запись «Продолжить просмотр» с сервера yani (`GET /video/last-watches`, нужен вход в аккаунт).
 *
 * @property animeId id тайтла — по нему открывается страница тайтла.
 * @property title название тайтла.
 * @property posterUrl абсолютная ссылка на постер (сервер отдаёт протокол-относительную ссылку).
 * @property screenshotUrl кадр из просмотренной серии, если сервер его сохранил; иначе null.
 * @property episode номер серии строкой (`12`, `OVA 1`); пустая строка — сервер не прислал.
 * @property positionSec где пользователь остановился, секунды от начала серии.
 * @property durationSec длительность серии в секундах; 0 — длительность неизвестна.
 * @property watchedAtSec unix-время просмотра в секундах; null — дата неизвестна.
 */
data class HomeContinueWatching(
    val animeId: Int,
    val title: String,
    val posterUrl: String?,
    val screenshotUrl: String?,
    val episode: String,
    val positionSec: Int,
    val durationSec: Int,
    val watchedAtSec: Long?,
) {
    /** Доля просмотра 0..1; 0 — прогресс неизвестен (длительности нет). */
    val progress: Float
        get() = if (durationSec > 0) (positionSec.toFloat() / durationSec).coerceIn(0f, 1f) else 0f
}

/**
 * Карточка новой серии для блоков главной («Новые серии» и «Новые серии из ваших списков»).
 *
 * @property animeId id тайтла.
 * @property title название тайтла.
 * @property posterUrl абсолютная ссылка на постер.
 * @property episode номер или подпись серии (`9`, `OVA 1`); пустая строка — номера нет.
 * @property dateSec unix-время выхода серии в секундах; null — дата неизвестна.
 */
data class HomeNewEpisode(
    val animeId: Int,
    val title: String,
    val posterUrl: String?,
    val episode: String,
    val dateSec: Long?,
)

/** Дополнительные блоки главной, которые не отдаёт `YaniRepository.home()` одной моделью. */
data class HomeExtras(
    val continueWatching: List<HomeContinueWatching> = emptyList(),
    val newEpisodesFromMyLists: List<HomeNewEpisode> = emptyList(),
    val newEpisodes: List<HomeNewEpisode> = emptyList(),
)

/**
 * Дополнительные блоки главной, как в ТВ-версии YummyTV.
 *
 * - «Продолжить просмотр» — `GET /video/last-watches` (только с токеном, сервер отдаёт ≤ 4 записей);
 * - «Новые серии из ваших списков» — `feed.new_videos`, отфильтрованные по id тайтлов из списка
 *   «Смотрю» (`GET /users/{id}/lists`, `user.list.list.href == "watch_now"`); если из ленты мало
 *   тайтлов, для 2–3 тайтлов из «Смотрю» параллельно догружается `GET /anime/{id}/videos`
 *   и берётся серия с самой свежей датой;
 * - «Новые серии» — весь `feed.new_videos` по всем тайтлам.
 *
 * Все ответы разбираются защитно через [JsonElement]: неизвестные поля игнорируются, `null`
 * и неожиданные типы не роняют главную. Ошибки сети и отсутствие токена возвращают пустые списки.
 * Результаты кэшируются в памяти на 5–10 минут (как в ТВ-клиенте, где TTL last-watches — 60 с,
 * а лента — 60 с; для ПК важнее не дёргать сервер при каждом переходе на главную).
 */
class YaniHomeExtrasApi(private val client: HttpClient = YaniHttp.client) {

    /**
     * «Продолжить просмотр» (только для авторизованного пользователя).
     *
     * @param force игнорировать кэш и запросить сервер заново.
     * @return до 4 карточек; пустой список без токена или при ошибке сервера.
     */
    suspend fun continueWatching(force: Boolean = false): List<HomeContinueWatching> {
        if (!YummyConfig.isAuthorized) return emptyList()
        return runCatching {
            cached(cacheKeyContinue(), CACHE_TTL_MS, force) { fetchContinueWatching() }
        }.getOrDefault(emptyList())
    }

    /**
     * «Новые серии» — все тайтлы из `feed.new_videos` (по одной карточке на тайтл).
     *
     * Публичный блок: работает и без входа в аккаунт.
     */
    suspend fun newEpisodes(force: Boolean = false): List<HomeNewEpisode> = runCatching {
        cached(CACHE_KEY_NEW_EPISODES, CACHE_TTL_MS, force) { fetchFeedNewEpisodes() }
    }.getOrDefault(emptyList())

    /**
     * «Новые серии из ваших списков» (только для авторизованного пользователя).
     *
     * Сначала пересекаем `feed.new_videos` с id тайтлов из списка «Смотрю». Если в ленте нашлось
     * меньше [MIN_NEW_EPISODES_FROM_LISTS] тайтлов, догружаем видео 2–3 тайтлов из «Смотрю»
     * (не более [MAX_LATEST_EPISODE_REQUESTS] параллельных запросов) и берём серию с самой свежей
     * датой. Недавно вышедшие серии ограничены окном [NEW_EPISODE_WINDOW_SEC].
     */
    suspend fun newEpisodesFromMyLists(force: Boolean = false): List<HomeNewEpisode> {
        if (!YummyConfig.isAuthorized || YummyConfig.userId <= 0) return emptyList()
        return runCatching {
            cached(cacheKeyMyNewEpisodes(), CACHE_TTL_MS, force) { buildMyNewEpisodes() }
        }.getOrDefault(emptyList())
    }

    /**
     * Все три блока одним вызовом: «Новые серии» грузятся первыми (общий `/feed`),
     * затем параллельно — «Продолжить просмотр» и «Новые серии из ваших списков».
     */
    suspend fun homeExtras(force: Boolean = false): HomeExtras {
        // Сначала лента: её кэш затем переиспользуют оба оставшихся блока.
        val freshEpisodes = newEpisodes(force)
        return coroutineScope {
            val continueDeferred = async { continueWatching(force) }
            val fromListsDeferred = async { newEpisodesFromMyLists(force) }
            HomeExtras(
                continueWatching = continueDeferred.await(),
                newEpisodesFromMyLists = fromListsDeferred.await(),
                newEpisodes = freshEpisodes,
            )
        }
    }

    /** Сбрасывает кэш блоков главной (например, после обновления экрана). */
    fun invalidateCache() {
        cache.clear()
    }

    // ---------------------------------------------------------------- сеть

    private suspend fun fetchContinueWatching(): List<HomeContinueWatching> {
        val raw = client.get("${YaniHttp.baseUrl()}/video/last-watches").bodyAsText()
        val items = responseArray(raw) ?: return emptyList()
        return items.mapNotNull { node ->
            val obj = node as? JsonObject ?: return@mapNotNull null
            val animeId = obj["anime_id"].intValue()?.takeIf { it > 0 } ?: return@mapNotNull null
            val durationSec = (obj["duration"].intValue() ?: 0).coerceAtLeast(0)
            val positionSec = (obj["end_time"].intValue() ?: obj["time"].intValue() ?: 0)
                .coerceAtLeast(0)
                .let { position -> if (durationSec > 0) position.coerceAtMost(durationSec) else position }
            HomeContinueWatching(
                animeId = animeId,
                title = obj["title"].textValue() ?: "Без названия",
                posterUrl = obj["poster"].posterUrl(),
                screenshotUrl = obj["screenshot"].screenshotUrl(),
                episode = normalizeEpisodeNumber(
                    obj["ep_title"].textValue()
                        ?: obj["episode"].textValue()
                        ?: obj["number"].textValue(),
                ),
                positionSec = positionSec,
                durationSec = durationSec,
                watchedAtSec = obj["date"].unixSeconds(),
            )
        }.sortedByDescending { it.watchedAtSec ?: 0L }
    }

    private suspend fun fetchFeedNewEpisodes(): List<HomeNewEpisode> {
        val raw = client.get("${YaniHttp.baseUrl()}/feed").bodyAsText()
        val root = jsonRoot(raw) ?: return emptyList()
        // /feed — единственная ручка из блока, где секции лежат внутри объекта `response`.
        val response = root["response"] as? JsonObject ?: return emptyList()
        return parseNewVideos(response["new_videos"] as? JsonArray)
    }

    /** Новые серии из ленты, отфильтрованные по id тайтлов из «Смотрю», плюс fallback по `/videos`. */
    private suspend fun buildMyNewEpisodes(): List<HomeNewEpisode> {
        val watching = watchNowTitles()
        if (watching.isEmpty()) return emptyList()
        val watchingIds = watching.mapTo(HashSet()) { it.animeId }
        val fromFeed = newEpisodes().filter { it.animeId in watchingIds }
        if (fromFeed.size >= MIN_NEW_EPISODES_FROM_LISTS) return fromFeed

        // В ленте почти нет тайтлов из «Смотрю» — догружаем их последние серии напрямую.
        val knownIds = fromFeed.mapTo(HashSet()) { it.animeId }
        val candidates = watching
            .asSequence()
            .filter { it.animeId !in knownIds }
            // Сначала тайтлы с ближайшим эфиром: у онгоингов серии выходят чаще.
            .sortedByDescending { it.nextEpisodeSec ?: 0L }
            .take(MAX_LATEST_EPISODE_REQUESTS)
            .toList()
        val latest = latestEpisodes(candidates)
        return (fromFeed + latest).sortedByDescending { it.dateSec ?: 0L }
    }

    /** id тайтлов из списка «Смотрю» вместе с постером и датой следующей серии (для выбора кандидатов). */
    private suspend fun watchNowTitles(): List<WatchNowTitle> = cached(
        cacheKeyWatchNow(),
        LISTS_CACHE_TTL_MS,
        force = false,
    ) {
        val userId = YummyConfig.userId
        if (userId <= 0) return@cached emptyList()
        val raw = client.get("${YaniHttp.baseUrl()}/users/$userId/lists").bodyAsText()
        val lists = responseArray(raw) ?: return@cached emptyList()
        val result = ArrayList<WatchNowTitle>()
        val seen = HashSet<Int>()
        for (node in lists) {
            val obj = node as? JsonObject ?: continue
            val listRef = ((obj["user"] as? JsonObject)?.get("list") as? JsonObject)
                ?.get("list") as? JsonObject ?: continue
            if (listRef["href"].textValue() != WATCH_NOW_HREF) continue
            val animeId = obj["anime_id"].intValue()?.takeIf { it > 0 } ?: continue
            if (!seen.add(animeId)) continue
            result += WatchNowTitle(
                animeId = animeId,
                title = obj["title"].textValue() ?: "Без названия",
                posterUrl = obj["poster"].posterUrl(),
                nextEpisodeSec = obj["next_episode"].unixSeconds(),
            )
        }
        result
    }

    /** Параллельно (не более [MAX_LATEST_EPISODE_REQUESTS]) берёт последнюю серию каждого тайтла. */
    private suspend fun latestEpisodes(titles: List<WatchNowTitle>): List<HomeNewEpisode> = coroutineScope {
        titles.take(MAX_LATEST_EPISODE_REQUESTS).map { title ->
            async { runCatching { fetchLatestEpisode(title) }.getOrNull() }
        }.awaitAll().filterNotNull()
    }

    /** Серия с самой свежей датой из `GET /anime/{id}/videos`; слишком старая серия не считается новой. */
    private suspend fun fetchLatestEpisode(title: WatchNowTitle): HomeNewEpisode? {
        val raw = client.get("${YaniHttp.baseUrl()}/anime/${title.animeId}/videos").bodyAsText()
        val videos = responseArray(raw) ?: return null
        var bestEpisode = ""
        var bestDateSec = 0L
        for (node in videos) {
            val obj = node as? JsonObject ?: continue
            val dateSec = obj["date"].unixSeconds() ?: continue
            val episode = normalizeEpisodeNumber(
                obj["number"].textValue() ?: obj["ep_title"].textValue(),
            )
            if (episode.isEmpty()) continue
            if (dateSec > bestDateSec) {
                bestDateSec = dateSec
                bestEpisode = episode
            }
        }
        if (bestDateSec <= 0L || bestEpisode.isEmpty()) return null
        val nowSec = System.currentTimeMillis() / 1000
        if (bestDateSec < nowSec - NEW_EPISODE_WINDOW_SEC) return null
        return HomeNewEpisode(
            animeId = title.animeId,
            title = title.title,
            posterUrl = title.posterUrl,
            episode = bestEpisode,
            dateSec = bestDateSec,
        )
    }

    // --------------------------------------------------------------- парсинг

    /**
     * Разбирает `new_videos`: одна карточка на тайтл, самая свежая серия, порядок — от новых к старым.
     */
    private fun parseNewVideos(array: JsonArray?): List<HomeNewEpisode> {
        if (array == null) return emptyList()
        val byAnimeId = LinkedHashMap<Int, HomeNewEpisode>()
        for (node in array) {
            val obj = node as? JsonObject ?: continue
            val animeId = obj["anime_id"].intValue()?.takeIf { it > 0 } ?: continue
            val episode = normalizeEpisodeNumber(
                obj["ep_title"].textValue() ?: obj["episode"].textValue(),
            )
            if (episode.isEmpty()) continue
            val card = HomeNewEpisode(
                animeId = animeId,
                title = obj["title"].textValue() ?: "Без названия",
                posterUrl = obj["poster"].posterUrl(),
                episode = episode,
                dateSec = obj["date"].unixSeconds(),
            )
            val previous = byAnimeId[animeId]
            if (previous == null || (card.dateSec ?: 0L) > (previous.dateSec ?: 0L)) {
                byAnimeId[animeId] = card
            }
        }
        return byAnimeId.values.sortedByDescending { it.dateSec ?: 0L }
    }

    /** `response` из ответа yani: массив или объект с `items` (на случай смены обёртки). */
    private fun responseArray(raw: String): JsonArray? {
        val root = jsonRoot(raw) ?: return null
        return when (val response = root["response"]) {
            is JsonArray -> response
            is JsonObject -> response["items"] as? JsonArray
            else -> null
        }
    }

    private fun jsonRoot(raw: String): JsonObject? =
        runCatching { jsonParser.parseToJsonElement(raw) }.getOrNull() as? JsonObject

    private fun JsonElement?.textValue(): String? =
        (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonElement?.intValue(): Int? = (this as? JsonPrimitive)?.intOrNull

    /** Лучшая ссылка на постер: объект с размерами (`big`/`mega`/…), реже — готовая строка. */
    private fun JsonElement?.posterUrl(): String? {
        val obj = this as? JsonObject ?: return textValue().toHttpsUrl()
        for (key in POSTER_SIZE_KEYS) {
            val url = obj[key].textValue().toHttpsUrl()
            if (url != null) return url
        }
        return null
    }

    /** Кадр серии: `screenshot {sizes {small, full}}`. */
    private fun JsonElement?.screenshotUrl(): String? {
        val sizes = (this as? JsonObject)?.get("sizes") as? JsonObject ?: return null
        return (sizes["full"] ?: sizes["small"]).textValue().toHttpsUrl()
    }

    /** Unix-время в секундах; значения больше [MILLIS_THRESHOLD] считаем миллисекундами. */
    private fun JsonElement?.unixSeconds(): Long? {
        val raw = (this as? JsonPrimitive)?.longOrNull ?: return null
        val seconds = if (raw > MILLIS_THRESHOLD) raw / 1000 else raw
        return seconds.takeIf { it > 0L }
    }

    // ---------------------------------------------------------------- кэш

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(
        key: String,
        ttlMs: Long,
        force: Boolean,
        loader: suspend () -> T,
    ): T {
        val now = System.currentTimeMillis()
        if (!force) {
            cache[key]?.let { entry ->
                if (now - entry.at < ttlMs) return entry.value as T
            }
        }
        val value = loader()
        cache[key] = CacheEntry(now, value)
        return value
    }

    private class CacheEntry(val at: Long, val value: Any)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private fun cacheKeyContinue(): String = "$CACHE_KEY_CONTINUE:${YummyConfig.userId}"

    private fun cacheKeyMyNewEpisodes(): String = "$CACHE_KEY_MY_NEW_EPISODES:${YummyConfig.userId}"

    private fun cacheKeyWatchNow(): String = "$CACHE_KEY_WATCH_NOW:${YummyConfig.userId}"
}

/** Тайтл из списка «Смотрю» — кандидат для блока «Новые серии из ваших списков». */
private data class WatchNowTitle(
    val animeId: Int,
    val title: String,
    val posterUrl: String?,
    val nextEpisodeSec: Long?,
)

/**
 * Парсер ответов yani: те же флаги, что в DTO-слое (`docs/api-contract.md`, §4.6).
 * Разбор идёт вручную через [JsonElement], поэтому незнакомые поля не ломают блок,
 * а `null`/неожиданные типы просто пропускаются.
 */
private val jsonParser = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** TTL блоков главной: 5 минут достаточно, чтобы не дёргать сервер при каждом возврате на главную. */
private const val CACHE_TTL_MS = 5 * 60 * 1000L

/** Списки пользователя меняются реже ленты — кэшируем на 10 минут. */
private const val LISTS_CACHE_TTL_MS = 10 * 60 * 1000L

private const val CACHE_KEY_CONTINUE = "home:last-watches"
private const val CACHE_KEY_NEW_EPISODES = "home:new-episodes"
private const val CACHE_KEY_MY_NEW_EPISODES = "home:my-new-episodes"
private const val CACHE_KEY_WATCH_NOW = "home:watch-now"

/** href списка «Смотрю» на сайте yani. */
private const val WATCH_NOW_HREF = "watch_now"

/** Если в ленте меньше стольких тайтлов из «Смотрю», догружаем их видео напрямую. */
private const val MIN_NEW_EPISODES_FROM_LISTS = 3

/** Больше трёх параллельных запросов `/anime/{id}/videos` не делаем: ответы тяжёлые. */
private const val MAX_LATEST_EPISODE_REQUESTS = 3

/** Серия считается новой, если вышла не раньше 30 дней назад. */
private const val NEW_EPISODE_WINDOW_SEC = 30L * 24 * 60 * 60

/** Размеры постера, которые yani умеет присылать; берём самый крупный из доступных. */
private val POSTER_SIZE_KEYS = listOf("big", "mega", "fullsize", "huge", "medium", "small")

/** Порог между unix-секундами и миллисекундами: секунды такого размера наступят не скоро. */
private const val MILLIS_THRESHOLD = 100_000_000_000L

/** Номера серий приходят строками: `"02"` → `"2"`, но `"OVA 1"` не трогаем. */
private fun normalizeEpisodeNumber(raw: String?): String {
    val value = raw?.trim().orEmpty()
    if (value.length > 1 && value.all { it.isDigit() }) {
        return value.trimStart('0').ifEmpty { "0" }
    }
    return value
}

/** yani отдаёт ссылки без схемы (`//imgproxy.yani.tv/...`) — для JVM делаем их абсолютными. */
private fun String?.toHttpsUrl(): String? {
    val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> "https://" + value.removePrefix("http://")
        value.startsWith("https://") -> value
        else -> "https://$value"
    }
}
