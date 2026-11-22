// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.sibnet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import su.afk.yummy.pc.extractors.ResolvedStream
import su.afk.yummy.pc.extractors.StreamResolver

/**
 * Экстрактор Sibnet: iframe-URL (`video.sibnet.ru/shell.php?videoid=...`) -> прямой mp4.
 *
 * Порт `SibnetExtractor` из YummyTV 1:1 по шагам: GET страницы плеера -> `player.src([{src:...}])`
 * или `<source src="...">` -> абсолютный URL. Качеств Sibnet не публикует: в результате одна пара
 * `auto`, а плееру передаются Referer/Origin/UA.
 *
 * Ошибки наружу не бросаются: [resolve] возвращает `Result`.
 * - [SibnetAccessForbiddenException] — Sibnet ответил 403 этой сети (контент тут не при чём);
 * - [SibnetResolveException] — другой HTTP-код или в странице нет ссылки на mp4.
 *
 * @param userAgent UA браузера для страницы плеера и для mp4.
 * @param onStage необязательный обработчик прогресса — для CLI/логов.
 */
class SibnetResolver(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val onStage: (String) -> Unit = {},
) : StreamResolver {

    private val http = SibnetHttpClient(userAgent)

    override fun supports(iframeUrl: String): Boolean = SibnetParsing.isSibnetPlayerUrl(iframeUrl)

    override suspend fun resolve(iframeUrl: String): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        val playerUrl = normalizeIframeUrl(iframeUrl)
        try {
            Result.success(extract(playerUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SibnetAccessForbiddenException) {
            Result.failure(e)
        } catch (e: SibnetResolveException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                SibnetResolveException("${e::class.java.simpleName}: ${e.message.orEmpty()}", e),
            )
        }
    }

    private fun extract(playerUrl: String): ResolvedStream {
        onStage("GET player page")
        val page = fetchPlayerPage(playerUrl)

        onStage("extract mp4 source")
        val streamUrl = SibnetParsing.extractStreamUrl(page, playerUrl)
            ?: throw SibnetResolveException("MP4 source was not found")

        // Одно качество: Sibnet отдаёт единственный mp4, лестницы нет.
        return ResolvedStream(
            url = streamUrl,
            headers = mapOf(
                "Referer" to playerUrl,
                "Origin" to SibnetParsing.SIBNET_ORIGIN,
                SibnetParsing.USER_AGENT_HEADER to userAgent,
            ),
            qualities = listOf("auto" to streamUrl),
        )
    }

    private fun normalizeIframeUrl(iframeUrl: String): String =
        SibnetParsing.normalizeUrl(iframeUrl)

    private fun fetchPlayerPage(playerUrl: String): String {
        val response = http.get(
            url = playerUrl,
            headers = mapOf(
                "Referer" to SibnetParsing.YANI_ORIGIN,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            ),
        )
        if (response.statusCode == HTTP_FORBIDDEN) throw SibnetAccessForbiddenException()
        if (!response.isSuccess) {
            throw SibnetResolveException("HTTP ${response.statusCode}")
        }
        return response.body
    }

    companion object {
        /** UA проверяемого профиля Windows Chrome (как `BrowserUserAgentProfile.WINDOWS_CHROME`). */
        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/149.0.0.0 Safari/537.36"

        private const val HTTP_FORBIDDEN = 403
    }
}
