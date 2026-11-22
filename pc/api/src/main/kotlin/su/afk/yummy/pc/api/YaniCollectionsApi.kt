// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Подборка (коллекция) yani в модели десктопа.
 *
 * Помимо обязательных полей карточка хранит описание, просмотры, миниатюры постеров и автора:
 * списку подборок они нужны для превью и подписей, детальному виду — для шапки.
 */
data class CollectionItem(
    val id: Int,
    val title: String,
    val likes: Int,
    val animes: List<AnimeCard>,
    /** Описание подборки без HTML-тегов. */
    val description: String = "",
    /** Просмотры подборки на сайте yani. */
    val views: Int = 0,
    /** Постеры-превью подборки из списка (`poster_previews`): 2–6 миниатюр для карточки. */
    val posterPreviews: List<String> = emptyList(),
    /** Ник автора подборки. */
    val ownerName: String? = null,
) {
    /** Сколько тайтлов в подборке (для списка — только после загрузки деталей). */
    val titlesCount: Int
        get() = animes.size

    /**
     * Постеры для полосы превью карточки (до 4 миниатюр).
     *
     * yani отдаёт в списке 3 готовых превью; после фоновой догрузки состава недостающую
     * миниатюру добираем постером тайтла (без дублей), иначе превью остаются как есть.
     */
    val previewUrls: List<String>
        get() {
            if (posterPreviews.size >= PREVIEW_LIMIT) return posterPreviews.take(PREVIEW_LIMIT)
            val extra = animes.mapNotNull { it.posterUrl }.filterNot { it in posterPreviews }
            return (posterPreviews + extra).take(PREVIEW_LIMIT)
        }

    private companion object {
        /** Сколько миниатюр показываем на карточке подборки. */
        const val PREVIEW_LIMIT = 4
    }
}

/**
 * Подборки yani: `GET /collection` (список с пагинацией) и `GET /collection/{id}` (детали).
 *
 * Особенность живого API: в списке поле `animes` приходит пустым, а карточки тайтлов есть только
 * в детальном ответе. Поэтому для превью список использует `poster_previews`, а [collection]
 * догружает полный состав подборки — экран делает это фоном для каждой карточки.
 *
 * Кэша здесь нет: экран держит загруженное в своём состоянии, а сервер YummyPC кэширует
 * ответы yani самостоятельно.
 */
class YaniCollectionsApi(private val client: HttpClient = YaniHttp.client) {

    /** Список подборок: `limit` — размер страницы, `offset` — смещение. */
    suspend fun collections(limit: Int = 30, offset: Int = 0): List<CollectionItem> =
        client.get("${YaniHttp.baseUrl()}/collection") {
            parameter("limit", limit)
            parameter("offset", offset)
        }.body<CollectionsEnvelopeDto>().response.map { it.toItem() }

    /** Детали подборки; `null`, если сервер ответил 404 (подборка удалена или скрыта). */
    suspend fun collection(id: Int): CollectionItem? =
        try {
            client.get("${YaniHttp.baseUrl()}/collection/$id")
                .body<CollectionEnvelopeDto>()
                .response
                .toItem()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is ClientRequestException && e.response.status.value == 404) null else throw e
        }
}

/**
 * Подборки через [YaniRepository] — экраны получают данные так же, как из остальных разделов
 * репозитория, при этом существующий [YaniRepository] менять не нужно.
 */
suspend fun YaniRepository.collections(limit: Int = 30, offset: Int = 0): List<CollectionItem> =
    SharedCollectionsApi.api.collections(limit = limit, offset = offset)

/** Детали одной подборки через [YaniRepository]; `null`, если подборка не найдена. */
suspend fun YaniRepository.collection(id: Int): CollectionItem? =
    SharedCollectionsApi.api.collection(id)

/** Общий экземпляр API для расширений [YaniRepository]: HTTP-клиент один на приложение. */
private object SharedCollectionsApi {
    val api: YaniCollectionsApi by lazy { YaniCollectionsApi() }
}

@Serializable
internal data class CollectionsEnvelopeDto(val response: List<CollectionDto> = emptyList())

@Serializable
internal data class CollectionEnvelopeDto(val response: CollectionDto = CollectionDto())

@Serializable
internal data class CollectionDto(
    val id: Int = 0,
    val title: String = "",
    val description: String = "",
    val likes: CollectionLikesDto? = null,
    val animes: List<CollectionAnimeDto> = emptyList(),
    @SerialName("poster_previews") val posterPreviews: List<CollectionPosterDto> = emptyList(),
    val views: Int = 0,
    val owner: CollectionOwnerDto? = null,
)

@Serializable
internal data class CollectionLikesDto(
    val likes: Int = 0,
    val dislikes: Int = 0,
)

@Serializable
internal data class CollectionOwnerDto(
    val id: Int? = null,
    val nickname: String = "",
)

/** Карточка тайтла внутри подборки: те же поля, что у фида (лишние игнорируются). */
@Serializable
internal data class CollectionAnimeDto(
    @SerialName("anime_id") val animeId: Int? = null,
    val title: String = "",
    val description: String = "",
    val poster: CollectionPosterDto? = null,
    val rating: CollectionRatingDto? = null,
    val year: Int? = null,
    val type: CollectionTypeDto? = null,
    val views: Int? = null,
    val episodes: CollectionEpisodesDto? = null,
)

@Serializable
internal data class CollectionRatingDto(val average: Double? = null)

@Serializable
internal data class CollectionTypeDto(val name: String = "")

@Serializable
internal data class CollectionEpisodesDto(val count: Int? = null)

@Serializable
internal data class CollectionPosterDto(
    val small: String? = null,
    val medium: String? = null,
    val big: String? = null,
    val fullsize: String? = null,
    val mega: String? = null,
)

private fun CollectionDto.toItem(): CollectionItem = CollectionItem(
    id = id,
    title = title,
    likes = likes?.likes ?: 0,
    animes = animes.mapNotNull { it.toCard() },
    description = description.stripHtml(),
    views = views,
    posterPreviews = posterPreviews.mapNotNull { it.bestPreviewUrl() },
    ownerName = owner?.nickname?.takeIf { it.isNotBlank() },
)

private fun CollectionAnimeDto.toCard(): AnimeCard? {
    val id = animeId ?: return null
    return AnimeCard(
        id = id,
        title = title,
        posterUrl = poster?.bestPosterUrl(),
        rating = rating?.average,
        year = year,
        description = description.stripHtml(),
        typeName = type?.name?.takeIf { it.isNotBlank() },
        episodesCount = episodes?.count,
        views = views?.toLong(),
    )
}

/** Лучший доступный постер тайтла (big → fullsize → medium → small → mega). */
private fun CollectionPosterDto.bestPosterUrl(): String? =
    big.absUrl() ?: fullsize.absUrl() ?: medium.absUrl() ?: small.absUrl() ?: mega.absUrl()

/** Миниатюра превью подборки: на карточку хватает small/medium, big — запасной вариант. */
private fun CollectionPosterDto.bestPreviewUrl(): String? =
    small.absUrl() ?: medium.absUrl() ?: big.absUrl() ?: fullsize.absUrl() ?: mega.absUrl()
