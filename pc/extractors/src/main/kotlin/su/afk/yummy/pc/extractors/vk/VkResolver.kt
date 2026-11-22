// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.vk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import su.afk.yummy.pc.extractors.ResolvedStream
import su.afk.yummy.pc.extractors.StreamResolver

/**
 * Экстрактор VK: iframe-URL балансера -> прямой поток (обычно mp4 с CDN VK).
 *
 * Порт `VkExtractor` из YummyTV 1:1 по шагам: обёртка `iframeVK.html` -> `id=<owner>_<video>` ->
 * `video_ext.php` (windows-1251) -> ссылки на поток из JSON/HTML -> лестница качеств. VK примерно
 * в половине запросов отдаёт урезанную страницу без ссылок, поэтому `video_ext.php` перезапрашивается
 * до [VkParsing.VIDEO_EXT_MAX_ATTEMPTS] раз, если нет ни потоков, ни маркера «видео недоступно».
 *
 * Ошибки наружу не бросаются: [resolve] возвращает `Result`.
 * - [VkVideoUnavailableException] — VK сам сообщил, что видео нет/закрыто (контент, а не сбой);
 * - [VkResolveException] — технический отказ (нет ссылок, сеть не отдала страницу).
 *
 * @param userAgent UA браузера для страниц VK; плееру передаётся тот же UA (VK проверяет отпечаток).
 * @param autoQualityLabel как называть качество «auto» в результате (`"auto"` по умолчанию).
 * @param onStage необязательный обработчик прогресса — для CLI/логов.
 */
class VkResolver(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val autoQualityLabel: String = "auto",
    private val onStage: (String) -> Unit = {},
) : StreamResolver {

    private val http = VkHttpClient(userAgent)

    override fun supports(iframeUrl: String): Boolean = VkParsing.isVkPlayerUrl(iframeUrl)

    override suspend fun resolve(iframeUrl: String): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        try {
            Result.success(extract(iframeUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: VkVideoUnavailableException) {
            Result.failure(e)
        } catch (e: VkResolveException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                VkResolveException("${e::class.java.simpleName}: ${e.message.orEmpty()}", e),
            )
        }
    }

    private suspend fun extract(iframeUrl: String): ResolvedStream {
        val normalizedUrl = VkParsing.normalizeUrl(iframeUrl)
        val referer = normalizedUrl.ifBlank { VkParsing.DEFAULT_REFERER }

        onStage("GET iframe page")
        val iframeHtml = VkParsing.normalizePayload(fetchPage(normalizedUrl, referer).text())
        val videoExtUrl = VkParsing.resolveVideoExtUrl(iframeHtml, normalizedUrl)
        val sourceUrl = videoExtUrl ?: normalizedUrl

        var sourceBytes: ByteArray? = null
        var sourceHtml = iframeHtml
        var videoExtFailure: Throwable? = null
        var candidates: LinkedHashMap<String, String>
        var attempt = 0

        while (true) {
            attempt++
            if (videoExtUrl != null) {
                try {
                    onStage("GET video_ext page (attempt $attempt)")
                    val page = fetchPage(videoExtUrl, normalizedUrl)
                    sourceBytes = page.bodyBytes
                    sourceHtml = VkParsing.normalizePayload(page.text())
                    videoExtFailure = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    videoExtFailure = e
                    onStage("video_ext failed: ${e::class.java.simpleName}: ${e.message.orEmpty().take(80)}")
                }
            }

            candidates = VkParsing.collectCandidates(sourceHtml, sourceUrl)

            // Урезанная страница VK: ни ссылок, ни маркера «видео недоступно». Отказ в доступе
            // (error_code 204) повтором не лечится — страница каждый раз та же. Актуальная
            // SPA-страница тоже не меняется между запросами: её ссылки добираются из player API.
            val emptyPage = videoExtUrl != null &&
                videoExtFailure == null &&
                candidates.isEmpty() &&
                VkParsing.VIDEO_UNAVAILABLE_MARKER !in sourceHtml &&
                !VkParsing.isVkAccessDenied(sourceHtml) &&
                !VkParsing.isSpaPage(sourceHtml)
            if (!emptyPage || attempt >= VkParsing.VIDEO_EXT_MAX_ATTEMPTS) break
            delay(VkParsing.VIDEO_EXT_RETRY_DELAY_MS)
        }

        // Актуальный VK отдаёт SPA без статических ссылок: забираем player config через
        // анонимный web-token страницы и `video.getEmbed` (см. VkParsing).
        if (candidates.isEmpty() && videoExtUrl != null && videoExtFailure == null) {
            candidates = fetchEmbedApiCandidates(sourceHtml, videoExtUrl) ?: LinkedHashMap()
        }

        if (candidates.isEmpty()) {
            if (VkParsing.VIDEO_UNAVAILABLE_MARKER in sourceHtml) {
                val message = sourceBytes?.let(VkParsing::vkUnavailableMessage)
                throw VkVideoUnavailableException(
                    vkMessage = message,
                    videoNotFound = message == null || VkParsing.isVkVideoNotFoundMessage(message),
                )
            }
            // video_ext не загрузился, а запасная iframe-страница ссылок не содержит: это сбой сети,
            // а не отсутствие потоков.
            videoExtFailure?.let { failure ->
                throw VkResolveException(
                    "${failure::class.java.simpleName}: ${failure.message.orEmpty().take(60)}",
                    failure,
                )
            }
            if (VkParsing.isVkAccessDenied(sourceHtml)) {
                throw VkVideoUnavailableException(vkMessage = null, videoNotFound = true)
            }
            throw VkResolveException(
                "no stream URLs found (${sourceHtml.length} chars, " +
                    "${VkParsing.pageDiagnostics(sourceHtml, viaVideoExt = videoExtUrl != null)})",
            )
        }

        onStage("build quality ladder")
        val qualities = VkParsing.orderQualities(candidates, autoQualityLabel)
        if (qualities.isEmpty()) throw VkResolveException("no usable stream qualities")

        return ResolvedStream(
            url = qualities.values.last(),
            headers = streamHeaders(sourceUrl),
            qualities = qualities.entries.map { it.key to it.value },
        )
    }

    /**
     * Ссылки на поток из текущего player API VK. Актуальная страница `video_ext.php` — SPA: в HTML
     * только анонимный web-token и подпись видео (`video.getHashes`), а `files` с качеcтвами
     * отдаёт `api.vk.com/method/video.getEmbed`. Возвращает null, если SPA-маркеров нет или API
     * не дал ссылок (тогда вызывающий покажет обычную ошибку разбора).
     */
    private fun fetchEmbedApiCandidates(
        pageHtml: String,
        videoExtUrl: String,
    ): LinkedHashMap<String, String>? {
        val accessToken = VkParsing.extractWebToken(pageHtml) ?: run {
            onStage("SPA page without web token, player API is not callable")
            return null
        }
        val ownerId = VkParsing.parseQueryParam(videoExtUrl, "oid") ?: return null
        val videoId = VkParsing.parseQueryParam(videoExtUrl, "id") ?: return null
        val videoHash = VkParsing.extractVideoHash(pageHtml)

        onStage("GET video.getEmbed api")
        val apiUrl = VkParsing.buildEmbedApiUrl(ownerId, videoId, accessToken, videoHash)
        val response = http.get(
            url = apiUrl,
            headers = mapOf(
                "Referer" to VkParsing.DEFAULT_REFERER,
                "Accept" to "*/*",
            ),
        )
        val body = response.text()

        VkParsing.parseEmbedApiError(body)?.let { (code, message) ->
            if (code == ERROR_ACCESS_DENIED) {
                throw VkVideoUnavailableException(
                    vkMessage = message.takeIf { it.isNotBlank() },
                    videoNotFound = true,
                )
            }
            throw VkResolveException("VK player API error $code: ${message.take(80)}")
        }

        val candidates = VkParsing.parseEmbedFiles(body)
        if (candidates.isEmpty()) {
            onStage("VK player API returned no files (HTTP ${response.statusCode})")
            return null
        }
        return candidates
    }

    /** GET страницы VK; не-2xx — это отказ разбора (страница-заглушка вместо плеера). */
    private fun fetchPage(url: String, referer: String): VkHttpResponse {
        val response = http.get(
            url = url,
            headers = mapOf(
                "Referer" to referer,
                "Accept" to "*/*",
            ),
        )
        if (!response.isSuccess) {
            throw VkResolveException("HTTP ${response.statusCode}: ${response.text().take(80)}")
        }
        return response
    }

    /** Заголовки для плеера: VK проверяет Referer страницы и Origin, UA — браузерный. */
    private fun streamHeaders(referer: String): Map<String, String> = mapOf(
        "Referer" to referer,
        "Origin" to VkParsing.DEFAULT_REFERER.removeSuffix("/"),
        VkParsing.USER_AGENT_HEADER to userAgent,
    )

    companion object {
        /** UA проверяемого профиля Windows Chrome (как `BrowserUserAgentProfile.WINDOWS_CHROME`). */
        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/149.0.0.0 Safari/537.36"

        /** VK API error_code «Access denied»: видео закрыто для анонимного зрителя. */
        private const val ERROR_ACCESS_DENIED = 204
    }
}
