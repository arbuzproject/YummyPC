// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.sibnet

import java.net.URL

/**
 * Чистые шаги разбора Sibnet: нормализация ссылки и вычитка mp4-источника из HTML плеера.
 * Вынесены из [SibnetResolver], чтобы покрывать их тестами без сети.
 *
 * Логика 1:1 повторяет `SibnetExtractor` из YummyTV (модуль source/sibnet).
 */
internal object SibnetParsing {

    const val SIBNET_ORIGIN = "https://video.sibnet.ru"
    const val YANI_ORIGIN = "https://yani.tv/"
    const val USER_AGENT_HEADER = "User-Agent"

    /** `player.src([{src: "..."}])` в скрипте плеера, затем `<source src="...">` как запасной вариант. */
    val STREAM_URL_PATTERNS = listOf(
        Regex("""player\.src\(\s*\[\s*\{\s*src\s*:\s*["']([^"']+)"""),
        Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
    )

    fun isSibnetPlayerUrl(url: String): Boolean = url.contains("sibnet.ru", ignoreCase = true)

    /** Ссылка на поток из HTML страницы плеера, приведённая к абсолютному URL; null — не нашлось. */
    fun extractStreamUrl(page: String, playerUrl: String): String? =
        STREAM_URL_PATTERNS
            .firstNotNullOfOrNull { pattern ->
                pattern.find(page)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { normalizeUrl(it, playerUrl) }
            }
            ?.takeIf { it.isNotBlank() }

    fun normalizeUrl(url: String, baseUrl: String = ""): String {
        val normalized = url.trim().trim('"', '\'').replace("\\/", "/")
        if (normalized.isBlank()) return ""

        return when {
            // Намеренно без runCatching (как в Android-версии): битая база — исключение,
            // а не тихий откат на https://<ссылка>.
            hasKnownUrlScheme(normalized) -> normalizeUrlScheme(normalized)
            baseUrl.isNotBlank() -> URL(URL(baseUrl), normalized).toString()
            else -> "https://$normalized"
        }
    }

    private fun hasKnownUrlScheme(url: String): Boolean =
        url.startsWith("//") || url.startsWith("http://") || url.startsWith("https://")

    private fun normalizeUrlScheme(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> url.replaceFirst("http://", "https://")
        else -> url
    }
}
