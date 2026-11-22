// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.vk

import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Ответ HTTP-слоя VK-экстрактора. Тело хранится байтами: страница `video_ext.php` приходит в
 * windows-1251, и текст сообщения VK («Видеофайл не найден») читается только из сырых байтов —
 * Android-версия по той же причине декодировала тело дважды.
 */
internal class VkHttpResponse(
    val statusCode: Int,
    val bodyBytes: ByteArray,
    val headers: Map<String, List<String>>,
) {
    val isSuccess: Boolean get() = statusCode in 200..299

    fun text(charset: Charset = Charsets.UTF_8): String = String(bodyBytes, charset)
}

/**
 * Минимальный HTTP-клиент VK-экстрактора на OkHttp: GET с браузерными заголовками, тело в памяти.
 *
 * Отдельный от [su.afk.yummy.pc.extractors.KodikHttpClient], потому что VK нужны сырые байты и
 * разбор страницы в windows-1251; cookie-jar VK не требует.
 */
internal class VkHttpClient(private val userAgent: String) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): VkHttpResponse {
        val mergedHeaders = if (headers.keys.any { it.equals(VkParsing.USER_AGENT_HEADER, ignoreCase = true) }) {
            headers
        } else {
            headers + (VkParsing.USER_AGENT_HEADER to userAgent)
        }

        val requestBuilder = Request.Builder().url(url)
        mergedHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }

        client.newCall(requestBuilder.get().build()).execute().use { response ->
            return VkHttpResponse(
                statusCode = response.code,
                bodyBytes = response.body.bytes(),
                headers = response.headers.toMultimap(),
            )
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
        const val CALL_TIMEOUT_SECONDS = 90L
    }
}
