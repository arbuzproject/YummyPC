// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.rutube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import su.afk.yummy.pc.extractors.ResolvedStream
import su.afk.yummy.pc.extractors.StreamResolver

/**
 * Экстрактор Rutube: iframe-URL (`rutube.ru/play/embed/<32-hex-id>`) -> прямой HLS-поток.
 *
 * Порт `RutubeExtractor` из YummyTV 1:1 по шагам: id видео из ссылки -> `/api/play/options/<id>/`
 * -> `video_balancer.m3u8` (или `default`) -> разбор мастер-плейлиста в лестницу качеств (при
 * недоступном мастере остаётся один поток `auto`).
 *
 * Ошибки наружу не бросаются: [resolve] возвращает `Result.failure(RutubeResolveException)`.
 *
 * @param userAgent UA браузера для options API, мастер-плейлиста и плеера.
 * @param autoQualityLabel как называть качество «auto» в результате (`"auto"` по умолчанию).
 * @param onStage необязательный обработчик прогресса — для CLI/логов.
 */
class RutubeResolver(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val autoQualityLabel: String = "auto",
    private val onStage: (String) -> Unit = {},
) : StreamResolver {

    private val http = RutubeHttpClient(userAgent)

    override fun supports(iframeUrl: String): Boolean = RutubeParsing.isRutubePlayerUrl(iframeUrl)

    override suspend fun resolve(iframeUrl: String): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        try {
            Result.success(extract(iframeUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RutubeResolveException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                RutubeResolveException("${e::class.java.simpleName}: ${e.message.orEmpty()}", e),
            )
        }
    }

    private fun extract(iframeUrl: String): ResolvedStream {
        val normalizedUrl = RutubeParsing.normalizeUrl(iframeUrl)
        val videoId = RutubeParsing.extractVideoId(normalizedUrl)
            ?: throw RutubeResolveException("Rutube: video id not found in $normalizedUrl")

        onStage("GET options for $videoId")
        val optionsJson = fetchJson(
            url = RutubeParsing.optionsUrl(videoId),
            headers = fetchHeaders(normalizedUrl),
            failureMessage = "Rutube: options request failed",
        )
        val balancer = optionsJson.optJSONObject("video_balancer")
            ?: throw RutubeResolveException("Rutube: video_balancer not found in options response")
        val streamUrl = RutubeParsing.streamUrlFromBalancer(balancer)
            ?: throw RutubeResolveException("Rutube: video_balancer has no stream url")

        onStage("GET master playlist")
        val qualities = buildQualityMap(streamUrl, normalizedUrl)

        return ResolvedStream(
            url = qualities.values.last(),
            headers = streamHeaders(normalizedUrl),
            qualities = qualities.entries.map { it.key to it.value },
        )
    }

    /** Мастер-плейлист недоступен — не отказ: остаётся один поток `auto` (как в Android-версии). */
    private fun buildQualityMap(streamUrl: String, referer: String): LinkedHashMap<String, String> {
        val masterPlaylist = try {
            val response = http.get(streamUrl, headers = fetchHeaders(referer))
            if (!response.isSuccess) {
                throw RutubeResolveException("HTTP ${response.statusCode}")
            }
            response.body
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onStage("master playlist failed, fallback to auto: ${e.message.orEmpty().take(80)}")
            return RutubeParsing.orderQualityMap(linkedMapOf("auto" to streamUrl), autoQualityLabel)
        }
        return RutubeParsing.buildQualityMap(streamUrl, masterPlaylist, autoQualityLabel)
    }

    private fun fetchJson(
        url: String,
        headers: Map<String, String>,
        failureMessage: String,
    ): JSONObject {
        val response = http.get(url, headers)
        if (!response.isSuccess) {
            throw RutubeResolveException("$failureMessage: HTTP ${response.statusCode}")
        }
        return runCatching { JSONObject(response.body) }
            .getOrElse { throw RutubeResolveException("$failureMessage: unexpected body", it) }
    }

    private fun streamHeaders(referer: String): Map<String, String> = mapOf(
        "Referer" to referer,
        "Origin" to RutubeParsing.RUTUBE_ORIGIN,
        RutubeParsing.USER_AGENT_HEADER to userAgent,
    )

    private fun fetchHeaders(referer: String): Map<String, String> =
        streamHeaders(referer) + ("Accept" to "*/*")

    companion object {
        /** UA проверяемого профиля Windows Chrome (как `BrowserUserAgentProfile.WINDOWS_CHROME`). */
        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/149.0.0.0 Safari/537.36"
    }
}
