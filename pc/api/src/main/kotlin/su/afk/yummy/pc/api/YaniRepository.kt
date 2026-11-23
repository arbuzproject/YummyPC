// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.api

import su.afk.yummy.tv.data.catalog.dto.YaniSearchItemDto
import su.afk.yummy.tv.data.details.dto.YaniAnimePosterDto
import su.afk.yummy.tv.data.details.dto.YaniAnimeResponseDto
import su.afk.yummy.tv.data.details.dto.YaniAnimeVideoDto
import su.afk.yummy.tv.data.home.dto.YaniAnimeDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId

/**
 * Репозиторий для UI: превращает DTO yani в простые модели десктопа.
 *
 * Ответы кэшируются в памяти на 10 минут: разделы переключаются моментально,
 * а данные обновляются без ручного перезапуска.
 */
class YaniRepository(
    private val api: YaniApi = YaniApi(),
    private val cacheTtlMs: Long = 10 * 60 * 1000L,
) {

    private class Entry(val at: Long, val value: Any)

    private val cache = HashMap<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, force: Boolean = false, loader: suspend () -> T): T {
        val now = System.currentTimeMillis()
        if (!force) {
            cache[key]?.let { if (now - it.at < cacheTtlMs) return it.value as T }
        }
        val value = loader()
        cache[key] = Entry(now, value)
        return value
    }

    /** Сбрасывает кэш разделов (например, после смены настроек). */
    fun invalidateCache() = cache.clear()

    suspend fun home(force: Boolean = false): HomeData = cached("home", force) {
        val feed = api.feed()
        val hero = feed.topCarousel.items.mapNotNull { card ->
            val id = card.animeId ?: return@mapNotNull null
            HeroSlide(
                id = id,
                title = card.title,
                description = card.description.stripHtml(),
                posterUrl = card.poster?.big.absUrl() ?: card.poster?.medium.absUrl(),
                backdropUrl = card.poster?.mega.absUrl() ?: card.poster?.big.absUrl(),
                rating = card.rating?.average,
                year = card.year,
                typeName = null,
                episodesCount = null,
            )
        }
        val rows = listOf(
            "Новинки" to feed.new,
            "Рекомендуем" to feed.recommends,
            "Анонсы" to feed.announcements,
        ).mapNotNull { (title, cards) ->
            val items = cards.mapNotNull { it.toCard() }
            if (items.isEmpty()) null else FeedRow(title = title, items = items)
        }
        HomeData(hero = hero, rows = rows)
    }

    suspend fun search(query: String): List<AnimeCard> = cached("search:$query") {
        api.search(query = query).mapNotNull { it.toCard() }
    }

    /** Поиск с фильтрами сайта: кэшируется по набору фильтров на 10 минут. */
    suspend fun search(
        query: String,
        filters: SearchFilters,
        limit: Int = 60,
        offset: Int = 0,
    ): List<AnimeCard> {
        val key = "search:$query:${filters.genres}:${filters.types}:${filters.statuses}:" +
            "${filters.seasons}:${filters.ageRatings}:${filters.fromYear}:${filters.toYear}:" +
            "${filters.sort}:${filters.sortForward}:$limit:$offset"
        return cached(key) {
            api.search(
                query = query,
                genres = filters.genres,
                types = filters.types,
                statuses = filters.statuses,
                seasons = filters.seasons,
                ageRatings = filters.ageRatings,
                fromYear = filters.fromYear,
                toYear = filters.toYear,
                sort = filters.sort,
                sortForward = filters.sortForward,
                limit = limit,
                offset = offset,
            ).mapNotNull { it.toCard() }
        }
    }

    suspend fun top(type: Int? = null, limit: Int = 30, offset: Int = 0): List<AnimeCard> =
        cached("top:$type:$limit:$offset") {
            api.search(
                types = listOfNotNull(type),
                // «Топ» — по рейтингу от большего к меньшему: API sort=top отдаёт
                // нерейтинговые тайтлы с нулями вперёд, а нужен именно топ по оценке.
                sort = "rating",
                sortForward = false,
                limit = limit,
                offset = offset,
            ).mapNotNull { it.toCard() }
        }

    suspend fun types(): List<AnimeType> = cached("types") {
        api.catalog().types.mapNotNull { item ->
            val value = item.type.value
            AnimeType(
                name = item.type.name,
                value = value,
                count = item.count,
            )
        }
    }

    suspend fun genres(): List<GenreOption> = cached("genres") {
        api.genres().genres.mapNotNull { genre ->
            val id = genre.value ?: return@mapNotNull null
            GenreOption(id = id, title = genre.title, groupId = genre.groupId)
        }
    }

    suspend fun schedule(force: Boolean = false): List<ScheduleDay> = cached("schedule", force) {
        val zone = ZoneId.systemDefault()
        api.schedule()
            .groupBy { item ->
                val next = item.episodes?.nextDate
                val day = if (next != null && next > 0) {
                    Instant.ofEpochSecond(next).atZone(zone).toLocalDate()
                } else {
                    Instant.now().atZone(zone).toLocalDate()
                }
                day.atStartOfDay(zone).toEpochSecond()
            }
            .toSortedMap()
            .map { (daySec, items) ->
                ScheduleDay(
                    timestampSec = daySec,
                    items = items.map { item ->
                        ScheduleItem(
                            card = AnimeCard(
                                id = item.animeId,
                                title = item.title,
                                posterUrl = item.poster?.big.absUrl() ?: item.poster?.medium.absUrl(),
                                rating = null,
                                year = null,
                            ),
                            nextEpisodeAtSec = item.episodes?.nextDate,
                            aired = item.episodes?.aired,
                            episodesCount = item.episodes?.count,
                        )
                    },
                )
            }
    }

    suspend fun page(animeId: Int, force: Boolean = false): AnimePage = cached("page:$animeId", force) {
        val details = api.details(animeId)
        val videos = runCatching { api.videos(animeId) }.getOrDefault(emptyList())
        details.toPage(videos)
    }
}

data class HomeData(
    val hero: List<HeroSlide>,
    val rows: List<FeedRow>,
)

private fun YaniAnimeDto.toCard(): AnimeCard? {
    val id = animeId ?: return null
    return AnimeCard(
        id = id,
        title = title,
        posterUrl = poster?.big.absUrl() ?: poster?.medium.absUrl() ?: poster?.small.absUrl(),
        rating = rating?.average,
        year = year,
        description = description.stripHtml(),
    )
}

private fun YaniSearchItemDto.toCard(): AnimeCard? {
    val id = animeId ?: return null
    return AnimeCard(
        id = id,
        title = title,
        posterUrl = poster?.big.absUrl() ?: poster?.medium.absUrl() ?: poster?.small.absUrl(),
        rating = rating?.average,
        year = year,
        description = description.stripHtml(),
    )
}

/** yani отдаёт ссылки без схемы (`//imgproxy.yani.tv/...`) — для JVM-клиента делаем их абсолютными. */
internal fun String?.absUrl(): String? {
    val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> "https://" + value.removePrefix("http://")
        value.startsWith("https://") -> value
        else -> "https://$value"
    }
}

private fun YaniAnimePosterDto.bestUrl(): String? =
    big.absUrl() ?: fullsize.absUrl() ?: medium.absUrl() ?: small.absUrl() ?: mega.absUrl()

private fun YaniAnimeResponseDto.toPage(videos: List<YaniAnimeVideoDto>): AnimePage {
    val refs = videos.map { it.toRef() }.filter { it.iframeUrl.isNotBlank() }
    val dubbingGroups = refs
        .groupBy { it.dubbing.ifBlank { "Без озвучки" } }
        .map { (dubbing, list) ->
            DubbingGroup(
                name = dubbing,
                players = list.map { it.player }.filter { it.isNotBlank() }.distinct(),
                videos = list,
                episodes = list.map { it.episode }.distinct().size,
            )
        }

    // Кадры из `random_screenshots`: в ряд «Скриншоты» идут все уникальные, а обложкой серии
    // становится первый кадр с её номером. Нет кадра — обложкой будет постер тайтла.
    val screenshots = randomScreenshots
        .mapNotNull { it.sizes.full.absUrl() ?: it.sizes.small.absUrl() }
        .distinct()
    val screenshotByEpisode = HashMap<String, String>(randomScreenshots.size)
    randomScreenshots.forEach { screenshot ->
        val url = screenshot.sizes.full.absUrl() ?: screenshot.sizes.small.absUrl() ?: return@forEach
        val key = normalizedEpisode(screenshot.episode)
        if (key.isNotEmpty() && key !in screenshotByEpisode) screenshotByEpisode[key] = url
    }
    val posterUrl = poster?.bestUrl()

    val episodeCards = refs
        .groupBy { it.episode }
        .map { (number, list) ->
            EpisodeCard(
                number = number,
                title = "Серия $number",
                thumbnailUrl = screenshotByEpisode[normalizedEpisode(number)] ?: posterUrl,
                durationSec = list.firstNotNullOfOrNull { it.durationSec?.takeIf { duration -> duration > 0 } },
                videos = list,
            )
        }
        .sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE }

    val order = viewingOrder.mapIndexedNotNull { index, item ->
        val id = item.animeId ?: return@mapIndexedNotNull null
        ViewingOrderItem(
            id = id,
            title = item.title,
            posterUrl = item.poster?.bestUrl(),
            year = item.year,
            rating = item.rating,
            typeName = item.type?.name,
            relation = item.data?.text?.stripHtml(),
            position = index + 1,
        )
    }

    return AnimePage(
        id = animeId ?: 0,
        title = title,
        description = description.stripHtml(),
        posterUrl = posterUrl,
        rating = rating.average,
        kinopoiskRating = rating.kinopoisk,
        shikimoriRating = rating.shikimori,
        myAnimeListRating = rating.myAnimeList,
        year = year,
        genres = genres.mapNotNull { it.title.takeIf(String::isNotBlank) },
        status = animeStatus?.title,
        typeName = type?.name,
        ageRating = minAge?.title,
        views = views?.toLong(),
        episodesCount = this.episodes?.count,
        airedCount = this.episodes?.aired,
        videos = refs,
        dubbings = dubbingGroups,
        episodes = episodeCards,
        viewingOrder = order,
        screenshots = screenshots,
        currentListHref = user?.list?.list?.href,
        isFavorite = user?.list?.isFav == true,
        commentsCount = commentsCount,
    )
}

/** Ключ серии для сопоставления `random_screenshots[].episode` и `videos[].number`: `"02"` → `"2"`. */
private fun normalizedEpisode(episode: String?): String {
    val value = episode?.trim().orEmpty()
    return value.toIntOrNull()?.toString() ?: value
}

private fun YaniAnimeVideoDto.toRef(): VideoRef = VideoRef(
    videoId = videoId,
    episode = number,
    player = data.player,
    dubbing = data.dubbing,
    iframeUrl = iframeUrl.absUrl() ?: iframeUrl,
    durationSec = duration,
    openingSkip = skips?.opening.toSkipRange(),
    endingSkip = skips?.ending.toSkipRange(),
    views = views,
    subscribed = subscribed,
)

/** Разбирает `{ "opening": {"time": 43, "length": 69} }` из yani. */
private fun kotlinx.serialization.json.JsonElement?.toSkipRange(): SkipRange? {
    val obj = this as? JsonObject ?: return null
    val start = (obj["time"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
    val length = (obj["length"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
    if (start < 0 || length <= 0) return null
    return SkipRange(startSec = start, lengthSec = length)
}
