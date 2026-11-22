// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.zedfilm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import su.afk.yummy.pc.extractors.ResolvedStream
import su.afk.yummy.pc.extractors.StreamResolver
import java.nio.charset.Charset

/**
 * Экстрактор Zedfilm/Hlamer: iframe-URL (`zedfilm.ru/<id>`) -> прямой поток (DASH `.mpd` или mp4).
 *
 * Порт `ZedfilmExtractor` из YummyTV 1:1 по шагам: GET iframe-страницы в windows-1251 ->
 * `video_Init('<base64 JSON>')` (`url` — основной DASH, `url2` — mp4-фолбэк) -> сканирование
 * страницы на ссылки `.mpd`/`.m3u8`/`.mp4` с отбрасыванием рекламных URL -> лестница качеств.
 * WebView-фолбэка нет: если контракт страницы изменится, экстрактор честно откажет.
 *
 * Ошибки наружу не бросаются: [resolve] возвращает `Result.failure(ZedfilmResolveException)`.
 *
 * @param userAgent UA браузера для iframe-страницы и плеера.
 * @param autoQualityLabel как называть качество «auto» в результате (`"auto"` по умолчанию).
 * @param onStage необязательный обработчик прогресса — для CLI/логов.
 */
class ZedfilmResolver(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val autoQualityLabel: String = "auto",
    private val onStage: (String) -> Unit = {},
) : StreamResolver {

    private val http = ZedfilmHttpClient(userAgent)

    override fun supports(iframeUrl: String): Boolean = ZedfilmParsing.isZedfilmPlayerUrl(iframeUrl)

    override suspend fun resolve(iframeUrl: String): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        try {
            Result.success(extract(iframeUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ZedfilmResolveException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                ZedfilmResolveException("${e::class.java.simpleName}: ${e.message.orEmpty()}", e),
            )
        }
    }

    private fun extract(iframeUrl: String): ResolvedStream {
        val playerUrl = ZedfilmParsing.normalizeUrl(iframeUrl)

        onStage("GET iframe page")
        val html = fetchIframeHtml(playerUrl)

        onStage("extract stream urls")
        val candidates = ZedfilmParsing.collectCandidates(html, playerUrl)
        if (candidates.isEmpty()) {
            throw ZedfilmResolveException("Zedfilm: no stream URLs found in iframe page")
        }

        val qualities = ZedfilmParsing.orderQualityMap(candidates, autoQualityLabel)
        if (qualities.isEmpty()) throw ZedfilmResolveException("Zedfilm: no usable stream qualities")

        return ResolvedStream(
            url = qualities.values.last(),
            headers = mapOf(
                "Referer" to playerUrl,
                "Origin" to ZedfilmParsing.HLAMER_ORIGIN,
                ZedfilmParsing.USER_AGENT_HEADER to userAgent,
            ),
            qualities = qualities.entries.map { it.key to it.value },
        )
    }

    /**
     * Zedfilm отдаёт текст страницы в windows-1251 — стандартная UTF-8-вычитка дала бы «кракозябры»
     * в мета/ошибках. Accept-Language и Accept-Encoding, как в Android-версии: без них сайт отвечает
     * на iframe-URL 404 «video not found».
     */
    private fun fetchIframeHtml(url: String): String {
        val response = http.get(
            url = url,
            headers = mapOf(
                "Referer" to ZedfilmParsing.YANI_REFERER,
                "Origin" to ZedfilmParsing.HLAMER_ORIGIN,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to ZedfilmParsing.RU_ACCEPT_LANGUAGE,
            ),
        )
        onStage("iframe page HTTP ${response.statusCode}")
        return response.text(WINDOWS_1251)
    }

    companion object {
        /** UA проверяемого профиля Windows Chrome (как `BrowserUserAgentProfile.WINDOWS_CHROME`). */
        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/149.0.0.0 Safari/537.36"

        private val WINDOWS_1251: Charset = Charset.forName("windows-1251")
    }
}
