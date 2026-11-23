// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
 * Проверка обновлений через последний релиз GitHub.
 *
 * Работает без авторизации (репозиторий публичный), кэширует ответ на 10 минут, чтобы
 * не дёргать API при каждом переходе по экранам.
 */
class UpdateChecker(private val client: HttpClient = YaniHttp.client) {

    suspend fun check(currentVersion: String = AppInfo.VERSION): UpdateInfo? {
        val latest = latest() ?: return null
        return latest.takeIf { isNewerVersion(it.version, currentVersion) }
    }

    suspend fun latest(force: Boolean = false): UpdateInfo? {
        val now = System.currentTimeMillis()
        if (!force) {
            cached?.let { (at, value) ->
                if (now - at < CACHE_MS) return value
            }
        }
        val result = runCatching {
            val text = client.get(LATEST_RELEASE_URL) {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", "YummyPC/${AppInfo.VERSION}")
            }.bodyAsText()
            parse(text)
        }.getOrNull()
        cached = now to result
        return result
    }

    private fun parse(raw: String): UpdateInfo? {
        val root = json.parseToJsonElement(raw).jsonObject
        if (root["tag_name"]?.jsonPrimitive?.contentOrNull == null) return null
        val tag = root["tag_name"]!!.jsonPrimitive.content
        val name = root["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val body = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val url = root["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val published = root["published_at"]?.jsonPrimitive?.contentOrNull
        val assets = root["assets"]?.jsonArray.orEmpty()
        val preferred = assets.firstOrNull { asset ->
            val n = asset.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            n.startsWith("YummyPC-") && n.endsWith("-setup.exe")
        } ?: assets.firstOrNull { asset ->
            val n = asset.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            n.endsWith(".zip")
        }
        val download = preferred?.jsonObject?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
        return UpdateInfo(
            version = tag.removePrefix("v").removePrefix("V"),
            name = name.ifBlank { "YummyPC $tag" },
            notes = body,
            pageUrl = url,
            downloadUrl = download,
            publishedAt = published,
        )
    }

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/arbuzproject/YummyPC/releases/latest"
        private const val CACHE_MS = 10 * 60_000L
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

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
