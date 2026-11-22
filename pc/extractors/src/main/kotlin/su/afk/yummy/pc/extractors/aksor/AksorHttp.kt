// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.aksor

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Ответ HTTP-слоя Aksor: JSON player API и HTML страницы плеера (скрипты/мета). */
internal class AksorHttpResponse(
    val statusCode: Int,
    val body: String,
) {
    val isSuccess: Boolean get() = statusCode in 200..299
}

/**
 * Минимальный HTTP-клиент Aksor-экстрактора на OkHttp: GET JSON API и HTML/скриптов плеера.
 *
 * Отдельный от [su.afk.yummy.pc.extractors.KodikHttpClient], чтобы пакет балансера был
 * самодостаточным; cookie-jar Aksor не нужен.
 */
internal class AksorHttpClient(private val userAgent: String) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): AksorHttpResponse {
        val mergedHeaders = if (headers.keys.any { it.equals(USER_AGENT_HEADER, ignoreCase = true) }) {
            headers
        } else {
            headers + (USER_AGENT_HEADER to userAgent)
        }

        val requestBuilder = Request.Builder().url(url)
        mergedHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }

        client.newCall(requestBuilder.get().build()).execute().use { response ->
            return AksorHttpResponse(
                statusCode = response.code,
                body = response.body.string(),
            )
        }
    }

    internal companion object {
        const val USER_AGENT_HEADER = "User-Agent"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val CALL_TIMEOUT_SECONDS = 90L
    }
}
