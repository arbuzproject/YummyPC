// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Ответ HTTP-слоя экстрактора. Тело читается в память: страницы/скрипт/JSON Kodik — единицы-сотни
 * КБ, для плейлиста читается только префикс ([KodikHttpClient.readPrefix]).
 */
internal class KodikHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>>,
) {
    val isSuccess: Boolean get() = statusCode in 200..299
}

/**
 * Минимальный HTTP-клиент экстрактора на OkHttp.
 *
 * В отличие от Android-оригинала (Ktor-клиент без cookie-jar, где `Set-Cookie` вручную переносился
 * в POST), здесь cookie-jar ведёт OkHttp: DDoS-Guard Kodik ставит `__ddg1_`/`__ddg8_`/`__ddg9_`/
 * `__ddg10_` на страницу, и они автоматически уходят в запрос скрипта и POST `/ftor`.
 */
internal class KodikHttpClient(private val userAgent: String) {

    private val cookieJar = MemoryCookieJar()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): KodikHttpResponse =
        execute(method = "GET", url = url, headers = headers)

    fun head(url: String, headers: Map<String, String> = emptyMap()): KodikHttpResponse =
        execute(method = "HEAD", url = url, headers = headers)

    fun postForm(
        url: String,
        formBody: String,
        headers: Map<String, String> = emptyMap(),
    ): KodikHttpResponse = execute(
        method = "POST",
        url = url,
        headers = headers,
        body = formBody,
    )

    /** GET, который читает только первые [maxBytes] байт тела (проверка `#EXTM3U` у плейлиста). */
    fun readPrefix(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = DEFAULT_PREFIX_BYTES,
    ): KodikHttpResponse = execute(
        method = "GET",
        url = url,
        headers = headers,
        prefixLimit = maxBytes,
    )

    private fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String? = null,
        prefixLimit: Int? = null,
    ): KodikHttpResponse {
        val mergedHeaders = if (headers.keys.any { it.equals(KodikParsing.USER_AGENT_HEADER, ignoreCase = true) }) {
            headers
        } else {
            headers + (KodikParsing.USER_AGENT_HEADER to userAgent)
        }

        val requestBuilder = Request.Builder().url(url)
        mergedHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }
        val request = when (method) {
            "HEAD" -> requestBuilder.head().build()
            "POST" -> requestBuilder.post(body.orEmpty().toRequestBody(FORM_MEDIA_TYPE)).build()
            else -> requestBuilder.get().build()
        }

        client.newCall(request).execute().use { response ->
            val responseBody = when {
                method == "HEAD" -> ""
                prefixLimit != null -> response.body.byteStream().use { stream ->
                    String(readUpTo(stream, prefixLimit), Charsets.UTF_8)
                }
                else -> response.body.string()
            }
            return KodikHttpResponse(
                statusCode = response.code,
                body = responseBody,
                headers = response.headers.toMultimap(),
            )
        }
    }

    private fun readUpTo(stream: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val read = stream.read(buffer, total, limit - total)
            if (read < 0) break
            total += read
        }
        return buffer.copyOf(total)
    }

    /** Cookie-jar на процесс: домены/пути/срок берутся из `Set-Cookie` от Kodik и его CDN. */
    private class MemoryCookieJar : CookieJar {
        private val store = CopyOnWriteArrayList<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                store.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) store.add(cookie)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            store.removeAll { it.expiresAt <= now }
            return store.filter { it.matches(url) }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
        const val CALL_TIMEOUT_SECONDS = 90L
        const val DEFAULT_PREFIX_BYTES = 4096
        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
    }
}
