// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.aksor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import su.afk.yummy.pc.extractors.ResolvedStream
import su.afk.yummy.pc.extractors.StreamResolver
import java.io.IOException

/**
 * Экстрактор Aksor: iframe-URL `player.aksor.tv/video/<hash>` -> прямой mp4.
 *
 * Порт `AksorExtractor` из YummyTV 1:1 по шагам: hash из ссылки -> player API
 * `player.aksor.tv/api/video/<hash>` -> лестница `q360…q4k`. Если API не ответил JSON с качествами
 * (404/HTML), включается запасной разбор страницы плеера: мета `video_url`, затем поиск пути API в
 * скриптах плеера.
 *
 * Ошибки наружу не бросаются: [resolve] возвращает `Result.failure(AksorResolveException)`.
 * Сетевой сбой отличается по префиксу сообщения (тип исключения), как в Android-версии.
 *
 * @param userAgent UA браузера для плеера, API и CDN.
 * @param onStage необязательный обработчик прогресса — для CLI/логов.
 */
class AksorResolver(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val onStage: (String) -> Unit = {},
) : StreamResolver {

    private val http = AksorHttpClient(userAgent)

    override fun supports(iframeUrl: String): Boolean = AksorParsing.isAksorPlayerUrl(iframeUrl)

    override suspend fun resolve(iframeUrl: String): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        try {
            Result.success(extract(iframeUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AksorResolveException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                AksorResolveException("${e::class.java.simpleName}: ${e.message.orEmpty()}", e),
            )
        }
    }

    private fun extract(iframeUrl: String): ResolvedStream {
        val playerUrl = AksorParsing.normalizeUrl(iframeUrl)
        val hash = AksorParsing.extractHash(playerUrl)
            ?: throw AksorResolveException("Aksor: video hash not found in $playerUrl")

        val apiUrl = "${AksorParsing.PLAYER_ORIGIN}/api/video/$hash"
        var networkFailure: Throwable? = null
        fun noteNetwork(e: Throwable) {
            if (e is IOException) networkFailure = e
        }

        onStage("GET player API")
        val qualities = runCatching { fetchApiQualities(apiUrl, referer = playerUrl) }
            .onFailure(::noteNetwork)
            .getOrNull()

        val streamUrl = qualities?.values?.lastOrNull()
            ?: run {
                onStage("player API failed, fallback to page scan")
                runCatching { fetchFallbackStreamUrl(playerUrl, hash) }
                    .onFailure(::noteNetwork)
                    .getOrNull()
            }
            ?: throw networkFailure?.let { failure ->
                AksorResolveException(
                    "${failure::class.java.simpleName}: ${failure.message.orEmpty().take(60)}",
                    failure,
                )
            } ?: AksorResolveException("Aksor: no stream found")

        return ResolvedStream(
            url = streamUrl,
            headers = mapOf(
                "Referer" to playerUrl,
                AksorParsing.USER_AGENT_HEADER to userAgent,
            ),
            qualities = qualities?.entries?.map { it.key to it.value }
                ?: listOf("auto" to streamUrl),
        )
    }

    private fun fetchApiQualities(
        apiUrl: String,
        referer: String,
    ): LinkedHashMap<String, String>? {
        val body = http.get(apiUrl, apiHeaders(referer, accept = "application/json")).body
        // HTML-тело (404/заглушка) даст JSONException — вызывающий уйдёт в запасной разбор.
        val qualitiesJson = JSONObject(body).optJSONObject("qualities") ?: return null
        val qualities = LinkedHashMap<String, String>()
        AksorParsing.QUALITY_ORDER.forEach { key ->
            val streamUrl = qualitiesJson.optString(key)
                .trim()
                .takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            if (streamUrl != null) {
                qualities[AksorParsing.qualityLabel(key)] = AksorParsing.sanitizeStreamUrl(streamUrl)
            }
        }
        return qualities.takeIf { it.isNotEmpty() }
    }

    private fun fetchFallbackStreamUrl(playerUrl: String, hash: String): String? {
        onStage("GET player page")
        val html = http.get(
            url = playerUrl,
            headers = apiHeaders(referer = AksorParsing.YANI_REFERER, accept = "text/html"),
        ).body
        AksorParsing.extractMetaVideoUrl(html)?.let { return AksorParsing.sanitizeStreamUrl(it) }

        onStage("scan player scripts for API path")
        val apiPath = AksorParsing.scriptUrls(html).firstNotNullOfOrNull { scriptUrl ->
            runCatching {
                AksorParsing.apiPathFromScript(
                    http.get(scriptUrl, apiHeaders(referer = playerUrl, accept = "*/*")).body,
                )
            }.getOrNull()
        } ?: return null

        val apiUrl = when {
            apiPath.startsWith("http") -> "$apiPath/video/$hash"
            apiPath.startsWith("/") -> "${AksorParsing.PLAYER_ORIGIN}$apiPath/video/$hash"
            else -> "${AksorParsing.PLAYER_ORIGIN}/$apiPath/video/$hash"
        }
        return fetchApiQualities(apiUrl, referer = playerUrl)?.values?.lastOrNull()
    }

    private fun apiHeaders(referer: String, accept: String): Map<String, String> = mapOf(
        "Referer" to referer,
        "Accept" to accept,
    )

    companion object {
        /** UA проверяемого профиля Windows Chrome (как `BrowserUserAgentProfile.WINDOWS_CHROME`). */
        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/149.0.0.0 Safari/537.36"
    }
}
