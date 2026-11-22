// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.zedfilm

import org.json.JSONObject
import java.net.URL
import java.util.Base64

/**
 * Чистые шаги разбора Zedfilm: `video_Init('<base64 JSON>')` в HTML, поиск ссылок на поток,
 * нормализация экранированных URL, качество из текста ссылки, отбрасывание рекламных URL.
 * Вынесены из [ZedfilmResolver], чтобы покрывать их тестами без сети.
 *
 * Логика 1:1 повторяет `ZedfilmExtractor` из YummyTV (модуль source/zedfilm); `android.util.Base64`
 * заменён на `java.util.Base64`.
 */
internal object ZedfilmParsing {

    const val ZEDFILM_ORIGIN = "https://zedfilm.ru"
    const val HLAMER_ORIGIN = "https://hlamer.ru"
    const val YANI_REFERER = "https://yani.tv/"
    const val RU_ACCEPT_LANGUAGE = "ru-RU,ru;q=0.9,en;q=0.8"
    const val USER_AGENT_HEADER = "User-Agent"

    /** Порядок качеств, как в `STANDARD_QUALITY_KEYS` Android-версии. */
    val STANDARD_QUALITY_KEYS =
        listOf("auto", "144p", "240p", "360p", "480p", "720p", "1080p", "1440p", "2160p")

    val STREAM_URL_PATTERNS = listOf(
        Regex("""(?i)https?:\\?/\\?/[^"'\s<>]+\.(?:mpd|m3u8|mp4)[^"'\s<>]*"""),
        Regex("""(?i)//[^"'\s<>]+\.(?:mpd|m3u8|mp4)[^"'\s<>]*"""),
        Regex("""(?i)\b(?:file|src|source|url|hls|dash)\b\s*[:=]\s*['"]([^'"]+\.(?:mpd|m3u8|mp4)[^'"]*)['"]"""),
        Regex("""(?i)<source[^>]+src=['"]([^'"]+\.(?:mpd|m3u8|mp4)[^'"]*)['"]"""),
    )

    /** Zedfilm кладёт метаданные потока как `video_Init('eyJ2X2lkIj...')`: base64 от JSON. */
    val VIDEO_INIT_PATTERN = Regex("""(?i)video_Init\(\s*['"]([^'"]+)['"]""")

    /** Длинные значения первыми и `(?!\d)` после числа: иначе `1440` читалось бы как `144`. */
    val QUALITY_FROM_TEXT = Regex("""(?i)(?<!\d)(2160|1440|1080|720|480|360|240|144)(?!\d)p?""")

    /** Хост отбрасывается при поиске качества: `s240.cdn.example` — это не качество. */
    val URL_ORIGIN_PATTERN = Regex("""^(?:https?:)?//[^/?#]+""")

    val AD_URL_TOKENS = setOf("ads", "ima")
    val AD_HOST_MARKERS = listOf("doubleclick", "yandex")

    fun isZedfilmPlayerUrl(url: String): Boolean {
        val normalized = url.lowercase()
        return "zedfilm.ru" in normalized || "hlamer.ru" in normalized
    }

    /**
     * `video_Init(...)` -> JSON с полями `url` (основной DASH) и `url2` (mp4-фолбэк).
     * null — полезной нагрузки нет, base64/JSON битые.
     */
    fun decodeVideoInit(html: String): JSONObject? {
        val encoded = VIDEO_INIT_PATTERN.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val json = runCatching {
            String(decodeBase64(encoded), Charsets.UTF_8)
        }.getOrNull() ?: return null

        return runCatching { JSONObject(json) }.getOrNull()
    }

    /** Android `Base64.DEFAULT` терпит переводы строк — пробуем обычный декодер, затем MIME. */
    private fun decodeBase64(encoded: String): ByteArray = runCatching {
        Base64.getDecoder().decode(encoded)
    }.getOrElse {
        Base64.getMimeDecoder().decode(encoded)
    }

    /** Все кандидаты страницы: `video_Init` первым, затем ссылки из разметки/скриптов. */
    fun collectCandidates(html: String, baseUrl: String): LinkedHashMap<String, String> {
        val candidates = LinkedHashMap<String, String>()
        val payload = normalizePayload(html)

        decodeVideoInit(payload)?.let { data ->
            videoInitUrl(data, "url", baseUrl)?.let { candidates[qualityLabelFromText(it)] = it }
                ?: videoInitUrl(data, "url2", baseUrl)?.let { candidates[qualityLabelFromText(it)] = it }
        }

        STREAM_URL_PATTERNS.forEach { pattern ->
            pattern.findAll(payload).forEach { match ->
                val raw = match.groupValues.getOrNull(1)
                    ?.takeIf { it.isNotBlank() }
                    ?: match.value
                val url = normalizeEscapedUrl(normalizeUrl(raw, baseUrl))
                if (isStreamUrl(url)) {
                    candidates[qualityLabelFromText(raw)] = url
                }
            }
        }
        return candidates
    }

    /** `url` (основной DASH-поток) или `url2` (mp4-фолбэк), как в плеере Zedfilm. */
    private fun videoInitUrl(data: JSONObject, key: String, baseUrl: String): String? =
        data.optString(key)
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?.let { normalizeUrl(it, baseUrl) }
            ?.takeIf(::isStreamUrl)

    fun isStreamUrl(url: String): Boolean {
        val lowered = url.lowercase()
        val isMedia = lowered.contains(".mpd") || lowered.contains(".m3u8") || lowered.contains(".mp4")
        if (!isMedia) return false
        if (AD_HOST_MARKERS.any(lowered::contains)) return false
        // Рекламные маркеры ищем целыми словами: подстрока «ads»/«ima» режет `uploads`,
        // `animation` и `prima`, то есть вполне нормальные ссылки.
        return lowered.split(Regex("[^a-z0-9]+")).none { it in AD_URL_TOKENS }
    }

    /** Качество из текста ссылки (`720.mp4` -> `720p`); неизвестное — `auto`. */
    fun qualityLabelFromText(text: String): String =
        QUALITY_FROM_TEXT.find(text.replace(URL_ORIGIN_PATTERN, ""))
            ?.groupValues
            ?.getOrNull(1)
            ?.let { "${it}p" }
            ?: "auto"

    fun normalizePayload(payload: String): String =
        payload
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")

    fun normalizeEscapedUrl(url: String): String =
        normalizePayload(url).trim().trim('"').trim('\'')

    fun normalizeUrl(url: String, baseUrl: String = ""): String {
        val trimmed = normalizeEscapedUrl(url)
        if (trimmed.isBlank()) return ""

        return when {
            hasKnownUrlScheme(trimmed) -> normalizeUrlScheme(trimmed)
            trimmed.startsWith("/") -> "$HLAMER_ORIGIN$trimmed"
            else -> resolveRelativeUrl(trimmed, baseUrl) { "$ZEDFILM_ORIGIN/$trimmed" }
        }
    }

    /** Стандартный порядок качеств; «auto» переименовывается в [autoQualityLabel], если задана. */
    fun orderQualityMap(
        raw: LinkedHashMap<String, String>,
        autoQualityLabel: String = "auto",
    ): LinkedHashMap<String, String> {
        val ordered = LinkedHashMap<String, String>()
        STANDARD_QUALITY_KEYS.forEach { key ->
            raw[key]?.let { ordered[key] = it }
        }
        raw.forEach { (key, value) -> if (!ordered.containsKey(key)) ordered[key] = value }

        if (autoQualityLabel.isBlank() || autoQualityLabel == "auto") return ordered
        val relabeled = LinkedHashMap<String, String>()
        ordered.forEach { (quality, url) ->
            relabeled[if (quality == "auto") autoQualityLabel else quality] = url
        }
        return relabeled
    }

    private fun hasKnownUrlScheme(url: String): Boolean =
        url.startsWith("//") || url.startsWith("http://") || url.startsWith("https://")

    private fun normalizeUrlScheme(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> url.replaceFirst("http://", "https://")
        else -> url
    }

    private fun resolveRelativeUrl(raw: String, baseUrl: String, fallback: () -> String): String =
        if (baseUrl.isBlank()) {
            fallback()
        } else {
            runCatching { URL(URL(baseUrl), raw).toString() }.getOrElse { fallback() }
        }
}
