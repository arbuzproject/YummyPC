// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.cvh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import su.afk.yummy.pc.extractors.ResolvedStream
import su.afk.yummy.pc.extractors.StreamResolver

/**
 * Экстрактор CVH (CdnVideoHub): iframe-URL вида
 * `iframeCVH.html?dubbing_code=AnimeVost&anime_id=61967&episode=1&dubbing=Озвучка+AnimeVost`
 * -> прямой mp4 на okcdn.
 *
 * Порт `CvhExtractor` из YummyTV 1:1 по шагам: playlist API -> выбор записи по серии/озвучке ->
 * video API по `vkId` -> лестница mp4-качеств. HLS намеренно не берётся (см. [CvhParsing.buildQualityLadder]).
 *
 * Ошибки наружу не бросаются: [resolve] возвращает `Result`.
 * - [CvhRegionBlockedException] — контент запрещён в РФ;
 * - [CvhUnavailableException] — плейлист пуст или в нём нет нужной серии/озвучки;
 * - [CvhResolveException] — технический отказ (нет `anime_id`/`vkId`/источников, HTTP-ошибка).
 *
 * @param userAgent UA браузера для API и плеера (CVH отдаёт ссылки на okcdn, UA нужен только плееру).
 * @param forceFailover перерезолв после сбоя воспроизведения: основной узел okcdn мог обрывать
 *   ответы, поэтому все качества переводятся на failoverHost из ответа API (подпись от узла не зависит).
 * @param onStage необязательный обработчик прогресса — для CLI/логов.
 */
class CvhResolver(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val forceFailover: Boolean = false,
    private val onStage: (String) -> Unit = {},
) : StreamResolver {

    private val http = CvhHttpClient(userAgent)

    override fun supports(iframeUrl: String): Boolean = isCvhPlayerUrl(iframeUrl)

    override suspend fun resolve(iframeUrl: String): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        try {
            Result.success(extract(iframeUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: CvhRegionBlockedException) {
            Result.failure(e)
        } catch (e: CvhUnavailableException) {
            Result.failure(e)
        } catch (e: CvhResolveException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                CvhResolveException("${e::class.java.simpleName}: ${e.message.orEmpty()}", e),
            )
        }
    }

    private suspend fun extract(iframeUrl: String): ResolvedStream {
        val fullUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
        val params = CvhParsing.parseQuery(fullUrl.substringAfter("?", ""))

        val animeId = params["anime_id"] ?: throw CvhResolveException("missing anime_id")
        val episodeNum = params["episode"]?.toIntOrNull() ?: 1
        val dubbingCode = params["dubbing_code"].orEmpty()
        val dubbingLabel = params["dubbing"].orEmpty()

        onStage("GET playlist (anime_id=$animeId)")
        val playlistJson = fetchJsonWithRetry(
            "$PLAYLIST_URL?pub=$PUBLISHER_ID&id=$animeId&aggr=$AGGREGATOR",
        )
        val playlist = CvhParsing.parsePlaylist(playlistJson)

        if (CvhParsing.isCvhRegionBlocked(playlist.tags, hasItems = playlist.hasItems)) {
            throw CvhRegionBlockedException(playlist.tags)
        }
        val items = playlist.items
            ?.takeIf { it.isNotEmpty() }
            ?: throw CvhUnavailableException("playlist has no items")

        onStage("select dubbing/episode")
        val item = CvhParsing.selectCvhItem(
            items = items,
            isSerial = playlist.isSerial,
            episodeNum = episodeNum,
            dubbingCode = dubbingCode,
            dubbingLabel = dubbingLabel,
        ) ?: throw CvhUnavailableException(
            "no playlist item for episode $episodeNum (isSerial=${playlist.isSerial}, items=${items.size})",
        )

        val vkId = item.vkId.takeIf { it.isNotEmpty() }
            ?: throw CvhResolveException("playlist item has no vkId")

        onStage("GET video/$vkId")
        val videoJson = fetchJsonWithRetry("$VIDEO_URL/$vkId")
        val failoverHost = videoJson.optString("failoverHost").takeIf { it.isNotBlank() }
        val sources = videoJson.optJSONObject("sources")
            ?: throw CvhResolveException("video response has no sources")

        onStage("build mp4 quality ladder")
        val qualities = CvhParsing.buildQualityLadder(
            sources = sources,
            failoverHost = failoverHost,
            forceFailover = forceFailover,
        )
        if (qualities.isEmpty()) throw CvhResolveException("no mp4 qualities in sources")

        return ResolvedStream(
            url = qualities.values.last(),
            // CVH отдаёт подписанные ссылки okcdn: плееру достаточно UA из настроек.
            headers = mapOf(CvhHttpClient.USER_AGENT_HEADER to userAgent),
            qualities = qualities.entries.map { it.key to it.value },
        )
    }

    /**
     * HTTP-ошибка бросает исключение (а не маскируется под JSON-ошибку на HTML-теле); один повтор
     * после короткой паузы закрывает кратковременные сбои сети.
     */
    private suspend fun fetchJsonWithRetry(url: String): JSONObject {
        val headers = jsonHeaders()
        return try {
            fetchJson(url, headers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onStage("retry after ${e::class.java.simpleName}: ${e.message.orEmpty().take(60)}")
            delay(RETRY_DELAY_MS)
            fetchJson(url, headers)
        }
    }

    private fun fetchJson(url: String, headers: Map<String, String>): JSONObject {
        val response = http.get(url, headers)
        if (!response.isSuccess) {
            throw CvhResolveException("HTTP ${response.statusCode}: ${response.body.take(80)}")
        }
        return JSONObject(response.body)
    }

    private fun jsonHeaders(): Map<String, String> = mapOf(
        "Referer" to REFERER,
        "Accept" to "application/json",
    )

    private fun isCvhPlayerUrl(url: String): Boolean {
        val normalized = url.lowercase()
        return "iframecvh" in normalized || normalized == "cvh"
    }

    companion object {
        /** UA проверяемого профиля Windows Chrome (как `BrowserUserAgentProfile.WINDOWS_CHROME`). */
        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/149.0.0.0 Safari/537.36"

        private const val PLAYLIST_URL = "https://plapi.cdnvideohub.com/api/v1/player/sv/playlist"
        private const val VIDEO_URL = "https://plapi.cdnvideohub.com/api/v1/player/sv/video"
        private const val PUBLISHER_ID = "745"
        private const val AGGREGATOR = "mali"
        private const val REFERER = "https://ru.yummyani.me/"
        private const val RETRY_DELAY_MS = 700L
    }
}
