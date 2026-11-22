// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import org.json.JSONObject

/**
 * Разбор ответа `POST /bnsi/movies/{id}`: озвучки с лесенками качеств и субтитры.
 *
 * @param responseJson тело ответа `bnsi`.
 * @param pickMirror выбирает URL из зеркал одного качества (`"url1 or url2"`); по умолчанию первое.
 * @throws AllohaSourceUnavailableException если `hlsSource` нет или ни у одной озвучки нет качеств.
 * @throws org.json.JSONException если тело — не JSON.
 */
internal fun parseSources(
    responseJson: String,
    pickMirror: (mirrors: List<String>) -> String = { it.first() },
): AllohaParsedSources {
    val root = JSONObject(responseJson)
    val sources = root.optJSONArray("hlsSource")
        ?: throw AllohaSourceUnavailableException("hlsSource is missing")

    val audioTracks = buildList {
        for (index in 0 until sources.length()) {
            val source = sources.optJSONObject(index) ?: continue
            val quality = source.optJSONObject("quality") ?: continue
            val qualities = linkedMapOf<String, String>()
            quality.keys().forEach { label ->
                val mirrors = (quality.optNonBlankString(label) ?: return@forEach)
                    .split(" or ")
                    .map { it.trim().normalizeStreamUrl() }
                    .filter(String::isNotBlank)
                if (mirrors.isEmpty()) return@forEach
                qualities.putIfAbsent(label.normalizeQualityLabel(), pickMirror(mirrors))
            }
            if (qualities.isEmpty()) continue
            val sorted = qualities.entries
                .sortedBy { it.key.filter(Char::isDigit).toIntOrNull() ?: 0 }
                .associateTo(linkedMapOf()) { it.toPair() }
            add(
                AllohaAudioTrack(
                    // audioId — идентификатор Alloha; запасной вариант — индекс, чтобы озвучка без него
                    // оставалась выбираемой.
                    id = source.optNonBlankString("audioId") ?: index.toString(),
                    label = source.optNonBlankString("label")?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?: "#${index + 1}",
                    isDefault = source.optBoolean("default"),
                    qualities = sorted,
                ),
            )
        }
    }
    if (audioTracks.isEmpty()) throw AllohaSourceUnavailableException("no HLS qualities found")

    val subtitles = buildList {
        val tracks = root.optJSONArray("tracks") ?: return@buildList
        for (index in 0 until tracks.length()) {
            val entry = tracks.optJSONObject(index) ?: continue
            if (!entry.optString("kind").equals("captions", ignoreCase = true)) continue
            val src = entry.optNonBlankString("src")?.normalizeStreamUrl()
                ?.takeIf(String::isNotBlank)
                ?: continue
            add(
                AllohaSubtitleTrack(
                    label = entry.optNonBlankString("label")?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?: "#${index + 1}",
                    url = src,
                    language = entry.optNonBlankString("language"),
                    format = src.substringBefore('?').substringAfterLast('.', "")
                        .lowercase()
                        .takeIf(String::isNotBlank),
                ),
            )
        }
    }

    return AllohaParsedSources(audioTracks = audioTracks, subtitles = subtitles)
}

/**
 * `optString` у `org.json` на JSON `null` возвращает строку `"null"`: она не пуста и превращалась бы в
 * «ссылку» или идентификатор дорожки. Здесь `null` и пустая строка — отсутствие.
 */
private fun JSONObject.optNonBlankString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

/** `//host/path` -> `https://host/path`; остальное (уже абсолютное) не меняется. */
internal fun String.normalizeStreamUrl(): String = if (startsWith("//")) "https:$this" else this

/** `1080` -> `1080p`; уже помеченное `p` не дублируется. */
internal fun String.normalizeQualityLabel(): String =
    trim().let { if (it.endsWith("p")) it else "${it}p" }

private val ERROR_PAGE_STYLE_OR_SCRIPT = Regex("(?is)<(style|script)\\b.*?</\\1>")
private val ERROR_PAGE_HTML_TAG = Regex("<[^>]+>")
private val ERROR_PAGE_WHITESPACE = Regex("\\s+")
private val ERROR_PAGE_TITLE = Regex("^(?:Ошибка!\\s*)+")
private const val ERROR_PAGE_REASON_LENGTH = 100

/**
 * Видимый текст страницы-заглушки Alloha на 404 («запрашиваемая серия отсутствует в нашей коллекции»,
 * «указанная озвучка недоступна», «сезон недоступен») без заголовка «Ошибка!». Нужен как причина в
 * отчётах: по ней отличают «серии нет» от «озвучки нет». `null` — на странице нет текста.
 */
internal fun allohaErrorPageReason(html: String): String? =
    html.replace(ERROR_PAGE_STYLE_OR_SCRIPT, " ")
        .replace(ERROR_PAGE_HTML_TAG, " ")
        .replace(ERROR_PAGE_WHITESPACE, " ")
        .trim()
        .replace(ERROR_PAGE_TITLE, "")
        .trim()
        .take(ERROR_PAGE_REASON_LENGTH)
        .takeIf { it.isNotEmpty() }
