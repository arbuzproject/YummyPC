// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.vk

import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset

/**
 * Чистые шаги разбора VK: нормализация ссылок, поиск iframe-URL `video_ext.php`, вычитка ссылок на
 * поток из HTML/JSON страницы, разбор сообщений VK («Видеофайл не найден», `error_code 204`).
 * Вынесены из [VkResolver], чтобы покрывать их тестами без сети.
 *
 * Логика 1:1 повторяет `VkExtractor`/`VkUtils` из YummyTV (модуль source/vk).
 */
internal object VkParsing {

    /** Страница-обёртка yani, которую открывает iframeVK.html. */
    const val YUMMY_ORIGIN = "https://ru.yummyani.me"

    /** Плеер VK: сюда ведёт `iframeVK.html?id=<owner>_<video>`. */
    const val VIDEO_EXT_URL = "https://vk.com/video_ext.php"

    const val DEFAULT_REFERER = "https://vk.com/"
    const val USER_AGENT_HEADER = "User-Agent"

    /** ASCII-маркер блока с сообщением VK («Видеофайл не найден»): не зависит от кодировки страницы. */
    const val VIDEO_UNAVAILABLE_MARKER = "id=\"video_ext_msg\""

    /** Сколько раз перезапрашивать `video_ext.php`, если VK вернул урезанную страницу без ссылок. */
    const val VIDEO_EXT_MAX_ATTEMPTS = 5

    /** Пауза между повторами `video_ext.php` (VK в половине запросов отдаёт страницу без потоков). */
    const val VIDEO_EXT_RETRY_DELAY_MS = 400L

    /** Кодировка страницы `video_ext.php`: русский текст сообщений VK читается только из неё. */
    val VK_PAGE_CHARSET: Charset = Charset.forName("windows-1251")

    /** Порядок качеств, как в `STANDARD_QUALITY_KEYS` Android-версии. */
    val STANDARD_QUALITY_KEYS =
        listOf("auto", "144p", "240p", "360p", "480p", "720p", "1080p", "1440p", "2160p")

    /** `url1080`/`mp4_1080` и т. п. — именованные поля плеера VK; порядок задаёт порядок качеств. */
    val NAMED_STREAM_PATTERNS = listOf(
        "1080p" to Regex("""(?i)\b(?:url1080|mp4_1080)\b\s*[:=]\s*['"]([^'"]+)['"]"""),
        "720p" to Regex("""(?i)\b(?:url720|mp4_720)\b\s*[:=]\s*['"]([^'"]+)['"]"""),
        "480p" to Regex("""(?i)\b(?:url480|mp4_480)\b\s*[:=]\s*['"]([^'"]+)['"]"""),
        "360p" to Regex("""(?i)\b(?:url360|mp4_360)\b\s*[:=]\s*['"]([^'"]+)['"]"""),
        "240p" to Regex("""(?i)\b(?:url240|mp4_240)\b\s*[:=]\s*['"]([^'"]+)['"]"""),
        "auto" to Regex("""(?i)\burl\b\s*[:=]\s*['"]([^'"]+)['"]"""),
        "auto" to Regex("""(?i)"hls_fmp4"\s*:\s*['"]([^'"]+)['"]"""),
        "auto" to Regex("""(?i)"hls"\s*:\s*['"]([^'"]+)['"]"""),
    )

    /** `"mp4_720": "..."` — ключ хранит качество прямо в поле. */
    val VK_FILE_QUALITY_PATTERNS = listOf(
        Regex("""(?i)"mp4_(\d{3,4})"\s*:\s*['"]([^'"]+)['"]"""),
    )

    /** Блок `"files": {...}, "trailer"` — там лежат все именованные ссылки без внешних скобок. */
    val FILES_BLOCK_PATTERN = Regex("""(?is)"files"\s*:\s*\{(.*?)\}\s*,\s*"trailer"""")

    val FILES_KEY_VALUE_PATTERNS = listOf(
        Regex("""(?i)"(mp4_\d{3,4}|hls_fmp4|hls|url\d{3,4}|url)"\s*:\s*['"]([^'"]+)['"]"""),
    )

    /** `video<owner>_<id>` в тексте/HTML. */
    val VIDEO_PATH_ID_PATTERN = Regex("""(?i)video(?<owner>-?\d+)_(?<id>\d+)""")

    /** `video_ext.php?...&oid=<owner>&id=<video>` в HTML обёртки. */
    val VIDEO_EXT_FROM_IFRAME_PATTERNS = listOf(
        Regex("""video_ext\.php[^"']*?oid=([^&"']+)&id=([^&"']+)""", RegexOption.IGNORE_CASE),
    )

    /** `.../720.mp4` -> 720; хвост `p`/`/` не обязателен. */
    val QUALITY_FROM_URL = Regex("""(?i)(\d{3,4})(?=p?(?:\.mp4|/|$))""")

    /** Прямые ссылки на поток где угодно в тексте страницы. */
    val STREAM_URL_PATTERNS = listOf(
        Regex("""(?i)https?:\/\/[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*"""),
        Regex("""(?i)\/\/[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*"""),
        Regex("""(?i)\b(?:videoUrl|fileList|file|src)\b[^=]*=\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]"""),
    )

    /** `data-video-src` и `<source src>`: у VK бывают в разметке старых страниц. */
    val DATA_SRC_PATTERNS = listOf(
        Regex("""(?i)data-video(?:-src|Src)\s*=\s*['"]([^'"]+)['"]"""),
        Regex("""(?i)<source[^>]+src=['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]"""),
    )

    val PLAIN_SOURCE_PATTERN = Regex("""(?i)<source[^>]+src=['"]([^'"]+)['"]""")

    /** `<div id="video_ext_msg">Видеофайл не найден.</div>` — текст в windows-1251. */
    val VK_MESSAGE_PATTERN = Regex(
        """id="video_ext_msg"[^>]*>(.*?)</div>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /**
     * Новая SPA-страница `video_ext.php` отдаёт ошибку доступа в `apiPrefetchCache`:
     * `{"error_code":204,"error_msg":"Access denied"}` — видео закрыто для анонимного зрителя.
     */
    val VK_ACCESS_DENIED_PATTERN = Regex(""""error_code"\s*:\s*204\b""")

    val HTML_TAG_PATTERN = Regex("<[^>]+>")
    val WHITESPACE_PATTERN = Regex("\\s+")

    /** Фрагмент текста VK, по которому сообщение распознаётся как «видеофайл не найден». */
    const val VK_NOT_FOUND_FRAGMENT = "не найден"

    /**
     * Актуальная SPA-страница `video_ext.php` (2026) не содержит ссылок на поток: она отдаёт
     * `window.vk.webToken.access_token` (анонимный токен) и предзагрузку `video.getHashes`
     * (`csrf` + подпись видео), а плеер сам ходит в `api.vk.com/method/video.getEmbed`.
     * Старый статический парсер (выше) оставлен первым шагом — на старых зеркалах он ещё работает.
     */
    val WEB_TOKEN_PATTERN = Regex("""webToken:\s*\{\s*"access_token":"([^"]+)"""")

    /** `"method":"video.getHashes",...,"response":{"csrf":"...","video":"<hash>"}` — подпись видео. */
    val VIDEO_HASH_PATTERN = Regex(
        """"method":"video\.getHashes".*?"response":\{[^}]*?"video":"([0-9a-f]{16,})"""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** `"mp4_720": "…"` внутри `files` ответа `video.getEmbed`. */
    val EMBED_MP4_FILE_PATTERN = Regex("""(?i)^mp4_(\d{3,4})$""")

    /** VK-URL балансера: `vk.com`, `vkvideo`, `video_ext.php` или обёртка `iframeVK`. */
    fun isVkPlayerUrl(url: String): Boolean {
        val normalized = url.lowercase()
        return "vk.com" in normalized ||
            "vkvideo" in normalized ||
            "video_ext.php" in normalized ||
            "iframevk" in normalized
    }

    /** Раскрывает `\/` и `\uXXXX`, которыми VK экранирует ссылки в JSON внутри страницы. */
    fun normalizePayload(text: String): String =
        decodeUnicodeEscapes(
            text.replace("\\/", "/").replace("\\u002f", "/").replace("\\u002F", "/"),
        )

    /** Декодирует JSON/JS-экранирование `\uXXXX` в тексте страницы (как общий хелпер Android-версии). */
    fun decodeUnicodeEscapes(text: String): String {
        if (!text.contains("\\u")) return text

        val output = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val hex = if (c == '\\' && i + 5 < text.length && text[i + 1] == 'u') {
                text.substring(i + 2, i + 6)
            } else {
                null
            }
            val decoded = hex?.toIntOrNull(16)
            if (decoded != null) {
                output.append(decoded.toChar())
                i += 6
            } else {
                output.append(c)
                i++
            }
        }
        return output.toString()
            .replace("\\u0026", "&")
            .replace("\\u002D", "-")
            .replace("\\u002d", "-")
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\\\", "\\")
    }

    fun hasKnownUrlScheme(url: String): Boolean =
        url.startsWith("//") || url.startsWith("http://") || url.startsWith("https://")

    fun normalizeUrlScheme(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> url.replaceFirst("http://", "https://")
        url.startsWith("https://") -> url
        else -> "https://$url"
    }

    /** Абсолютный URL из относительного (RFC 3986); [fallback] — когда базы нет или она битая. */
    fun resolveRelativeUrl(raw: String, baseUrl: String, fallback: () -> String): String =
        if (baseUrl.isBlank()) {
            fallback()
        } else {
            runCatching { URL(URL(baseUrl), raw).toString() }.getOrElse { fallback() }
        }

    /** Ссылка из разметки/JSON -> абсолютный https-URL (или пустая строка, если разбирать нечего). */
    fun normalizeUrl(url: String, baseUrl: String = ""): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return ""

        val normalized = normalizePayload(trimmed)
            .trim()
            .trim('"')
            .trim('\'')
        if (normalized.isBlank()) return ""

        return when {
            hasKnownUrlScheme(normalized) -> normalizeUrlScheme(normalized)
            normalized.startsWith("/") -> {
                val origin = runCatching {
                    val base = URL(baseUrl)
                    "${base.protocol}://${base.host}"
                }.getOrNull() ?: YUMMY_ORIGIN
                "$origin$normalized"
            }

            else -> resolveRelativeUrl(normalized, baseUrl) { "https://$normalized" }
        }
    }

    /** Ссылка после вычитки экранирования (`\u0026`, `\/`) и обрезки кавычек. */
    fun normalizeEscapedUrl(url: String): String =
        decodeUnicodeEscapes(url)
            .replace("\\u0026", "&")
            .replace("\\u002D", "-")
            .replace("\\u002d", "-")
            .replace("\\/", "/")

    /**
     * Итоговая карта качеств: сначала стандартный порядок, затем незнакомые метки в порядке находки;
     * метка «auto» переименовывается в [autoQualityLabel], если пришла из настроек.
     */
    fun orderQualities(
        raw: LinkedHashMap<String, String>,
        autoQualityLabel: String = "auto",
    ): LinkedHashMap<String, String> {
        val ordered = LinkedHashMap<String, String>()
        STANDARD_QUALITY_KEYS.forEach { key ->
            val aliases = if (key == "auto") listOf(key) else listOf(key, key.removeSuffix("p"))
            aliases.firstNotNullOfOrNull { raw[it] }?.let { ordered[key] = it }
        }
        raw.forEach { (key, value) -> if (!ordered.containsKey(key)) ordered[key] = value }

        if (autoQualityLabel.isBlank() || autoQualityLabel == "auto") return ordered
        return entriesWithAutoLabel(ordered, autoQualityLabel)
    }

    private fun entriesWithAutoLabel(
        qualities: LinkedHashMap<String, String>,
        autoQualityLabel: String,
    ): LinkedHashMap<String, String> {
        val relabeled = LinkedHashMap<String, String>()
        qualities.forEach { (quality, url) ->
            relabeled[if (quality == "auto") autoQualityLabel else quality] = url
        }
        return relabeled
    }

    /**
     * Ссылки на поток из страницы: именованные поля плеера, блок `files`, прямые URL в тексте,
     * `data-video-src`/`<source>`. Дубликаты по URL отбрасываются, при равенстве метки остаётся
     * более длинная ссылка (как в Android-версии).
     */
    fun collectCandidates(html: String, baseUrl: String): LinkedHashMap<String, String> {
        val candidates = LinkedHashMap<String, String>()

        NAMED_STREAM_PATTERNS.forEach { (quality, pattern) ->
            pattern.findAll(html).forEach { match ->
                val rawUrl = match.groupValues.getOrNull(1)?.trim().orEmpty()
                addCandidate(candidates, quality, normalizeUrl(rawUrl, baseUrl))
            }
        }

        VK_FILE_QUALITY_PATTERNS.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                val qualityValue = match.groupValues.getOrNull(1)?.trim().orEmpty()
                val rawUrl = match.groupValues.getOrNull(2)?.trim().orEmpty()
                val quality = if (qualityValue.isBlank()) "auto" else "${qualityValue}p"
                addCandidate(candidates, quality, normalizeUrl(rawUrl, baseUrl))
            }
        }

        FILES_BLOCK_PATTERN.find(html)?.groupValues?.getOrNull(1)?.let { filesBlock ->
            val block = filesBlock.replace("\"trailer\"", "")
            FILES_KEY_VALUE_PATTERNS.forEach { pattern ->
                pattern.findAll(block).forEach { match ->
                    val quality = normalizeQualityLabel(match.groupValues.getOrNull(1)?.trim().orEmpty())
                    val rawUrl = match.groupValues.getOrNull(2)?.trim().orEmpty()
                    addCandidate(candidates, quality, normalizeUrl(rawUrl, baseUrl))
                }
            }
        }

        STREAM_URL_PATTERNS.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                val raw = match.groupValues.getOrNull(1)?.trim() ?: match.value
                val quality = normalizeQualityFromUrl(raw)
                addCandidate(candidates, quality, normalizeUrl(raw, baseUrl))
            }
        }

        PLAIN_SOURCE_PATTERN.findAll(html).forEach { match ->
            val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val quality = normalizeQualityFromUrl(raw)
            addCandidate(candidates, quality, normalizeUrl(raw, baseUrl))
        }

        DATA_SRC_PATTERNS.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
                val quality = normalizeQualityFromUrl(raw)
                addCandidate(candidates, quality, normalizeUrl(raw, baseUrl))
            }
        }

        return candidates
    }

    private fun addCandidate(
        candidates: LinkedHashMap<String, String>,
        quality: String,
        url: String,
    ) {
        if (url.isBlank() || !isStreamLike(url, quality)) return
        val cleaned = normalizeEscapedUrl(url)
        if (candidates.any { (key, value) -> key != quality && value == cleaned }) return

        val current = candidates[quality]
        if (current == null || current.length < cleaned.length) {
            candidates[quality] = cleaned
        }
    }

    /** Похоже ли значение на прямую ссылку на поток (или на известный CDN VK с меткой качества). */
    fun isStreamLike(url: String, quality: String): Boolean {
        val lowered = url.lowercase()
        if (lowered.contains(".m3u8") || lowered.contains(".mp4")) return true

        val isKnownVkCdn = lowered.contains("okcdn.ru") ||
            lowered.contains("vkuser") ||
            lowered.contains("userapi.com") ||
            lowered.contains("vkvd")
        val isNamedVideoQuality = quality != "auto" && isHttpUrl(url)
        return isKnownVkCdn && isNamedVideoQuality
    }

    fun isHttpUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    /** Качество из URL: 720 в `.../720.mp4` -> `720p`; неизвестное — `auto`. */
    fun normalizeQualityFromUrl(url: String): String {
        val found = QUALITY_FROM_URL.find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: return "auto"

        return when (found) {
            144, 240, 360, 480, 720, 1080, 1440, 2160 -> "${found}p"
            else -> "auto"
        }
    }

    /** Метка поля (`mp4_720`, `hls`, `url1080`) -> `720p`/`auto`. */
    fun normalizeQualityLabel(label: String): String {
        val normalized = label.trim().lowercase()
        if (normalized.isBlank()) return "auto"
        if (normalized == "hls" || normalized == "hls_fmp4" || normalized == "url") return "auto"

        val quality = Regex("""(\d{3,4})""").find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return when (quality) {
            144, 240, 360, 480, 720, 1080, 1440, 2160 -> "${quality}p"
            else -> label
        }
    }

    /**
     * URL плеера VK `video_ext.php` из обёртки `iframeVK.html`: сначала параметры самой ссылки
     * (`id=<owner>_<video>`), затем `video<owner>_<id>` в HTML, затем готовый `video_ext.php`.
     */
    fun resolveVideoExtUrl(pageHtml: String, iframeUrl: String): String? {
        val normalized = normalizeUrl(iframeUrl)
        if (normalized.contains("video_ext.php", ignoreCase = true)) return normalized

        parseVideoPairFromText(normalized)?.let { (ownerId, videoId) ->
            return "$VIDEO_EXT_URL?oid=$ownerId&id=$videoId&hd=1"
        }

        parseQueryParam(normalized, "id")?.let { id ->
            parseVideoPairFromId(id)?.let { (ownerId, videoId) ->
                return "$VIDEO_EXT_URL?oid=$ownerId&id=$videoId&hd=1"
            }
        }

        val match = VIDEO_PATH_ID_PATTERN.find(pageHtml)
        val ownerId = match?.groups?.get("owner")?.value
        val videoId = match?.groups?.get("id")?.value
        if (!ownerId.isNullOrBlank() && !videoId.isNullOrBlank()) {
            return "$VIDEO_EXT_URL?oid=$ownerId&id=$videoId&hd=1"
        }

        return VIDEO_EXT_FROM_IFRAME_PATTERNS.firstNotNullOfOrNull { pattern ->
            val found = pattern.find(pageHtml) ?: return@firstNotNullOfOrNull null
            val owner = found.groupValues.getOrNull(1).orEmpty()
            val video = found.groupValues.getOrNull(2).orEmpty()
            if (owner.isBlank() || video.isBlank()) {
                null
            } else {
                "$VIDEO_EXT_URL?oid=$owner&id=$video&hd=1"
            }
        }
    }

    /** `video<owner>_<id>` или `<owner>_<id>` -> пара идентификаторов; одиночный id не подходит. */
    fun parseVideoPairFromText(text: String): Pair<String, String>? {
        val match = VIDEO_PATH_ID_PATTERN.find(text) ?: return null
        val ownerId = match.groups["owner"]?.value.orEmpty()
        val videoId = match.groups["id"]?.value.orEmpty()
        return pairOrNull(ownerId, videoId)
    }

    /** Значение query-параметра `id` (`-234920203_456239687`) -> пара owner/video. */
    fun parseVideoPairFromId(rawId: String): Pair<String, String>? {
        val id = rawId.removePrefix("video")
        if (!id.contains("_")) return null
        val splitIndex = id.indexOf("_")
        if (splitIndex <= 0 || splitIndex >= id.length - 1) return null
        return pairOrNull(id.substring(0, splitIndex), id.substring(splitIndex + 1))
    }

    private fun pairOrNull(ownerId: String, videoId: String): Pair<String, String>? =
        if (ownerId.isBlank() || videoId.isBlank()) null else ownerId to videoId

    /** Значение query-параметра [key] (с URL-декодированием), или null. */
    fun parseQueryParam(url: String, key: String): String? {
        val query = url.substringAfter("?", "").substringBefore("#")
        if (query.isBlank()) return null

        return query.split("&").firstNotNullOfOrNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) return@firstNotNullOfOrNull null
            val param = pair.substring(0, idx)
            if (!param.equals(key, ignoreCase = true)) return@firstNotNullOfOrNull null
            val value = pair.substring(idx + 1)
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: value
        }
    }

    /** Текст сообщения VK из страницы `video_ext.php` в windows-1251; null — блока нет. */
    fun vkUnavailableMessage(bodyBytes: ByteArray): String? =
        VK_MESSAGE_PATTERN.find(String(bodyBytes, VK_PAGE_CHARSET))
            ?.groupValues
            ?.get(1)
            ?.replace(HTML_TAG_PATTERN, " ")
            ?.replace(WHITESPACE_PATTERN, " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** VK сообщает, что видеофайла нет (удалён или никогда не существовал). */
    fun isVkVideoNotFoundMessage(message: String): Boolean =
        message.contains(VK_NOT_FOUND_FRAGMENT, ignoreCase = true)

    /** Видео закрыто для анонимного зрителя (приватное, только для подписчиков). */
    fun isVkAccessDenied(html: String): Boolean = VK_ACCESS_DENIED_PATTERN.containsMatchIn(html)

    /** Актуальная SPA-страница VK: ссылок в HTML нет, их надо добирать через player API. */
    fun isSpaPage(html: String): Boolean =
        WEB_TOKEN_PATTERN.containsMatchIn(html) || "apiPrefetchCache" in html

    /** Анонимный web-token страницы (`anonym.…`): им подписываются запросы `video.getEmbed`. */
    fun extractWebToken(html: String): String? =
        WEB_TOKEN_PATTERN.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    /** Подпись видео из предзагрузки `video.getHashes`; может отсутствовать у приватных видео. */
    fun extractVideoHash(html: String): String? =
        VIDEO_HASH_PATTERN.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    /** URL метода `video.getEmbed`: owner/video из `video_ext.php`, [videoHash] — когда VK его выдал. */
    fun buildEmbedApiUrl(
        ownerId: String,
        videoId: String,
        accessToken: String,
        videoHash: String?,
    ): String = buildString {
        append("https://api.vk.com/method/video.getEmbed")
        append("?owner_id=").append(ownerId)
        append("&video_id=").append(videoId)
        if (!videoHash.isNullOrBlank()) append("&hash=").append(videoHash)
        append("&v=").append(API_VERSION)
        append("&access_token=").append(accessToken)
    }

    /**
     * `files` из ответа `video.getEmbed` -> лестница качеств: `mp4_144…mp4_2160` по возрастанию,
     * `hls`/`hls_fmp4` — как `auto`. `dash_sep` пропускается: это отдельный видео-поток без
     * манифеста, mpv его не откроет.
     */
    fun parseEmbedFiles(apiJson: String): LinkedHashMap<String, String> {
        val response = runCatching { JSONObject(apiJson) }.getOrNull()?.optJSONObject("response")
            ?: return LinkedHashMap()
        val files = response.optJSONObject("video")?.optJSONObject("files")
            ?: return LinkedHashMap()

        val candidates = LinkedHashMap<String, String>()
        val hls = files.optString("hls").takeIf { isUsableStreamUrl(it) }
            ?: files.optString("hls_fmp4").takeIf { isUsableStreamUrl(it) }
        hls?.let { candidates["auto"] = it }

        val keys = files.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val quality = EMBED_MP4_FILE_PATTERN.find(key)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            val streamUrl = files.optString(key)
            if (isUsableStreamUrl(streamUrl)) candidates["${quality}p"] = streamUrl
        }
        return candidates
    }

    /** Ошибка player API: `{error_code, error_msg}`; null — ошибки нет (или тело не JSON). */
    fun parseEmbedApiError(apiJson: String): Pair<Int, String>? {
        val error = runCatching { JSONObject(apiJson) }.getOrNull()?.optJSONObject("error")
            ?: return null
        return error.optInt("error_code") to error.optString("error_msg")
    }

    private fun isUsableStreamUrl(url: String): Boolean =
        url.isNotBlank() && !url.equals("null", ignoreCase = true)

    /** Версия VK API, которую запрашивает текущий веб-плеер. */
    const val API_VERSION = "5.289"


    /** Короткая сводка страницы для отчёта: видно, пустая ли это заглушка VK, а не нужна ли правка парсера. */
    fun pageDiagnostics(html: String, viaVideoExt: Boolean): String {
        val title = Regex("""<title[^>]*>([^<]{1,40})""").find(html)?.groupValues?.get(1)?.trim().orEmpty()
        return "videoExt=$viaVideoExt, title=\"$title\""
    }
}
