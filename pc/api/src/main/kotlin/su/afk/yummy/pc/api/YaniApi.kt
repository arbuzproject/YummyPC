package su.afk.yummy.pc.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import su.afk.yummy.tv.data.catalog.dto.YaniSearchGenresDto
import su.afk.yummy.tv.data.catalog.dto.YaniSearchGenresResponseDto
import su.afk.yummy.tv.data.catalog.dto.YaniSearchItemDto
import su.afk.yummy.tv.data.catalog.dto.YaniSearchResponseDto
import su.afk.yummy.tv.data.details.dto.YaniAnimeDetailsDto
import su.afk.yummy.tv.data.details.dto.YaniAnimeResponseDto
import su.afk.yummy.tv.data.details.dto.YaniAnimeVideoDto
import su.afk.yummy.tv.data.details.dto.YaniAnimeVideosDto
import su.afk.yummy.tv.data.home.dto.YaniFeedDto
import su.afk.yummy.tv.data.home.dto.YaniFeedResponseDto
import su.afk.yummy.tv.data.home.dto.YaniLastWatchesDto

class YaniApi(private val client: HttpClient = YaniHttp.client) {

    suspend fun feed(): YaniFeedResponseDto =
        client.get("${YaniHttp.baseUrl()}/feed").body<YaniFeedDto>().response

    suspend fun lastWatches(): YaniLastWatchesDto =
        client.get("${YaniHttp.baseUrl()}/video/last-watches").body()

    suspend fun search(
        query: String = "",
        genres: List<Int> = emptyList(),
        types: List<Int> = emptyList(),
        statuses: List<String> = emptyList(),
        seasons: List<String> = emptyList(),
        ageRatings: List<Int> = emptyList(),
        fromYear: Int? = null,
        toYear: Int? = null,
        sort: String = "rating",
        sortForward: Boolean = false,
        limit: Int = 30,
        offset: Int = 0,
    ): List<YaniSearchItemDto> =
        client.get("${YaniHttp.baseUrl()}/anime") {
            if (query.isNotBlank()) parameter("q", query)
            genres.forEach { parameter("genres", it) }
            types.forEach { parameter("types", it) }
            statuses.forEach { parameter("status", it) }
            seasons.forEach { parameter("season", it) }
            ageRatings.forEach { parameter("min_age", it) }
            fromYear?.let { parameter("from_year", it) }
            toYear?.let { parameter("to_year", it) }
            parameter("sort", sort)
            parameter("sort_forward", sortForward)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body<YaniSearchResponseDto>().response

    /** Статус списка: 0 — смотрю, 1 — в планах, 2 — просмотрено, 3 — брошено, 5 — отложено. */
    suspend fun setListStatus(animeId: Int, listId: Int) {
        client.put("${YaniHttp.baseUrl()}/anime/$animeId/list") {
            contentType(ContentType.Application.Json)
            setBody(SetListRequest(list = listId, date = System.currentTimeMillis() / 1000))
        }
    }

    /** Добавить/убрать «Любимое». */
    suspend fun setFavorite(animeId: Int, favorite: Boolean) {
        val url = "${YaniHttp.baseUrl()}/anime/$animeId/list/fav"
        if (favorite) {
            client.put(url) { contentType(ContentType.Application.Json); setBody(SetFavRequest(System.currentTimeMillis() / 1000)) }
        } else {
            client.delete(url)
        }
    }

    /** Убрать тайтл из списков. */
    suspend fun removeFromList(animeId: Int) {
        client.delete("${YaniHttp.baseUrl()}/anime/$animeId/list")
    }
    suspend fun details(animeId: Int): YaniAnimeResponseDto =
        client.get("${YaniHttp.baseUrl()}/anime/$animeId").body<YaniAnimeDetailsDto>().response

    suspend fun videos(animeId: Int): List<YaniAnimeVideoDto> =
        client.get("${YaniHttp.baseUrl()}/anime/$animeId/videos").body<YaniAnimeVideosDto>().response

    suspend fun genres(): YaniSearchGenresDto =
        client.get("${YaniHttp.baseUrl()}/anime/genres").body<YaniSearchGenresResponseDto>().response

    suspend fun catalog(): YaniCatalogDto =
        client.get("${YaniHttp.baseUrl()}/anime/catalog").body<YaniCatalogEnvelopeDto>().response

    suspend fun schedule(): List<YaniScheduleItemDto> =
        client.get("${YaniHttp.baseUrl()}/anime/schedule").body<YaniScheduleEnvelopeDto>().response
}

@Serializable
data class YaniCatalogEnvelopeDto(val response: YaniCatalogDto = YaniCatalogDto())

@Serializable
data class YaniCatalogDto(
    val types: List<YaniTypeCountDto> = emptyList(),
    val genres: YaniSearchGenresDto = YaniSearchGenresDto(),
)

@Serializable
data class YaniTypeCountDto(
    val count: Int = 0,
    val type: YaniTypeDto = YaniTypeDto(),
)

@Serializable
data class YaniTypeDto(
    val name: String = "",
    val value: Int = 0,
    val shortname: String? = null,
    val alias: String? = null,
)

@Serializable
data class YaniScheduleEnvelopeDto(val response: List<YaniScheduleItemDto> = emptyList())

@Serializable
data class YaniScheduleItemDto(
    @SerialName("anime_id") val animeId: Int = 0,
    @SerialName("anime_url") val animeUrl: String = "",
    val title: String = "",
    val description: String = "",
    val poster: YaniSchedulePosterDto? = null,
    val episodes: YaniScheduleEpisodesDto? = null,
)

@Serializable
data class YaniSchedulePosterDto(
    val small: String? = null,
    val medium: String? = null,
    val big: String? = null,
    val fullsize: String? = null,
    val mega: String? = null,
)

@Serializable
data class YaniScheduleEpisodesDto(
    val count: Int? = null,
    val aired: Int? = null,
    @SerialName("next_date") val nextDate: Long? = null,
    @SerialName("prev_date") val prevDate: Long? = null,
)
@Serializable
data class SetListRequest(val list: Int, val date: Long)

@Serializable
data class SetFavRequest(val date: Long)