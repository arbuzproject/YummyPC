// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.rutube

import org.json.JSONObject
import java.net.URL

/**
 * Чистые шаги разбора Rutube: id видео из embed-ссылки, ссылка на options API, поток из
 * `video_balancer`, разбор HLS-мастер-плейлиста в лестницу качеств. Вынесены из [RutubeResolver],
 * чтобы покрывать их тестами без сети.
 *
 * Логика 1:1 повторяет `RutubeExtractor` из YummyTV (модуль source/rutube).
 */
internal object RutubeParsing {

    const val RUTUBE_ORIGIN = "https://rutube.ru"
    const val USER_AGENT_HEADER = "User-Agent"

    /** Порядок качеств, как в `STANDARD_QUALITY_KEYS` Android-версии. */
    val STANDARD_QUALITY_KEYS =
        listOf("auto", "144p", "240p", "360p", "480p", "720p", "1080p", "1440p", "2160p")

    private val VIDEO_ID_PATTERN = Regex("""(?i)([a-f0-9]{32})""")
    private val RESOLUTION_PATTERN = Regex("""(?i)RESOLUTION=(\d+)x(\d+)""")

    fun isRutubePlayerUrl(url: String): Boolean = url.contains("rutube.ru", ignoreCase = true)

    fun optionsUrl(videoId: String): String =
        "$RUTUBE_ORIGIN/api/play/options/$videoId/?no_404=true"

    /**
     * Id видео из ссылки: сегмент пути из 32 hex-символов (`/play/embed/<id>`, `/video/<id>/`),
     * иначе первое такое совпадение в самой ссылке.
     */
    fun extractVideoId(url: String): String? {
        val segments = runCatching {
            URL(url).path
                .trim('/')
                .split('/')
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

        val pathId = segments.firstOrNull { VIDEO_ID_PATTERN.matches(it) }
        if (!pathId.isNullOrBlank()) return pathId

        return VIDEO_ID_PATTERN.find(url)?.groupValues?.getOrNull(1)
    }

    /**
     * Ссылка на мастер-плейлист из `video_balancer`: сначала `m3u8`, затем `default`.
     *
     * JSON null у Android `optString` даёт строку "null": её нельзя считать значением, иначе
     * резервный `default` не подхватывался бы.
     */
    fun streamUrlFromBalancer(balancer: JSONObject): String? =
        listOf("m3u8", "default").firstNotNullOfOrNull { key ->
            balancer.optString(key).takeIf {
                !balancer.isNull(key) &&
                    it.isNotBlank() &&
                    !it.equals("null", ignoreCase = true)
            }
        }

    /**
     * HLS-мастер-плейлист -> лестница: `auto` = сам мастер-URL, варианты — по `RESOLUTION`.
     * Незнакомое разрешение (например `854x800`) отбрасывается.
     */
    fun buildQualityMap(
        streamUrl: String,
        masterPlaylist: String,
        autoQualityLabel: String = "auto",
    ): LinkedHashMap<String, String> {
        val candidates = linkedMapOf("auto" to streamUrl)
        var pendingQuality: String? = null

        masterPlaylist.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    pendingQuality = qualityLabelFromStreamInfo(line)
                    return@forEach
                }

                val quality = pendingQuality
                if (quality != null && !line.startsWith("#")) {
                    val variantUrl = normalizeUrl(line, streamUrl)
                    if (variantUrl.isNotBlank() && !candidates.containsKey(quality)) {
                        candidates[quality] = variantUrl
                    }
                    pendingQuality = null
                }
            }

        return orderQualityMap(candidates, autoQualityLabel)
    }

    fun qualityLabelFromStreamInfo(line: String): String? {
        val height = RESOLUTION_PATTERN.find(line)
            ?.groupValues
            ?.getOrNull(2)
            ?.toIntOrNull()
            ?: return null

        return when (height) {
            144, 240, 360, 480, 720, 1080, 1440, 2160 -> "${height}p"
            else -> null
        }
    }

    /** Ссылка из плейлиста -> абсолютный https-URL; `/path` разрешается от хоста мастер-плейлиста. */
    fun normalizeUrl(url: String, baseUrl: String = ""): String {
        val trimmed = url.trim().trim('"').trim('\'')
        if (trimmed.isBlank()) return ""

        return when {
            hasKnownUrlScheme(trimmed) -> normalizeUrlScheme(trimmed)
            // Корневой путь относится к хосту документа-основания, а не всегда к rutube.ru:
            // варианты мастер-плейлиста лежат на хосте балансера.
            trimmed.startsWith("/") ->
                resolveRelativeUrl(trimmed, baseUrl) { "$RUTUBE_ORIGIN$trimmed" }

            else -> resolveRelativeUrl(trimmed, baseUrl) { "https://$trimmed" }
        }
    }

    fun hasKnownUrlScheme(url: String): Boolean =
        url.startsWith("//") || url.startsWith("http://") || url.startsWith("https://")

    fun normalizeUrlScheme(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> url.replaceFirst("http://", "https://")
        url.startsWith("https://") -> url
        else -> "https://$url"
    }

    fun resolveRelativeUrl(raw: String, baseUrl: String, fallback: () -> String): String =
        if (baseUrl.isBlank()) {
            fallback()
        } else {
            runCatching { URL(URL(baseUrl), raw).toString() }.getOrElse { fallback() }
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
}
