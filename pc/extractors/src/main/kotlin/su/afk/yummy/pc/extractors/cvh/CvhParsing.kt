// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.cvh

import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder

/** Одна запись плейлиста CdnVideoHub, вынутая из JSON-ответа. */
internal data class CvhPlaylistItem(
    val vkId: String,
    val voiceStudio: String,
    val voiceType: String,
    /** null для фильмов: при `isSerial=false` поля `episode`/`season` в плейлисте нет вообще. */
    val episode: Int?,
)

/** Разобранный плейлист CVH: теги региона, признак сериала и записи (null — поля `items` нет). */
internal class CvhPlaylist(
    val isSerial: Boolean,
    val tags: List<Int>,
    val items: List<CvhPlaylistItem>?,
) {
    val hasItems: Boolean get() = (items?.size ?: 0) > 0
}

/**
 * Чистые шаги разбора CVH: query iframe-ссылки, плейлист (выбор озвучки/серии), лестница mp4-качеств
 * из ответа video API, подмена хоста okcdn на резервный. Вынесены из [CvhResolver], чтобы покрывать
 * их тестами без сети.
 *
 * Логика 1:1 повторяет `CvhExtractor`/`CvhPlaylistItem`/`CvhUtils` из YummyTV (модуль source/cvh).
 */
internal object CvhParsing {

    /** Тег плейлиста CdnVideoHub «Blocked»: контент запрещён на территории РФ. */
    const val CVH_TAG_BLOCKED = 5

    private const val DUBBING_LABEL_PREFIX = "Озвучка "

    /** mpeg-ключи ответа video API -> подпись качества. Порядок задаёт лестницу по возрастанию. */
    val QUALITY_KEYS: List<Pair<String, String>> = listOf(
        "240p" to "mpegLowestUrl",
        "360p" to "mpegLowUrl",
        "480p" to "mpegMediumUrl",
        "720p" to "mpegHighUrl",
        "1080p" to "mpegFullHdUrl",
    )

    private val IPV4_HOST_REGEX = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")

    /** `query` iframe-ссылки -> параметры (URL-декодированные). */
    fun parseQuery(query: String): Map<String, String> =
        query.split("&").mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) {
                null
            } else {
                pair.substring(0, eq) to URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }
        }.toMap()

    /** JSON плейлиста -> модель. Битая структура даёт пустой плейлист (как `opt*` в Android-версии). */
    fun parsePlaylist(json: JSONObject): CvhPlaylist {
        val tags = json.optJSONArray("tags")
            ?.let { array -> (0 until array.length()).map(array::optInt) }
            .orEmpty()
        val itemsArray = json.optJSONArray("items")
        val items = itemsArray?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                CvhPlaylistItem(
                    vkId = item.optStringOrEmpty("vkId"),
                    voiceStudio = item.optStringOrEmpty("voiceStudio"),
                    voiceType = item.optStringOrEmpty("voiceType"),
                    episode = if (item.isNull("episode")) null else item.optInt("episode"),
                )
            }
        }
        return CvhPlaylist(
            // Default true сохраняет прежнюю фильтрацию серий, если поле когда-нибудь исчезнет.
            isSerial = json.optBoolean("isSerial", true),
            tags = tags,
            items = items,
        )
    }

    /**
     * Плейлист закрыт запретом на территории РФ: источник помечает его тегом [CVH_TAG_BLOCKED] и
     * отдаёт пустой список. Тег при непустом списке запретом не считаем — серии ещё можно играть.
     */
    fun isCvhRegionBlocked(tags: List<Int>, hasItems: Boolean): Boolean =
        !hasItems && CVH_TAG_BLOCKED in tags

    /**
     * Выбирает запись плейлиста для воспроизведения.
     *
     * Фильтр по серии пропускается для фильмов: у их записей нет поля `episode`, и трактовка
     * отсутствующего значения как 0 отбрасывала бы единственного кандидата.
     *
     * Совпадение озвучки ищется по нескольким ключам: `dubbing_code` не всегда равен `voiceStudio`
     * (локализованное имя студии приходит транслитом, «ort» против «ОРТ»), и только подпись
     * `dubbing` («Озвучка Дубляж ОРТ») пишет студию так же, как плейлист.
     */
    fun selectCvhItem(
        items: List<CvhPlaylistItem>,
        isSerial: Boolean,
        episodeNum: Int,
        dubbingCode: String,
        dubbingLabel: String,
    ): CvhPlaylistItem? {
        val candidates = if (!isSerial) {
            items
        } else {
            items.filter { it.episode == null || it.episode == episodeNum }
        }
        if (candidates.isEmpty()) return null

        val label = dubbingLabel.removePrefix(DUBBING_LABEL_PREFIX).trim()

        return candidates.firstOrNull { it.voiceStudio.equalsKey(dubbingCode) }
            ?: candidates.firstOrNull { "${it.voiceType} ${it.voiceStudio}".trim().equalsKey(label) }
            ?: candidates.firstOrNull { it.voiceStudio.equalsKey(label) }
            ?: candidates.first()
    }

    /**
     * `sources` ответа video API -> лестница mp4-качеств.
     *
     * HLS-записи не берём: манифесты CdnVideoHub содержат сегменты с сырыми IP-адресами CDN, которые
     * не переписываются на failoverHost, и HLS-загрузка ломается. Последняя пара = лучшее качество.
     */
    fun buildQualityLadder(
        sources: JSONObject,
        failoverHost: String?,
        forceFailover: Boolean,
    ): LinkedHashMap<String, String> {
        val qualities = LinkedHashMap<String, String>()
        QUALITY_KEYS.forEach { (label, key) ->
            putQuality(qualities, label, sources.optString(key), failoverHost, forceFailover)
        }
        // 1440p приходит под двумя ключами (mpegQhdUrl / mpeg2kUrl): берём один, чтобы не дублировать.
        val qhdUrl = sources.optString("mpegQhdUrl").ifBlank { sources.optString("mpeg2kUrl") }
        putQuality(qualities, "1440p", qhdUrl, failoverHost, forceFailover)
        // Подпись строго «NNNp»: «4K» общий разбор высоты принял бы за 4.
        putQuality(qualities, "2160p", sources.optString("mpeg4kUrl"), failoverHost, forceFailover)
        return qualities
    }

    private fun putQuality(
        qualities: LinkedHashMap<String, String>,
        label: String,
        url: String,
        failoverHost: String?,
        forceFailover: Boolean,
    ) {
        val sourceUrl = url.takeIf { it.isNotBlank() } ?: return
        qualities[label] = normalizeOkCdnHost(sourceUrl, failoverHost, forceFailover)
    }

    /**
     * Сырые IP-адреса CDN всегда меняются на failoverHost; именованный узел — только когда
     * [forceFailover] (перерезолв после сбоя воспроизведения на этом узле).
     */
    fun normalizeOkCdnHost(
        url: String,
        failoverHost: String?,
        forceFailover: Boolean,
    ): String {
        val host = failoverHost?.takeIf { it.isNotBlank() } ?: return url
        val currentHost = cdnHostOrNull(url) ?: return url
        if (!forceFailover && !isIpv4Host(currentHost)) return url

        return withCdnHost(url, host)
    }

    /** Хост ссылки, или null — разобрать не удалось. */
    fun cdnHostOrNull(url: String): String? = runCatching { URL(url).host }.getOrNull()

    /** Сырой IPv4-адрес вместо имени узла: такие адреса CDN меняет чаще, чем именованные. */
    fun isIpv4Host(host: String): Boolean = IPV4_HOST_REGEX.matches(host)

    /** Та же ссылка на другом узле CDN; непригодная для разбора или не-https ссылка возвращается как есть. */
    fun withCdnHost(url: String, host: String): String {
        if (host.isBlank()) return url
        val parsed = runCatching { URL(url) }.getOrNull() ?: return url
        if (!parsed.protocol.equals("https", ignoreCase = true)) return url
        return runCatching { URL(parsed.protocol, host, parsed.port, parsed.file).toString() }
            .getOrDefault(url)
    }

    private fun JSONObject.optStringOrEmpty(key: String): String =
        if (isNull(key)) "" else optString(key)

    private fun String.equalsKey(key: String): Boolean =
        key.isNotBlank() && equals(key, ignoreCase = true)
}
