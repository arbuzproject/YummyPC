// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import su.afk.yummy.tv.core.model.comments.CommentTargetType

/**
 * Комментарий yani, подготовленный для показа на десктопе.
 *
 * Живой API (`GET /comments/anime/{id}`) отдаёт «плоский» объект без вложенных ответов:
 * автора, аватар, текст с BB-кодом, unix-время, лайки/дизлайки и `children_count`. Сами ответы
 * лежат в отдельной ручке `GET /comments/{id}/children` — по одной на корневой комментарий.
 *
 * @property parentId id родителя; `0` у комментариев верхнего уровня.
 * @property authorName ник автора (`name` в ответе yani).
 * @property avatarUrl абсолютная ссылка на аватар: yani присылает её без схемы (`//static...`).
 * @property text текст без HTML и BB-кода — готов для [androidx.compose.material3.Text].
 * @property createdAtSec unix-время комментария в секундах (поле `time`).
 * @property likes/dislikes числа реакций; голосовать в desktop-клиенте нельзя, показываем счётчики.
 * @property childrenCount сколько ответов у комментария; `0` — ответов нет.
 * @property isDeleted комментарий удалён (`deleted_at > 0`): на экране показываем пометку.
 * @property roles служебные роли автора yani (`editorPlus`, `testerDeputy` и т.п.); пустой список у обычных.
 */
data class CommentItem(
    val id: Int,
    val parentId: Int,
    val userId: Int,
    val authorName: String,
    val avatarUrl: String?,
    val text: String,
    val createdAtSec: Long,
    val likes: Int,
    val dislikes: Int,
    val childrenCount: Int,
    val isDeleted: Boolean,
    val roles: List<String>,
)

/**
 * Сортировка комментариев: значения `sort` живого API.
 *
 * Проверено запросами: yani принимает только `new` и `old`; `popular`, `top`, `best`, `rating`,
 * `likes` и `date` отвечают HTTP 400. По умолчанию сервер отдаёт `new` — сначала новые.
 */
enum class CommentSort(val apiValue: String) {
    NEW("new"),
    OLD("old"),
}

/**
 * Страница комментариев.
 *
 * @property comments комментарии в порядке, который вернул сервер.
 * @property hasMore есть ли следующая страница: сервер не присылает общее число, поэтому
 *   признак — заполненная до конца страница (`comments.size >= limit`).
 */
data class CommentsPage(
    val comments: List<CommentItem>,
    val hasMore: Boolean,
)

/**
 * Комментарии yani к тайтлу: `GET /comments/anime/{animeId}` и `GET /comments/{id}/children`.
 *
 * Заголовки `Authorization: Bearer` и `Lang` подставляет общий [YaniHttp.client], `X-Application`
 * клиент не отправляет — его добавляет сервер YummyPC. Экран комментариев работает и без входа:
 * список читается с токеном или без него, действия (лайк/ответ) в десктоп-клиенте не реализованы.
 *
 * Пагинация — те же `limit` + `skip`, что и у остальных списков yani; размер страницы
 * [PAGE_SIZE]. Ответы на комментарий приходят одним списком без пагинации: ручка `children`
 * не понимает `limit`, `skip` и `sort` (отвечает предупреждениями `unknown_parameter`).
 *
 * @param client HTTP-клиент yani; по умолчанию общий [YaniHttp.client].
 */
class YaniCommentsApi(
    private val client: HttpClient = YaniHttp.client,
) {

    /**
     * Комментарии верхнего уровня к тайтлу.
     *
     * @param animeId id тайтла на yani; некорректный id не запрашивается.
     * @param skip сколько комментариев пропустить (для кнопки «Загрузить ещё»).
     * @param limit размер страницы; ограничивается [MAX_PAGE_SIZE].
     * @param sort порядок [CommentSort]: по умолчанию сначала новые.
     * @return страница комментариев; пустой список, если у тайтла нет комментариев.
     */
    suspend fun comments(
        animeId: Int,
        skip: Int = 0,
        limit: Int = PAGE_SIZE,
        sort: CommentSort = CommentSort.NEW,
    ): CommentsPage {
        if (animeId <= 0) return CommentsPage(emptyList(), hasMore = false)
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val safeSkip = skip.coerceAtLeast(0)
        val dto = client.get("${YaniHttp.baseUrl()}/comments/${CommentTargetType.ANIME.apiValue}/$animeId") {
            parameter("limit", safeLimit)
            parameter("skip", safeSkip)
            parameter("sort", sort.apiValue)
        }.body<CommentsEnvelopeDto>()
        val raw = dto.response.comments
        return CommentsPage(
            comments = raw.map { it.toItem() },
            hasMore = raw.size >= safeLimit,
        )
    }

    /**
     * Ответы на комментарий: `GET /comments/{commentId}/children`.
     *
     * Возвращает все ответы сразу (обычно единицы, максимум — несколько десятков), поэтому
     * пагинация не нужна. Дальше одного уровня yani ответы не вкладывает: у детей
     * `children_count` всегда `0`.
     *
     * @param commentId id корневого комментария; некорректный id не запрашивается.
     */
    suspend fun replies(commentId: Int): List<CommentItem> {
        if (commentId <= 0) return emptyList()
        val dto = client.get("${YaniHttp.baseUrl()}/comments/$commentId/children").body<CommentsEnvelopeDto>()
        return dto.response.comments.map { it.toItem() }
    }

    companion object {
        /** Размер страницы комментариев: столько же просим у сервера и добавляем по «Загрузить ещё». */
        const val PAGE_SIZE = 20

        /** Защита от случайного огромного `limit`; живой API спокойно отдаёт 100, но нам хватает 50. */
        private const val MAX_PAGE_SIZE = 50
    }
}

@Serializable
internal data class CommentsEnvelopeDto(
    val response: CommentsResponseDto = CommentsResponseDto(),
)

@Serializable
internal data class CommentsResponseDto(
    val comments: List<CommentDto> = emptyList(),
    @SerialName("isMod") val isMod: Boolean = false,
)

@Serializable
internal data class CommentAvatarsDto(
    val small: String? = null,
    val big: String? = null,
    val full: String? = null,
)

@Serializable
internal data class CommentDto(
    val id: Int = 0,
    @SerialName("parent_id") val parentId: Int = 0,
    @SerialName("user_id") val userId: Int = 0,
    val name: String = "",
    val text: String = "",
    val avatars: CommentAvatarsDto? = null,
    val time: Long = 0,
    val likes: Int = 0,
    val dislikes: Int = 0,
    @SerialName("children_count") val childrenCount: Int = 0,
    @SerialName("deleted_at") val deletedAt: Long = 0,
    val roles: List<String> = emptyList(),
)

private fun CommentDto.toItem(): CommentItem = CommentItem(
    id = id,
    parentId = parentId,
    userId = userId,
    authorName = name.takeIf { it.isNotBlank() } ?: "Пользователь",
    // Аватар берём самый крупный из доступных: на карточке он всё равно ужимается до 40 dp,
    // но полноразмерный Skia декодирует с меньшим числом артефактов.
    avatarUrl = avatars?.full.absUrl() ?: avatars?.big.absUrl() ?: avatars?.small.absUrl(),
    text = text.commentText(),
    createdAtSec = time,
    likes = likes,
    dislikes = dislikes,
    childrenCount = childrenCount,
    isDeleted = deletedAt > 0,
    roles = roles,
)

/** Открывающий/закрывающий BB-тег: `[спойлер="..." ]`, `[/спойлер]`, `[ник]`, `[quote]` и т.п. */
private val COMMENT_BB_TAG = Regex(
    """\[/?(?:спойлер|spoiler|ник|nick|b|i|u|s|quote|url|img|video|code)(?:=[^\]]*)?]""",
    RegexOption.IGNORE_CASE,
)

/** Слишком много пустых строк: оставляем максимум одну пустую между абзацами. */
private val COMMENT_BLANK_LINES = Regex("""\n{3,}""")

/** Несколько пробелов/табов подряд (в том числе после вырезанного BB-тега) — один пробел. */
private val COMMENT_SPACES = Regex("""[ \t]{2,}""")

/**
 * Текст комментария для десктопа: HTML-теги убираются [stripHtml], BB-код (`[спойлер]`, `[ник]`
 * и т.п.) вырезается, лишние пустые строки и пробелы схлопываются.
 *
 * Эмодзи-коды yani (`:like:`, `:capiyummy:`) не трогаем: в отличие от тегов это часть текста,
 * и подставлять вместо них картинки на десктопе пока нечем.
 */
internal fun String.commentText(): String =
    COMMENT_BB_TAG.replace(stripHtml(), "")
        .replace(COMMENT_SPACES, " ")
        .replace(COMMENT_BLANK_LINES, "\n\n")
        .trim()
