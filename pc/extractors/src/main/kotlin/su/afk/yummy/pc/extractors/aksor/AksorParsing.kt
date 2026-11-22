// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.aksor

import java.net.URI

/**
 * Чистые шаги разбора Aksor: hash видео из ссылки, метки/санитайзинг ссылок на качества, запасной
 * разбор HTML (мета `video_url`, скрипты плеера с путём API). Вынесены из [AksorResolver], чтобы
 * покрывать их тестами без сети.
 *
 * Логика 1:1 повторяет `AksorExtractor` из YummyTV (модуль source/aksor).
 */
internal object AksorParsing {

    const val PLAYER_ORIGIN = "https://player.aksor.tv"
    const val YANI_REFERER = "https://yani.tv/"
    const val USER_AGENT_HEADER = "User-Agent"

    /** Ключи ответа player API в порядке возрастания качества. */
    val QUALITY_ORDER = listOf("q360", "q480", "q720", "q1080", "q2k", "q4k")

    val META_VIDEO_URL_PATTERN =
        Regex("""<meta[^>]+name=["']video_url["'][^>]+content=["']([^"']+)["']""")
    val SCRIPT_SRC_PATTERN = Regex("""<script[^>]+src=["']([^"']+)["']""")
    val API_PATH_PATTERN = Regex("""["']([^"']*/api)["']""")

    fun isAksorPlayerUrl(url: String): Boolean = url.contains("aksor.tv", ignoreCase = true)

    fun normalizeUrl(url: String): String = normalizeUrlScheme(url.trim())

    /**
     * Hash видео: сегмент после `video` в `/video/<hash>`; если сегмента `video` нет — последний
     * сегмент пути. Пустой путь -> null.
     */
    fun extractHash(url: String): String? {
        val segments = URI(url).path
            ?.trim('/')
            ?.split('/')
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val videoIndex = segments.indexOf("video")
        val hash = if (videoIndex >= 0) {
            segments.getOrNull(videoIndex + 1)
        } else {
            segments.lastOrNull()
        }
        return hash?.takeIf { it.isNotBlank() }
    }

    /** `q720` -> `720p`, `q2k` -> `2K`, `q4k` -> `4K`. */
    fun qualityLabel(key: String): String = when (key) {
        "q2k" -> "2K"
        "q4k" -> "4K"
        else -> "${key.removePrefix("q")}p"
    }

    /**
     * В путях CDN Aksor встречаются пробелы (названия озвучек вроде `JAM CLUB`). Браузеры их
     * автоматически кодируют, а HTTP-стек без кодирования получает 404 от части узлов CDN.
     */
    fun sanitizeStreamUrl(url: String): String =
        if (url.contains(' ')) url.replace(" ", "%20") else url

    /** `<meta name="video_url" content="...">`, кроме шаблонов вида `{{ video.url }}`. */
    fun extractMetaVideoUrl(html: String): String? =
        META_VIDEO_URL_PATTERN.find(html)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.contains("{{") }

    /** `<script src="...">` -> абсолютные URL относительно [PLAYER_ORIGIN]. */
    fun scriptUrls(html: String): List<String> =
        SCRIPT_SRC_PATTERN.findAll(html)
            .map { it.groupValues[1] }
            .map { src ->
                when {
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/") -> "$PLAYER_ORIGIN$src"
                    src.startsWith("http") -> src
                    else -> "$PLAYER_ORIGIN/$src"
                }
            }
            .toList()

    /** Путь API из JS-скрипта плеера (`"https://api.aksor.tv/api"` -> `/api`-совместимый префикс). */
    fun apiPathFromScript(script: String): String? =
        API_PATH_PATTERN.find(script)?.groupValues?.getOrNull(1)

    private fun normalizeUrlScheme(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> url.replaceFirst("http://", "https://")
        url.startsWith("https://") -> url
        else -> "https://$url"
    }
}
