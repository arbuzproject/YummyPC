package su.afk.yummy.pc.extractors

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 *
 *
 */
class KodikResolver(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val onStage: (String) -> Unit = {},
) : StreamResolver {

    private val endpointPathByScriptUrl = ConcurrentHashMap<String, String>()
    private val http = KodikHttpClient(userAgent)

    override fun supports(iframeUrl: String): Boolean {
        val normalized = iframeUrl.lowercase()
        return "kodik" in normalized || "kodikplayer" in normalized
    }
    override suspend fun resolve(iframeUrl: String): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        try {
            Result.success(extract(iframeUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: KodikBlockedException) {
            Result.failure(e)
        } catch (e: KodikResolveException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                KodikResolveException("${e::class.java.simpleName}: ${e.message.orEmpty()}", e),
            )
        }
    }

    private suspend fun extract(iframeUrl: String): ResolvedStream {
        val fullUrl = KodikParsing.normalizeIframeUrl(iframeUrl)

        onStage("GET iframe page")
        val html = requirePage(fullUrl, referer = KodikParsing.YANI_REFERER)
        val flat = KodikParsing.flattenHtml(html)

        val urlParams = KodikParsing.URL_PARAMS_REGEX.find(flat)?.groupValues?.get(1)
            ?: throw KodikResolveException("urlParams were not found in iframe page")
        val type = KodikParsing.VIDEO_TYPE_REGEX.find(flat)?.groupValues?.get(1)
            ?: throw KodikResolveException("video type was not found in iframe page")
        val hash = KodikParsing.VIDEO_HASH_REGEX.find(flat)?.groupValues?.get(1)
            ?: throw KodikResolveException("video hash was not found in iframe page")
        val id = KodikParsing.VIDEO_ID_REGEX.find(flat)?.groupValues?.get(1)
            ?: throw KodikResolveException("video id was not found in iframe page")

        val playerSrc = KodikParsing.PLAYER_SCRIPT_REGEX.find(flat)?.groupValues?.get(1)
            ?: throw KodikResolveException("player script URL was not found in iframe page")

        val pageOrigin = KodikParsing.originOf(fullUrl)
        val playerScriptUrl = KodikParsing.absolutePlayerScriptUrl(playerSrc, pageOrigin)
        val endpointOrigin = KodikParsing.originOfPlayerScript(playerScriptUrl)

        val endpointPath = endpointPathByScriptUrl[playerScriptUrl]
            ?: fetchEndpointPath(playerScriptUrl, referer = fullUrl)
                ?.also { endpointPathByScriptUrl[playerScriptUrl] = it }
            ?: KodikParsing.DEFAULT_ENDPOINT_PATH
        val endpointUrl = "$endpointOrigin$endpointPath"

        onStage("POST endpoint $endpointPath")
        val postBody = KodikParsing.buildFtorBody(urlParams, type, hash, id)
        val response = requestEndpoint(endpointUrl, postBody, referer = fullUrl, playerScriptUrl = playerScriptUrl)

        val qualityLinks = try {
            KodikParsing.parseQualityLinks(response.body)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        if (qualityLinks.isEmpty()) {
            endpointPathByScriptUrl.remove(playerScriptUrl)
            throw KodikResolveException(
                "no stream URL in endpoint response: HTTP ${response.statusCode}, " +
                    "${response.body.length} chars" +
                    KodikParsing.errorBodyHint(response.statusCode, response.body),
            )
        }

        onStage("check quality manifests")
        val resolved = coroutineScope {
            qualityLinks.map { (expectedQuality, streamUrl) ->
                async { resolveQualityStream(expectedQuality, streamUrl) }
            }.awaitAll()
        }
        val qualities = LinkedHashMap<String, String>()
        resolved.forEach { qualities.putIfAbsent(it.label, it.url) }
        if (qualities.isEmpty()) throw KodikResolveException("no playable qualities resolved")

        return ResolvedStream(
            url = qualities.values.last(),
            headers = mapOf(KodikParsing.USER_AGENT_HEADER to userAgent),
            qualities = qualities.entries.map { it.key to it.value },
        )
    }

    private fun fetchEndpointPath(playerScriptUrl: String, referer: String): String? {
        onStage("GET player script")
        val playerScript = http.get(playerScriptUrl, headers = browserHeaders(referer)).body
        return KodikParsing.decodeEndpointPaths(playerScript).firstOrNull()
    }

    /**
     */
    private suspend fun requestEndpoint(
        endpointUrl: String,
        postBody: String,
        referer: String,
        playerScriptUrl: String,
    ): KodikHttpResponse {
        fun requestOnce(): KodikHttpResponse = try {
            http.postForm(endpointUrl, postBody, headers = postFormHeaders(referer))
        } catch (e: Exception) {
            endpointPathByScriptUrl.remove(playerScriptUrl)
            throw e
        }

        var response = requestOnce()
        if (response.statusCode >= HTTP_SERVER_ERROR) {
            onStage("endpoint HTTP ${response.statusCode}, retrying in ${SERVER_ERROR_RETRY_DELAY_MS}ms")
            delay(SERVER_ERROR_RETRY_DELAY_MS)
            response = requestOnce()
        }
        return response
    }

    private suspend fun resolveQualityStream(expectedQuality: Int, url: String): QualityStream {
        val actualQuality = KodikParsing.hlsManifestQuality(url)
            ?: return QualityStream(KodikParsing.qualityLabel(expectedQuality), url)
        if (actualQuality == expectedQuality) {
            return QualityStream(KodikParsing.qualityLabel(expectedQuality), url)
        }

        if (expectedQuality > actualQuality) {
            val repairedUrl = KodikParsing.replaceHlsManifestQuality(url, expectedQuality)
            if (repairedUrl != url && isUrlAvailable(repairedUrl)) {
                return QualityStream(KodikParsing.qualityLabel(expectedQuality), repairedUrl)
            }
        }

        return QualityStream(KodikParsing.qualityLabel(actualQuality), url)
    }

    private fun isUrlAvailable(url: String): Boolean = try {
        http.head(url, headers = mapOf(KodikParsing.USER_AGENT_HEADER to userAgent)).isSuccess
    } catch (_: Exception) {
        false
    }

    private fun requirePage(url: String, referer: String): String {
        val response = http.get(url, headers = browserHeaders(referer))
        if (!response.isSuccess) {
            throw KodikBlockedException(
                statusCode = response.statusCode,
                message = KodikParsing.parseBlockedMessage(response.body),
            )
        }
        return response.body
    }

    private fun browserHeaders(referer: String): Map<String, String> = mapOf(
        "Referer" to referer,
        KodikParsing.USER_AGENT_HEADER to userAgent,
        "Accept-Language" to KodikParsing.RU_ACCEPT_LANGUAGE,
    )

    private fun postFormHeaders(referer: String): Map<String, String> = mapOf(
        "Referer" to referer,
        KodikParsing.USER_AGENT_HEADER to userAgent,
        "Content-Type" to "application/x-www-form-urlencoded",
        "X-Requested-With" to "XMLHttpRequest",
    )

    private data class QualityStream(
        val label: String,
        val url: String,
    )

    companion object {
        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/149.0.0.0 Safari/537.36"

        private const val HTTP_SERVER_ERROR = 500
        private const val SERVER_ERROR_RETRY_DELAY_MS = 700L
    }
}
