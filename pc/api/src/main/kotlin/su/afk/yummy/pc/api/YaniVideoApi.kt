// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.api

import io.ktor.client.HttpClient
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

/**
 * Синхронизация просмотров с сайтом yani.
 *
 * Эндпоинт — `PUT /video/{videoId}` («Mark video with ID as watched»): тело `{time, duration, times}`,
 * ответ `{response: Boolean}` (см. `docs/api-contract.md` §3.2 и `YaniAccountApi.markWatched` в
 * Android-клиенте). Авторизацию (`Authorization: Bearer …`) и язык подставляет общий [YaniHttp],
 * поэтому отдельных заголовков здесь нет. Гость получит 401/403 — вызывающая сторона решает,
 * считать ли это ошибкой: вызовы best-effort и обёрнуты в `runCatching`.
 */
class YaniVideoApi(private val client: HttpClient = YaniHttp.client) {

    /**
     * Mark video with ID as watched — отметка без прогресса.
     *
     * Короткая форма для случаев, когда позиция и длительность неизвестны: в теле уходят нули.
     * Игроку плеера нужен [markWatched] с прогрессом — он попадает в «Продолжить просмотр».
     */
    suspend fun markWatched(videoId: Int) {
        markWatched(videoId, timeSeconds = 0, durationSeconds = 0)
    }

    /**
     * Отправляет прогресс просмотра видео: сервер обновит «Продолжить просмотр» и историю.
     *
     * [timeSeconds] — текущая позиция, [durationSeconds] — длительность серии. Позиция
     * зажимается в `[0, duration - 10]` — тот же хвост эпизода ([WATCH_END_TOLERANCE_SECONDS]),
     * который не отправляет веб-клиент yani: иначе серия может считаться досмотренной заранее.
     */
    suspend fun markWatched(videoId: Int, timeSeconds: Int, durationSeconds: Int) {
        if (videoId <= 0) return
        val duration = durationSeconds.coerceAtLeast(0)
        val tail = if (duration > WATCH_END_TOLERANCE_SECONDS) WATCH_END_TOLERANCE_SECONDS else 0
        val time = timeSeconds.coerceIn(0, (duration - tail).coerceAtLeast(0))
        client.put("${YaniHttp.baseUrl()}/video/$videoId") {
            contentType(ContentType.Application.Json)
            setBody(MarkWatchedBody(time = time, duration = duration))
        }
    }

    /** Тело `PUT /video/{videoId}` (`YaniPutVideoBodyDto` в Android-клиенте). */
    @Serializable
    private data class MarkWatchedBody(
        val time: Int,
        val duration: Int,
        /** Уникальные просмотренные секунды: из них сервер считает время просмотра. */
        val times: List<Int> = emptyList(),
    )

    private companion object {
        /** Хвост серии (секунды), который не отправляется как позиция просмотра. */
        const val WATCH_END_TOLERANCE_SECONDS = 10
    }
}
