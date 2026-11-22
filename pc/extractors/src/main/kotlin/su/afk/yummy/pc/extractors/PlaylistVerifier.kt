// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Первые байты ответа CDN по выданной ссылке.
 *
 * @property statusCode HTTP-статус после редиректов (CDN Kodik отвечает 302 на «красивый» хост).
 * @property prefix начало тела (до нескольких КБ), для HLS это `#EXTM3U...`.
 */
data class PlaylistProbe(
    val statusCode: Int,
    val prefix: String,
) {
    val isHlsPlaylist: Boolean get() = prefix.startsWith("#EXTM3U")
    val firstLine: String get() = prefix.lineSequence().firstOrNull().orEmpty()
}

/**
 * Автоматическая проверка результата [KodikResolver]: GET ссылки и сверка первых байт с `#EXTM3U`.
 *
 * Читает только префикс тела, поэтому не тянет сегменты плейлиста. Нужна CLI (`--verify`) и
 * диагностике: «ссылка разрешилась, но CDN отдаёт 403/404» — частая причина, которую не видно по
 * одному URL.
 */
class PlaylistVerifier(
    userAgent: String = KodikResolver.DEFAULT_USER_AGENT,
) {
    private val http = KodikHttpClient(userAgent)

    suspend fun check(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): PlaylistProbe = withContext(Dispatchers.IO) {
        val response = http.readPrefix(url, headers = headers, maxBytes = maxBytes)
        PlaylistProbe(statusCode = response.statusCode, prefix = response.body)
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 4096
    }
}
