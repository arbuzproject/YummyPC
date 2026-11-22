// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Открывает сессию Alloha обычным HTTP, без браузера: качает страницу плеера, читает её зерно
 * `viewporti`, подписывает запрос `POST /bnsi/movies/{id}` заголовком `Borth` и разбирает лесенки
 * озвучек из ответа. Ссылки из ответа играют как есть, поэтому страницу не нужно держать живой и
 * ничего ротировать: переставшая работать сессия заменяется новой.
 *
 * Блокирующий: вызывать с IO-диспетчера. Порт `AllohaHttpSessionOpener.kt` из Android-версии.
 */
internal class AllohaHttpSessionOpener(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val fingerprint: String,
    private val onStage: (String) -> Unit = {},
) {
    /** Клиент проб зеркал: короткий `callTimeout`, чтобы лежачий CDN не держал resolve. */
    private val probeClient: OkHttpClient = client.newBuilder()
        .callTimeout(MIRROR_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /** Ключ `Authorizations` кэшируется по URL скрипта: имя скрипта несёт хеш содержимого. */
    private val apiKeyCache = ConcurrentHashMap<String, String>()

    @Throws(IOException::class)
    fun open(iframeUrl: String): AllohaOpenedSession {
        onStage("GET player page")
        val page = fetchPage(iframeUrl)
        val viewporti = AllohaPageParsing.parseViewporti(page)
            ?: throw IOException("Alloha: viewporti seed is missing")
        val token = AllohaPageParsing.iframeToken(iframeUrl)
            ?: throw IOException("Alloha: token is missing in iframe url")
        val activeId = AllohaPageParsing.parseActiveId(page)
            ?: throw IOException("Alloha: active media id is missing")

        val origin = AllohaPageParsing.originOf(iframeUrl)
        val headers = allohaCdnHeaders(origin, userAgent) + authorizationHeader(origin, page, iframeUrl)
        onStage("POST bnsi/movies/$activeId")
        val bnsi = fetchBnsi(origin, iframeUrl, activeId, token, allohaBorthHeader(fingerprint, viewporti))
        val picker = AllohaMirrorPicker { url -> isReachable(url, headers) }
        val sources = try {
            parseSources(bnsi) { mirrors ->
                picker.pick(mirrors).also { picked ->
                    onStage("mirrors=${mirrors.map(::hostOf)} picked=${hostOf(picked)}")
                }
            }
        } catch (error: JSONException) {
            throw IOException("Alloha: bnsi is not valid json", error)
        }
        onStage(
            "bnsi audioTracks=${sources.audioTracks.map(AllohaAudioTrack::label)} " +
                "subtitles=${sources.subtitles.size}",
        )
        return AllohaOpenedSession(
            sources = sources,
            headers = headers,
            socketTicket = AllohaPageParsing.parseSocketTicket(bnsi),
            viewTicket = AllohaPageParsing.parseViewTicket(page, activeId),
        )
    }

    /**
     * Отдаёт ли CDN [url] запросу, который выглядит как запрос плеера. Плейлист — несколько сотен байт,
     * читается только статус, поэтому зеркало, отвечающее 403 или не ответившее за
     * [MIRROR_PROBE_TIMEOUT_MS], пропускается.
     */
    private fun isReachable(url: String, headers: Map<String, String>): Boolean = try {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        probeClient.newCall(request).execute().use { it.isSuccessful }
    } catch (error: IOException) {
        false
    }.also { reachable ->
        if (!reachable) onStage("mirror is not reachable, using the next one host=${hostOf(url)}")
    }

    /**
     * В скрипте сайта лежит длинная константа, которую сайт шлёт к CDN как
     * `Authorizations: Bearer <ключ>` на каждый медиазапрос рядом с `Accepts-Controls`. Имя скрипта
     * несёт хеш содержимого, поэтому ключ читается один раз на скрипт.
     */
    private fun authorizationHeader(origin: String, page: String, iframeUrl: String): Map<String, String> {
        val path = AllohaPageParsing.parseAppScriptPath(page) ?: return emptyMap()
        val scriptUrl = AllohaPageParsing.absoluteScriptUrl(path, origin)
        val key = apiKeyCache[scriptUrl] ?: run {
            val request = Request.Builder().url(scriptUrl)
                .header("User-Agent", userAgent)
                .header("Referer", iframeUrl)
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .build()
            val body = try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body.string() else null
                }
            } catch (error: IOException) {
                null
            }
            body?.let(AllohaPageParsing::parseApiKey)?.also { apiKeyCache[scriptUrl] = it }
        }
        onStage("authorization key present=${key != null}")
        return key?.let { mapOf("authorizations" to "Bearer $it") }.orEmpty()
    }

    private fun fetchPage(iframeUrl: String): String {
        val request = Request.Builder().url(iframeUrl)
            .header("Referer", PAGE_REFERER)
            .header("User-Agent", userAgent)
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .build()
        return client.newCall(request).execute().use { response ->
            if (response.code == HTTP_NOT_FOUND) {
                // 404 со заглушкой «Ошибка!» — это ответ Alloha «такой серии/озвучки/сезона у нас нет»
                // (список видео у yani устарел), а не сбой: повтор того же адреса вернёт то же самое.
                val reason = runCatching { response.body.string() }.getOrNull()?.let(::allohaErrorPageReason)
                throw AllohaSourceUnavailableException("player page HTTP 404" + reason?.let { ": $it" }.orEmpty())
            }
            if (!response.isSuccessful) throw IOException("Alloha: player page HTTP ${response.code}")
            response.body.string()
        }
    }

    private fun fetchBnsi(
        origin: String,
        iframeUrl: String,
        activeId: Long,
        token: String,
        borth: String,
    ): String {
        val body = FormBody.Builder()
            .add("token", token)
            .add("av1", "false")
            .add("autoplay", "0")
            .add("audio", "")
            .add("subtitle", "")
            .build()
        val request = Request.Builder().url("$origin/bnsi/movies/$activeId")
            .post(body)
            .header("User-Agent", userAgent)
            .header("Borth", borth)
            .header("Referer", iframeUrl)
            .header("Origin", origin)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Alloha: bnsi HTTP ${response.code}")
            response.body.string().also {
                if (it.isBlank()) throw IOException("Alloha: bnsi answered with an empty body")
            }
        }
    }

    private fun hostOf(url: String): String =
        runCatching { url.toHttpUrlHost() }.getOrDefault("unknown")

    private companion object {
        const val ACCEPT_LANGUAGE = "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
        const val MIRROR_PROBE_TIMEOUT_MS = 4_000L
        const val HTTP_NOT_FOUND = 404

        /** Страница отдаётся только тому, кто пришёл с сайта, который её встраивает. */
        const val PAGE_REFERER = "https://ru.yummyani.me/"
    }
}
