// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * Чистые шаги разбора страницы плеера Alloha: зерно `viewporti`, id активного файла из `fileList`,
 * токен из iframe-ссылки, `userParam`, скрипт плеера и ключ `Authorizations`, тикеты WS/отчётов.
 *
 * Вынесены из [AllohaHttpSessionOpener] (как `AllohaResponseParsing` в Android-версии), чтобы покрывать
 * их тестами без сети.
 */
internal object AllohaPageParsing {

    /** `<meta name="viewporti" content="…">` — зерно текущей загрузки страницы. */
    val VIEWPORTI = Regex("""<meta\s+name="viewporti"\s+content="([^"]+)"""")

    /** `<script src=".../app.<хеш>.js">` — скрипт сайта, в нём лежит ключ `Authorizations`. */
    val APP_SCRIPT = Regex("""<script[^>]+src=["']([^"']*/app\.[^"']*\.js[^"']*)["']""")

    /** Длинная константа-ключ в скрипте: 340-420 символов `[a-zA-Z0-9]`. */
    val API_KEY = Regex("""['"]([a-zA-Z0-9]{340,420})['"]""")

    /** `userParam = {token:'…', domain:'…', …}`. */
    val USER_PARAM = Regex("""userParam\s*=\s*\{[^}]*\}""")
    val PARAM_TOKEN = Regex("""token:\s*'([^']+)'""")
    val PARAM_DOMAIN = Regex("""domain:\s*'([^']*)'""")

    /** `const fileList = JSON.parse('…')`: из JSON берётся `active.id`. */
    val FILE_LIST = Regex(
        """const\s+fileList\s*=\s*JSON\.parse\(\s*(["'`])(.*?)\1\s*\)""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** `//host/path` и голый `host/path` -> абсолютный https-URL (как в Android-версии). */
    fun normalizeIframeUrl(iframeUrl: String): String = when {
        iframeUrl.startsWith("//") -> "https:$iframeUrl"
        iframeUrl.startsWith("http") -> iframeUrl
        else -> "https://$iframeUrl"
    }

    /** `https://host:port/path?query` -> `https://host:port`, хост в нижнем регистре. */
    fun originOf(url: String): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return url.substringBefore('?').trimEnd('/')
        return "${httpUrl.scheme}://${httpUrl.host.lowercase()}"
    }

    /** Значение параметра `token` из iframe-ссылки; `null` — параметра нет. */
    fun iframeToken(iframeUrl: String): String? = iframeUrl.toHttpUrlOrNull()?.queryParameter("token")

    /** Зерно `viewporti` из HTML страницы плеера. */
    fun parseViewporti(page: String): String? = VIEWPORTI.find(page)?.groupValues?.get(1)

    /** Числовой id активного файла (`active.id`) из `const fileList = JSON.parse(…)`. */
    fun parseActiveId(page: String): Long? {
        val literal = FILE_LIST.find(page)?.groupValues?.get(2) ?: return null
        val json = literal.replace("\\\"", "\"").replace("\\\\", "\\")
        return runCatching { JSONObject(json).getJSONObject("active").getLong("id") }.getOrNull()
    }

    /** Путь к скрипту сайта из `<script src>`; относительный (`/build/…`) или абсолютный. */
    fun parseAppScriptPath(page: String): String? = APP_SCRIPT.find(page)?.groupValues?.get(1)

    /** Относительный путь скрипта -> абсолютный URL от origin страницы. */
    fun absoluteScriptUrl(path: String, origin: String): String = when {
        path.startsWith("http") -> path
        else -> "$origin/${path.trimStart('.', '/')}"
    }

    /** Ключ `Authorizations` из скрипта сайта (340-420 символов). */
    fun parseApiKey(playerScript: String): String? = API_KEY.find(playerScript)?.groupValues?.get(1)

    /** Токен и домен из `userParam` страницы. */
    fun parseUserParam(page: String): AllohaPageUserParam? {
        val block = USER_PARAM.find(page)?.value ?: return null
        val token = PARAM_TOKEN.find(block)?.groupValues?.get(1) ?: return null
        val domain = PARAM_DOMAIN.find(block)?.groupValues?.get(1) ?: return null
        return AllohaPageUserParam(token = token, domain = domain)
    }

    /** Тикет WebSocket (`pnr`/`pnk`) из ответа `bnsi`; `null` — сокета в ответе нет. */
    fun parseSocketTicket(bnsi: String): AllohaSocketTicket? {
        val root = runCatching { JSONObject(bnsi) }.getOrNull() ?: return null
        fun field(name: String): String? =
            if (root.isNull(name)) null else root.optString(name).takeIf(String::isNotBlank)
        val url = field("pnr") ?: return null
        val key = field("pnk") ?: return null
        return AllohaSocketTicket(url = url, key = key)
    }

    /** Тикет отчётов `/events` и `/stat`: `userParam` + id файла. */
    fun parseViewTicket(page: String, activeId: Long): AllohaViewTicket? {
        val userParam = parseUserParam(page) ?: return null
        return AllohaViewTicket(
            token = userParam.token,
            domain = userParam.domain,
            fileId = activeId.toString(),
        )
    }
}
