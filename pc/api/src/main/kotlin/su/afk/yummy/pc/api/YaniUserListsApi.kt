// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Запись пользовательского списка (Смотрю / В планах / Просмотрено / Брошено / Отложено / Любимое). */
data class UserListEntry(
    val animeId: Int,
    val title: String,
    val posterUrl: String?,
    val rating: Double?,
    val year: Int?,
    val typeName: String?,
    val listId: Int?,
    val listHref: String?,
    val listTitle: String?,
    val isFavorite: Boolean,
    val userRating: Double,
    val genres: List<String> = emptyList(),
) {
    val statusKey: String
        get() = if (isFavorite) "fav" else listHref ?: "unknown"
}

/** Счётчики профиля, как в шапке сайта. */
data class ProfileStats(
    val lists: Int = 0,
    val friends: Int = 0,
    val reviews: Int = 0,
    val collections: Int = 0,
    val posts: Int = 0,
)

/**
 * Пользовательские списки и счётчики профиля.
 *
 * yani отдаёт все списки одним ответом `/users/{id}/lists` (216 записей, ~340 КБ),
 * поэтому фильтрация по статусам и поиск делаются локально — без сети на каждую вкладку.
 */
class YaniUserListsApi(private val client: HttpClient = YaniHttp.client) {

    suspend fun lists(userId: Int): List<UserListEntry> {
        val dto = client.get("${YaniHttp.baseUrl()}/users/$userId/lists").body<UserListsDto>()
        return dto.response.mapNotNull { item ->
            val id = item.animeId ?: return@mapNotNull null
            UserListEntry(
                animeId = id,
                title = item.title,
                posterUrl = (item.poster?.big ?: item.poster?.medium ?: item.poster?.small).absUrl(),
                rating = item.rating,
                year = item.year,
                typeName = item.type?.name,
                listId = item.user?.list?.list?.id,
                listHref = item.user?.list?.list?.href,
                listTitle = item.user?.list?.list?.title,
                isFavorite = item.user?.list?.isFav == true,
                userRating = item.user?.rating ?: 0.0,
                genres = item.genres.mapNotNull { it.title?.takeIf(String::isNotBlank) },
            )
        }
    }

    suspend fun stats(userId: Int): ProfileStats = ProfileStats(
        lists = countOrZero { client.get("${YaniHttp.baseUrl()}/users/$userId/lists").body<UserListsDto>().response.size },
        friends = countOrZero { client.get("${YaniHttp.baseUrl()}/users/$userId/friends").body<CountListDto>().response.size },
        reviews = countOrZero { client.get("${YaniHttp.baseUrl()}/users/$userId/reviews").body<CountListDto>().response.size },
        collections = countOrZero { client.get("${YaniHttp.baseUrl()}/users/$userId/collections").body<CountListDto>().response.size },
        posts = countOrZero { client.get("${YaniHttp.baseUrl()}/users/$userId/posts").body<CountListDto>().response.size },
    )

    private inline fun countOrZero(block: () -> Int): Int = runCatching(block).getOrDefault(0)
}

/** Список статусов сайта в порядке вкладок. */
object UserListStatuses {
    data class Status(val href: String, val title: String)

    val ALL = listOf(
        Status("watch_now", "Смотрю"),
        Status("will", "В планах"),
        Status("watched", "Просмотрено"),
        Status("lost", "Брошено"),
        Status("postponed", "Отложено"),
        Status("fav", "Любимое"),
    )
}

@Serializable
internal data class UserListsDto(val response: List<UserListAnimeDto> = emptyList())

@Serializable
internal data class CountListDto(val response: List<kotlinx.serialization.json.JsonElement> = emptyList())

@Serializable
internal data class UserListAnimeDto(
    @SerialName("anime_id") val animeId: Int? = null,
    val title: String = "",
    val poster: UserListPosterDto? = null,
    val rating: Double? = null,
    val year: Int? = null,
    val type: UserListTypeDto? = null,
    val user: UserListUserDto? = null,
    val genres: List<UserListGenreDto> = emptyList(),
)

@Serializable
internal data class UserListGenreDto(val title: String? = null)

@Serializable
internal data class UserListPosterDto(
    val small: String? = null,
    val medium: String? = null,
    val big: String? = null,
    val fullsize: String? = null,
    val mega: String? = null,
)

@Serializable
internal data class UserListTypeDto(val name: String? = null, val value: Int? = null)

@Serializable
internal data class UserListUserDto(
    val list: UserListRefDto? = null,
    val rating: Double? = null,
)

@Serializable
internal data class UserListRefDto(
    @SerialName("is_fav") val isFav: Boolean = false,
    val list: UserListInfoDto? = null,
)

@Serializable
internal data class UserListInfoDto(
    val id: Int? = null,
    val href: String? = null,
    val title: String? = null,
)
