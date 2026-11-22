// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.zedfilm

import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/** Ответ HTTP-слоя Zedfilm: HTML в windows-1251, поэтому тело хранится байтами. */
internal class ZedfilmHttpResponse(
    val statusCode: Int,
    val bodyBytes: ByteArray,
) {
    val isSuccess: Boolean get() = statusCode in 200..299

    fun text(charset: Charset = Charsets.UTF_8): String = String(bodyBytes, charset)
}

/**
 * Минимальный HTTP-клиент Zedfilm-экстрактора на OkHttp.
 *
 * `Accept-Encoding: gzip` вручную не выставляется, в отличие от Android-версии: OkHttp сам
 * добавляет `gzip` при прозрачном сжатии и декодирует ответ, а его ручная установка отключила бы
 * декодирование (в Android-версии сжатие разбирал Ktor ContentEncoding). Для сайта важен сам факт
 * заголовка — без него Zedfilm отвечает на iframe 404 «video not found».
 */
internal class ZedfilmHttpClient(private val userAgent: String) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): ZedfilmHttpResponse {
        val mergedHeaders = if (headers.keys.any { it.equals(USER_AGENT_HEADER, ignoreCase = true) }) {
            headers
        } else {
            headers + (USER_AGENT_HEADER to userAgent)
        }

        val requestBuilder = Request.Builder().url(url)
        mergedHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }

        client.newCall(requestBuilder.get().build()).execute().use { response ->
            return ZedfilmHttpResponse(
                statusCode = response.code,
                bodyBytes = response.body.bytes(),
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
