// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Рубрика новостей yani (`GET /posts/categories`).
 *
 * @property uri код рубрики для фильтра `GET /posts?category=`: `site_news`, `anime_news`,
 * `articles`, `interview` (в живом API именно `interview`, без «s»).
 * @property title подпись для интерфейса с заглавной буквы: yani присылает «новости аниме».
 */
data class NewsCategory(
    val id: Int,
    val title: String,
    val uri: String,
)

/** Автор публикации: на десктопе показываем только ник и аватар. */
data class NewsAuthor(
    val id: Int,
    val nickname: String,
    val avatarUrl: String? = null,
)

/**
 * Карточка публикации в ленте новостей (`GET /posts`).
 *
 * @property previewImageUrl абсолютная ссылка на превью (yani присылает её без схемы, `//static...`).
 *   Чаще всего это AVIF: Skia его не декодирует, поэтому картинки новостей на десктопе грузит
 *   отдельный WIC-декодер из модуля app.
 * @property preview текст анонса без HTML.
 * @property createdAtSec unix-время публикации в секундах.
 */
data class NewsPost(
    val id: Int,
    val title: String,
    val previewImageUrl: String?,
    val preview: String,
    val author: NewsAuthor,
    val category: NewsCategory,
    val createdAtSec: Long,
)

/** Тайтл, связанный с публикацией (поле `animes` в детальном ответе). */
data class NewsRelatedAnime(
    val animeId: Int,
    val title: String,
    val posterUrl: String?,
    val year: Int?,
    val rating: Double?,
)

/**
 * Публикация целиком (`GET /posts/{id}`).
 *
 * @property contentHtml исходный HTML из поля `content` (картинки в тексте приходят без схемы).
 * @property contentText тот же HTML, очищенный для текстового показа: абзацы сохранены переводами строк.
 * @property imageUrl обложка: `preview_image`, а если её нет — первая картинка из текста.
 * @property editedAtSec unix-время правки; null, если публикацию не правили.
 * @property likes/dislikes реакции yani (на десктопе показываем только числа).
 * @property relatedAnime связанные тайтлы — по клику открывается страница тайтла.
 */
data class NewsPostDetails(
    val id: Int,
    val title: String,
    val contentHtml: String,
    val contentText: String,
    val imageUrl: String?,
    val author: NewsAuthor,
    val category: NewsCategory,
    val createdAtSec: Long,
    val editedAtSec: Long?,
    val views: Int,
    val comments: Int,
    val likes: Int,
    val dislikes: Int,
    val relatedAnime: List<NewsRelatedAnime>,
)

/** Сортировка ленты новостей: значения `sort` живого API. */
enum class NewsSort(val apiValue: String) {
    /** Сначала новые — значение по умолчанию в API и на экране. */
    NEW("new"),

    /** «Лучшие»: yani сортирует по рейтингу публикации. Значение `best` (не `top`: на `top` — HTTP 400). */
    BEST("best"),

    /** Сначала старые. */
    OLD("old"),
}

/**
 * Новости yani: `GET /posts`, `GET /posts/{id}` и `GET /posts/categories`.
 *
 * Живой API проверен запросами: `category` принимает uri рубрики (`site_news`, `anime_news`,
 * `articles`, `interview`), `sort` — только `new` / `best` / `old` (значения `top` и `popular`
 * отвечают 400), листание — `limit` + `skip`. Ссылки на картинки приходят протокол-относительными
 * (`//static.yani.tv/...`), поэтому для JVM-клиента они переводятся в `https` тем же [absUrl].
 *
 * Ответы кэшируются в памяти на 5–10 минут: переключение сортировки/рубрики и возврат из
 * публикации не дёргают сеть. Кэш не чистится принудительно — самый старый ответ просто
 * перезапрашивается по истечении TTL.
 */
class YaniNewsApi(
    private val client: HttpClient = YaniHttp.client,
) {

    private class Entry(val at: Long, val value: Any)

    private val cache = ConcurrentHashMap<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, ttlMs: Long, loader: suspend () -> T): T {
        val now = System.currentTimeMillis()
        cache[key]?.let { if (now - it.at < ttlMs) return it.value as T }
        val value = loader()
        cache[key] = Entry(now, value)
        return value
    }

    /** Рубрики новостей; на ошибке сети экран использует встроенный список. */
    suspend fun categories(): List<NewsCategory> = cached("categories", CATEGORIES_TTL_MS) {
        client.get("${YaniHttp.baseUrl()}/posts/categories")
            .body<NewsCategoriesResponseDto>()
            .response
            .map { it.toCategory() }
    }

    /**
     * Страница ленты новостей.
     *
     * @param categoryUri uri рубрики (`null` или пустая строка — все рубрики).
     * @param sort порядок сортировки.
     * @param limit размер страницы (yani отдаёт не больше 20 на самых свежих лентах, больше — можно).
     * @param skip сколько публикаций пропустить (пагинация «Загрузить ещё»).
     */
    suspend fun posts(
        categoryUri: String? = null,
        sort: NewsSort = NewsSort.NEW,
        limit: Int = PAGE_SIZE,
        skip: Int = 0,
    ): List<NewsPost> {
        val key = "feed:${categoryUri.orEmpty()}:${sort.apiValue}:$limit:$skip"
        return cached(key, FEED_TTL_MS) {
            client.get("${YaniHttp.baseUrl()}/posts") {
                parameter("category", categoryUri.orEmpty())
                parameter("sort", sort.apiValue)
                parameter("limit", limit.coerceAtLeast(1))
                parameter("skip", skip.coerceAtLeast(0))
            }.body<NewsPostsResponseDto>().response.map { it.toPost() }
        }
    }

    /** Публикация целиком, включая HTML-текст, счётчики и связанные тайтлы. */
    suspend fun post(id: Int): NewsPostDetails = cached("post:$id", DETAIL_TTL_MS) {
        client.get("${YaniHttp.baseUrl()}/posts/$id")
            .body<NewsPostEnvelopeDto>()
            .response
            .toDetails()
    }

    private companion object {
        /** Размер страницы ленты. */
        const val PAGE_SIZE = 20

        /** Лента новостей обновляется часто: 5 минут хватает, чтобы не дёргать сеть при переключениях. */
        const val FEED_TTL_MS = 5 * 60 * 1000L

        /** Текст публикации и рубрики меняются редко — держим дольше. */
        const val DETAIL_TTL_MS = 10 * 60 * 1000L
        const val CATEGORIES_TTL_MS = 10 * 60 * 1000L
    }
}

@Serializable
internal data class NewsCategoriesResponseDto(val response: List<NewsCategoryDto> = emptyList())

@Serializable
internal data class NewsPostsResponseDto(val response: List<NewsPostDto> = emptyList())

@Serializable
internal data class NewsPostEnvelopeDto(val response: NewsPostDetailsDto = NewsPostDetailsDto())

@Serializable
internal data class NewsCategoryDto(
    val id: Int = 0,
    val title: String = "",
    val uri: String = "",
)

@Serializable
internal data class NewsAvatarDto(
    val small: String? = null,
    val big: String? = null,
    val full: String? = null,
)

@Serializable
internal data class NewsAuthorDto(
    val id: Int = 0,
    val nickname: String = "",
    val avatars: NewsAvatarDto? = null,
)

@Serializable
internal data class NewsPostDto(
    val id: Int = 0,
    val title: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    val category: NewsCategoryDto = NewsCategoryDto(),
    @SerialName("content_preview") val contentPreview: String = "",
    @SerialName("preview_image") val previewImage: String? = null,
    val user: NewsAuthorDto = NewsAuthorDto(),
)

@Serializable
internal data class NewsLikesDto(
    val likes: Int = 0,
    val dislikes: Int = 0,
    val vote: Int = 0,
)

@Serializable
internal data class NewsPostPosterDto(
    val small: String? = null,
    val medium: String? = null,
    val big: String? = null,
    val fullsize: String? = null,
    val huge: String? = null,
    val mega: String? = null,
)

@Serializable
internal data class NewsPostRatingDto(val average: Double? = null)

@Serializable
internal data class NewsPostAnimeDto(
    @SerialName("anime_id") val animeId: Int = 0,
    val title: String = "",
    val poster: NewsPostPosterDto? = null,
    val year: Int? = null,
    val rating: NewsPostRatingDto? = null,
)

@Serializable
internal data class NewsPostDetailsDto(
    val id: Int = 0,
    val title: String = "",
    val content: String = "",
    @SerialName("preview_image") val previewImage: String? = null,
    val category: NewsCategoryDto = NewsCategoryDto(),
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("edited_at") val editedAt: Long? = null,
    val user: NewsAuthorDto = NewsAuthorDto(),
    val animes: List<NewsPostAnimeDto> = emptyList(),
    val likes: NewsLikesDto = NewsLikesDto(),
    val views: Int = 0,
    val comments: Int = 0,
)

private fun NewsCategoryDto.toCategory(): NewsCategory = NewsCategory(
    id = id,
    // yani присылает названия рубрик со строчной буквы: «новости аниме» → «Новости аниме».
    title = title.replaceFirstChar { it.uppercase() },
    uri = uri,
)

private fun NewsAuthorDto.toAuthor(): NewsAuthor = NewsAuthor(
    id = id,
    nickname = nickname.takeIf { it.isNotBlank() } ?: "yani",
    avatarUrl = avatars?.full.absUrl() ?: avatars?.big.absUrl() ?: avatars?.small.absUrl(),
)

private fun NewsPostDto.toPost(): NewsPost = NewsPost(
    id = id,
    title = title.takeIf { it.isNotBlank() } ?: "Без названия",
    previewImageUrl = previewImage.absUrl(),
    preview = contentPreview.newsText(),
    author = user.toAuthor(),
    category = category.toCategory(),
    createdAtSec = createdAt,
)

private fun NewsPostDetailsDto.toDetails(): NewsPostDetails {
    val text = content.newsText()
    return NewsPostDetails(
        id = id,
        title = title.takeIf { it.isNotBlank() } ?: "Без названия",
        contentHtml = content,
        contentText = text,
        // Если обложки нет, показываем первую картинку из текста публикации.
        imageUrl = previewImage.absUrl() ?: firstImageUrl(content),
        author = user.toAuthor(),
        category = category.toCategory(),
        createdAtSec = createdAt,
        editedAtSec = editedAt?.takeIf { it > 0 && it != createdAt },
        views = views,
        comments = comments,
        likes = likes.likes,
        dislikes = likes.dislikes,
        relatedAnime = animes
            .filter { it.animeId > 0 }
            .distinctBy { it.animeId }
            .map { it.toRelatedAnime() },
    )
}

private fun NewsPostAnimeDto.toRelatedAnime(): NewsRelatedAnime = NewsRelatedAnime(
    animeId = animeId,
    title = title.takeIf { it.isNotBlank() } ?: "Без названия",
    posterUrl = poster?.big.absUrl() ?: poster?.fullsize.absUrl() ?: poster?.medium.absUrl()
        ?: poster?.small.absUrl() ?: poster?.mega.absUrl(),
    year = year,
    rating = rating?.average?.takeIf { it > 0.0 },
)

/** Конец блочных тегов HTML — превращаем его в перевод строки, чтобы абзацы не слипались. */
private val HTML_BLOCK_END = Regex("(?i)</(?:p|div|li|h[1-6]|blockquote|tr|ul|ol)\\s*>")

/** Абзацы текста: блочные теги → пустая строка, дальше обычный [stripHtml]. */
internal fun String.newsText(): String =
    replace(HTML_BLOCK_END, "\n\n")
        .stripHtml()
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

/** Первая картинка из HTML-текста публикации (если у поста нет обложки). */
private val HTML_FIRST_IMAGE = Regex("""(?is)<img[^>]+src\s*=\s*["']([^"']+)["']""")

private fun firstImageUrl(html: String): String? =
    HTML_FIRST_IMAGE.find(html)?.groupValues?.getOrNull(1)?.absUrl()
