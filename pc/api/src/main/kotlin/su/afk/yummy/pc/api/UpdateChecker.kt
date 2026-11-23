// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText

/** Информация о новом релизе на GitHub. */
data class UpdateInfo(
    val version: String,
    val name: String,
    val notes: String,
    val pageUrl: String,
    val downloadUrl: String?,
    val publishedAt: String?,
)

/**
 * Проверка обновлений через GitHub.
 *
 * Основной источник — Atom-лента релизов (`/releases.atom`): она не подпадает под лимиты
 * REST API (60 запросов/час на IP), из-за которых раньше проверка молча отвечала
 * «обновлений нет». Если лента недоступна — запасной путь через REST API.
 *
 * Ответ кэшируется в памяти и в `~/.yummypc/update-cache.txt` на 10 минут.
 */
class UpdateChecker(private val client: HttpClient = YaniHttp.client) {

    suspend fun check(currentVersion: String = AppInfo.VERSION): UpdateInfo? {
        val latest = latest() ?: return null
        return latest.takeIf { isNewerVersion(it.version, currentVersion) }
    }

    suspend fun latest(force: Boolean = false): UpdateInfo? {
        val now = System.currentTimeMillis()
        if (!force) {
            cached?.let { (at, value) -> if (now - at < CACHE_MS) return value }
            readFileCache()?.let { (at, value) -> if (now - at < CACHE_MS) { cached = at to value; return value } }
        }
        val fromAtom = runCatching { fetchAtom() }.getOrNull()
        val result = fromAtom ?: runCatching { fetchApi() }.getOrNull()
        cached = now to result
        writeFileCache(now, result)
        return result
    }

    // ---------- Atom-лента ----------

    private suspend fun fetchAtom(): UpdateInfo? {
        val xml = client.get(RELEASES_ATOM) {
            header("Accept", "application/atom+xml")
            header("User-Agent", "YummyPC/${AppInfo.VERSION}")
        }.bodyAsText()
        val entries = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map { it.groupValues[1] }
            .toList()
        if (entries.isEmpty()) return null
        // Лента отсортирована от новых к старым — берём первый стабильный релиз.
        for (entry in entries) {
            val version = Regex("/v([0-9][0-9A-Za-z.\\-]*)</id>").find(entry)?.groupValues?.get(1)
                ?: Regex("/releases/tag/v([0-9][0-9A-Za-z.\\-]*)").find(entry)?.groupValues?.get(1)
                ?: continue
            if (version.contains('-')) continue // pre-release
            val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(entry)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
            val notesHtml = Regex("<content type=\"html\">(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
                .find(entry)?.groupValues?.get(1).orEmpty()
            val pageUrl = Regex("<link rel=\"alternate\" type=\"text/html\" href=\"([^\"]+)\"")
                .find(entry)?.groupValues?.get(1)
                ?: "https://github.com/arbuzproject/YummyPC/releases/tag/v$version"
            val updated = Regex("<updated>([^<]+)</updated>").find(entry)?.groupValues?.get(1)
            return UpdateInfo(
                version = version,
                name = title.ifBlank { "YummyPC $version" },
                notes = htmlToText(unescape(notesHtml)),
                pageUrl = pageUrl,
                downloadUrl = buildString {
                    append("https://github.com/arbuzproject/YummyPC/releases/download/v")
                    append(version)
                    append("/YummyPC-")
                    append(version)
                    append("-portable.zip")
                },
                publishedAt = updated,
            )
        }
        return null
    }

    // ---------- REST API (запасной путь) ----------

    private suspend fun fetchApi(): UpdateInfo? {
        val text = client.get(LATEST_RELEASE_URL) {
            header("Accept", "application/vnd.github+json")
            header("User-Agent", "YummyPC/${AppInfo.VERSION}")
        }.bodyAsText()
        val root = kotlinx.serialization.json.Json
            .parseToJsonElement(text)
            .let { it as? kotlinx.serialization.json.JsonObject }
            ?: return null
        fun str(key: String): String? =
            root[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        val tag = str("tag_name") ?: return null
        val version = tag.removePrefix("v").removePrefix("V")
        val assets = (root["assets"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        val download = assets.mapNotNull { asset ->
            val obj = asset as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val url = (obj["browser_download_url"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (name.startsWith("YummyPC-") && name.endsWith("-setup.exe")) url else null
        }.firstOrNull()
        return UpdateInfo(
            version = version,
            name = str("name").orEmpty().ifBlank { "YummyPC $tag" },
            notes = str("body").orEmpty(),
            pageUrl = str("html_url").orEmpty(),
            downloadUrl = download,
            publishedAt = str("published_at"),
        )
    }

    // ---------- утилиты ----------

    private fun htmlToText(html: String): String = html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</li>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("\r", "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun readFileCache(): Pair<Long, UpdateInfo?>? = runCatching {
        val file = cacheFile
        if (!file.isFile) return null
        val first = file.readLines().firstOrNull() ?: return null
        val at = first.substringBefore('\t').toLongOrNull() ?: return null
        val payload = first.substringAfter('\t', "")
        val parts = payload.split('\u001f')
        if (parts.size < 5) return@runCatching null
        at to UpdateInfo(
            version = parts[0],
            name = parts[1],
            notes = parts[4],
            pageUrl = parts[2],
            downloadUrl = parts[3].ifBlank { null },
            publishedAt = null,
        )
    }.getOrNull()

    private fun writeFileCache(at: Long, info: UpdateInfo?) = runCatching {
        val file = cacheFile
        file.parentFile?.mkdirs()
        val payload = info?.let {
            listOf(it.version, it.name.replace('\t', ' '), it.pageUrl, it.downloadUrl.orEmpty(), it.notes.replace('\t', ' ').replace('\n', ' '))
                .joinToString("\u001f")
        }.orEmpty()
        file.writeText("$at\t$payload")
    }

    private val cacheFile get() = java.io.File(System.getProperty("user.home"), ".yummypc/update-cache.txt")

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/arbuzproject/YummyPC/releases/latest"
        private const val RELEASES_ATOM = "https://github.com/arbuzproject/YummyPC/releases.atom"
        private const val CACHE_MS = 10 * 60_000L

        @Volatile
        private var cached: Pair<Long, UpdateInfo?>? = null
    }
}

/** Сравнение версий вида 1.0.0 / 1.2.10: true, если [candidate] новее [current]. */
fun isNewerVersion(candidate: String, current: String): Boolean {
    fun parts(value: String): List<Int> = value
        .removePrefix("v").removePrefix("V")
        .split('.', '-', '+')
        .mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
    val a = parts(candidate)
    val b = parts(current)
    if (a.isEmpty()) return false
    for (index in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrElse(index) { 0 }
        val right = b.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return false
}
