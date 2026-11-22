// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import su.afk.yummy.tv.data.details.dto.YaniRecommendationItemDto
import su.afk.yummy.tv.data.details.dto.YaniRecommendationsDto

/**
 * Дополнительные ручки страницы тайтла, которых нет в [YaniApi]: похожие тайтлы и
 * подписка на новые серии выбранной озвучки.
 *
 * Заголовки `Authorization: Bearer` и `Lang` подставляет общий [YaniHttp.client], поэтому
 * вызывающему коду ничего передавать не нужно. Кэш ответов держит сервер YummyPC (GET без
 * авторизации кэшируется сам, см. `backend/yani.go`), в клиенте кэша нет: рекомендации
 * запрашиваются один раз при открытии страницы тайтла.
 *
 * @param client HTTP-клиент yani; по умолчанию общий [YaniHttp.client].
 */
class YaniAnimeExtrasApi(private val client: HttpClient = YaniHttp.client) {

    /**
     * Похожие тайтлы: `GET /anime/{animeId}/recommendations`.
     *
     * Ответ — `{response: [карточки аниме]}`; карточка содержит `anime_id`, `title`,
     * `poster {small, medium, big, fullsize, mega}`, `rating {average}`, `year` и `type {name}`.
     * Остальные поля ответа игнорируются.
     *
     * @param animeId id тайтла на yani; некорректный id не запрашивается.
     * @param limit сколько карточек просить у сервера (по умолчанию [RECOMMENDATIONS_LIMIT]).
     * @return карточки для ряда «Похожие тайтлы»; пустой список, если сервер ничего не вернул.
     */
    suspend fun recommendations(animeId: Int, limit: Int = RECOMMENDATIONS_LIMIT): List<AnimeCard> {
        if (animeId <= 0) return emptyList()
        val safeLimit = limit.coerceIn(1, MAX_RECOMMENDATIONS_LIMIT)
        val dto = client.get("${YaniHttp.baseUrl()}/anime/$animeId/recommendations") {
            parameter("limit", safeLimit)
        }.body<YaniRecommendationsDto>()
        return dto.response.mapNotNull { it.toCard() }
    }

    /**
     * Подписывает на новые серии выбранной озвучки: `PUT /video/{videoId}/subscribe`.
     *
     * Сервер принимает id любой серии нужной озвучки и сам сводит его к тройке
     * «тайтл + балансер + озвучка», поэтому подписка живёт на уровне группы видео, а не
     * отдельной серии. Нужен вход в аккаунт.
     */
    suspend fun subscribe(videoId: Int) {
        setSubscribed(videoId, subscribed = true)
    }

    /**
     * Отписывает от новых серий выбранной озвучки: `DELETE /video/{videoId}/subscribe`.
     *
     * Отписка — именно `DELETE`: повторный `PUT` на сайте подписку не снимает. Так же это
     * сделано в референсном ТВ-клиенте (`YaniAccountApi.removeSubscribed`).
     */
    suspend fun unsubscribe(videoId: Int) {
        setSubscribed(videoId, subscribed = false)
    }

    /**
     * Переключает подписку на новые серии: `true` — `PUT`, `false` — `DELETE`.
     *
     * Ответ сервера — `{response: Boolean}`; явный `false` означает, что подписка не
     * изменилась (например, кончился токен или сервер отклонил действие), поэтому это
     * исключение, а не молчаливый успех. Пустое тело и незнакомая обёртка считаются
     * успехом: сервер YummyPC проксирует ответ yani, но может отдать его без тела.
     *
     * @throws Exception ошибка сети/HTTP (Ktor с `expectSuccess`) или отказ сервера.
     */
    suspend fun setSubscribed(videoId: Int, subscribed: Boolean) {
        if (videoId <= 0) return
        val url = "${YaniHttp.baseUrl()}/video/$videoId/subscribe"
        val response = if (subscribed) client.put(url) else client.delete(url)
        val body = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
        if (mutationRejected(body)) {
            error("Сервер не изменил подписку (response = false)")
        }
    }

    /** `response: false` в теле ответа — подписка не изменилась. Остальное считаем успехом. */
    private fun mutationRejected(body: String): Boolean {
        if (body.isBlank()) return false
        val element = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return false
        val primitive = (element as? JsonObject)?.get("response") as? JsonPrimitive ?: return false
        return primitive.booleanOrNull == false
    }

    private companion object {
        /** Сколько похожих тайтлов просим: ровно один ряд на странице тайтла. */
        const val RECOMMENDATIONS_LIMIT = 12

        /** Защита от случайного огромного `limit`: больше одного ряда всё равно не показываем. */
        const val MAX_RECOMMENDATIONS_LIMIT = 30
    }
}

/**
 * Карточка рекомендации yani → карточка тайтла десктопа.
 *
 * Постер берём самый крупный из доступных (в карточках рекомендаций `poster` — объект с
 * размерами), ссылку приводим к https: сервер отдаёт протокол-относительные адреса.
 */
private fun YaniRecommendationItemDto.toCard(): AnimeCard? {
    val id = animeId ?: return null
    return AnimeCard(
        id = id,
        title = title,
        posterUrl = poster?.big.absUrl() ?: poster?.medium.absUrl() ?: poster?.small.absUrl(),
        rating = rating.average,
        year = year,
        typeName = type?.name,
    )
}
