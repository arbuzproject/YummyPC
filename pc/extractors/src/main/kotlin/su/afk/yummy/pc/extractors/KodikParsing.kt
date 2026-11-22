// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/**
 * Чистые шаги разбора Kodik: регулярки страницы/скрипта, декодирование `atob(...)` и ROT18+base64,
 * сборка form-urlencoded тела `/ftor`, разбор `links` из ответа. Вынесены из [KodikResolver], чтобы
 * покрывать их тестами без сети.
 *
 * Логика 1:1 повторяет `KodikExtractor` из YummyTV (`source/kodik/data/.../KodikExtractor.kt`).
 */
internal object KodikParsing {

    /** `var urlParams = '{"d":...}';` — подписанные параметры iframe-страницы. */
    val URL_PARAMS_REGEX = Regex("""\burlParams\s*=\s*'([^']+)'""")

    /** `videoInfo.type`/`vInfo.type` (страница отдаёт сокращённый `vInfo`). */
    val VIDEO_TYPE_REGEX = Regex("""\b(?:videoInfo|vInfo)\.type\s*=\s*'([^']+)'""")
    val VIDEO_HASH_REGEX = Regex("""\b(?:videoInfo|vInfo)\.hash\s*=\s*'([^']+)'""")
    val VIDEO_ID_REGEX = Regex("""\b(?:videoInfo|vInfo)\.id\s*=\s*'([^']+)'""")

    /** `<script src="/assets/js/app.player_single.<hash>.js">` — скрипт плеера с путём эндпоинта. */
    val PLAYER_SCRIPT_REGEX = Regex("""src="((?://[^"]+)?/assets/js/app\.player_single[^"]+)"""")

    /** `atob("L2Z0b3I=")` — путь эндпоинта в base64. */
    val ATOB_REGEX = Regex("""atob\("([A-Za-z0-9+/=]+)"\)""")

    /** `/720.mp4:hls:manifest.m3u8` — реальное качество в URL манифеста. */
    val HLS_QUALITY_MANIFEST_REGEX = Regex("""/(\d+)\.mp4:hls:manifest\.m3u8(?=$|[?#])""")

    /** `<div class="message">Видео не найдено</div>` — текст ошибки Kodik. */
    val BLOCKED_MESSAGE_REGEX = Regex("""<div[^>]+class=["']message["'][^>]*>([^<]+)<""")
    val HTML_TAG_REGEX = Regex("<[^>]+>")
    val WHITESPACE_REGEX = Regex("\\s+")

    /** Порядок качеств, который перебирает Kodik в ответе `/ftor`. */
    val QUALITY_ORDER = listOf(240, 360, 480, 720, 1080)

    /** Запасной путь эндпоинта: его отдают почти все зеркала Kodik. */
    const val DEFAULT_ENDPOINT_PATH = "/ftor"

    const val YANI_REFERER = "https://yani.tv/"
    const val RU_ACCEPT_LANGUAGE = "ru-RU,ru;q=0.9,en;q=0.8"
    const val USER_AGENT_HEADER = "User-Agent"
    const val ERROR_BODY_HINT_LENGTH = 80

    /** `//host/path` и голый `host/path` -> абсолютный https-URL (как в Android-версии). */
    fun normalizeIframeUrl(iframeUrl: String): String = when {
        iframeUrl.startsWith("//") -> "https:$iframeUrl"
        iframeUrl.startsWith("http") -> iframeUrl
        else -> "https://$iframeUrl"
    }

    /** Склеенная в одну строку HTML-страница: регулярки рассчитаны на отсутствие переводов строк. */
    fun flattenHtml(html: String): String = html.replace("\n", "").replace("\r", "")

    /** `https://host:port/path?query` -> `https://host:port`. */
    fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val afterScheme = url.substring(schemeEnd + 3)
        val slashIndex = afterScheme.indexOf('/')
        return if (slashIndex >= 0) url.substring(0, schemeEnd + 3 + slashIndex) else url
    }

    /** Относительный `src` скрипта плеера -> абсолютный URL. */
    fun absolutePlayerScriptUrl(playerSrc: String, pageOrigin: String): String = when {
        playerSrc.startsWith("//") -> "https:$playerSrc"
        playerSrc.startsWith("/") -> "$pageOrigin$playerSrc"
        else -> playerSrc
    }

    /** Origin, от которого строится URL эндпоинта (`https://kodikplayer.com`). */
    fun originOfPlayerScript(playerScriptUrl: String): String =
        playerScriptUrl.substringBefore("/assets/js/")

    /**
     * Все осмысленные пути эндпоинта из `atob("...")` скрипта: короткие (`/ftor`), начинающиеся с
     * `/`, но не протокол-относительные (`//host`).
     */
    fun decodeEndpointPaths(playerScript: String): List<String> =
        ATOB_REGEX.findAll(playerScript)
            .mapNotNull { match -> decodeBase64OrNull(match.groupValues[1]) }
            .filter { it.startsWith("/") && !it.startsWith("//") && it.length <= 10 }
            .toList()

    fun decodeBase64OrNull(value: String): String? = try {
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * `src` из `/ftor` без `//` закодирован ROT18 по буквам и затем base64. Пустое/битое значение
     * даёт `null` — качество пропускается.
     */
    fun decodeKodikSrc(src: String): String? = try {
        val rotated = buildString(src.length) {
            src.forEach { c ->
                if (c.isLetter()) {
                    val shifted = c.code + 18
                    val limit = if (c <= 'Z') 'Z'.code else 'z'.code
                    append(if (shifted <= limit) shifted.toChar() else (shifted - 26).toChar())
                } else {
                    append(c)
                }
            }
        }
        val padded = rotated + "=".repeat((4 - rotated.length % 4) % 4)
        String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    /** `//cdn/...` -> `https://cdn/...`; уже абсолютный URL не меняется. */
    fun fixProtocol(src: String): String = when {
        src.startsWith("//") -> "https:$src"
        src.startsWith("http") -> src
        else -> "https://$src"
    }

    fun hlsManifestQuality(url: String): Int? =
        HLS_QUALITY_MANIFEST_REGEX.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun replaceHlsManifestQuality(url: String, quality: Int): String {
        val match = HLS_QUALITY_MANIFEST_REGEX.find(url) ?: return url
        return url.replaceRange(match.range, "/$quality.mp4:hls:manifest.m3u8")
    }

    fun qualityLabel(quality: Int): String = "${quality}p"

    fun parseBlockedMessage(html: String): String? =
        BLOCKED_MESSAGE_REGEX.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    /** Короткий ASCII-безопасный фрагмент тела ошибки для диагностики (без разметки). */
    fun errorBodyHint(statusCode: Int, body: String): String {
        if (statusCode < 400) return ""
        val text = body.replace(HTML_TAG_REGEX, " ").replace(WHITESPACE_REGEX, " ").trim()
            .take(ERROR_BODY_HINT_LENGTH)
        return if (text.isEmpty()) "" else ": \"$text\""
    }

    fun encodeForm(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    /**
     * Тело POST `/ftor`. `ref` в `urlParams` уже URL-encoded, поэтому подставляется как есть;
     * остальные значения кодируются (как `enc()` в Android-версии).
     */
    fun buildFtorBody(urlParamsJson: String, type: String, hash: String, id: String): String {
        val urlParams = JSONObject(urlParamsJson)
        return buildString {
            append("d=").append(encodeForm(urlParams.optString("d")))
            append("&d_sign=").append(encodeForm(urlParams.optString("d_sign")))
            append("&pd=").append(encodeForm(urlParams.optString("pd")))
            append("&pd_sign=").append(encodeForm(urlParams.optString("pd_sign")))
            append("&ref=").append(urlParams.optString("ref"))
            append("&ref_sign=").append(encodeForm(urlParams.optString("ref_sign")))
            append("&bad_user=true")
            append("&cdn_is_working=true")
            append("&type=").append(encodeForm(type))
            append("&hash=").append(encodeForm(hash))
            append("&id=").append(encodeForm(id))
            append("&info=%7B%7D")
        }
    }

    /**
     * Ответ `/ftor` -> качества по возрастанию: ожидаемое качество -> прямой URL, уже с `https:`.
     * Это ещё «сырые» ссылки: реальное качество в URL манифеста проверяет [KodikResolver].
     */
    fun parseQualityLinks(responseJson: String): List<Pair<Int, String>> {
        val links = JSONObject(responseJson).optJSONObject("links") ?: return emptyList()
        return QUALITY_ORDER.mapNotNull { quality ->
            val src = links.optJSONArray(quality.toString())
                ?.optJSONObject(0)
                ?.optString("src")
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val decoded = if (src.contains("//")) src else decodeKodikSrc(src) ?: return@mapNotNull null
            quality to fixProtocol(decoded)
        }
    }
}
