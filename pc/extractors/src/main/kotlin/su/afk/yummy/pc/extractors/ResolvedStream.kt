// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors

/**
 * Результат разрешения iframe-ссылки балансера в прямой поток для внешнего плеера (mpv).
 *
 * @property url прямой URL потока: HLS-плейлист (`*.m3u8`) или mp4.
 * @property headers заголовки, которые нужно передать плееру вместе с [url].
 * @property qualities качества по возрастанию: подпись (`"480p"`) -> URL. Последняя пара — то же
 *   самое, что [url]. Разные подписи могут указывать на один URL, если Kodik отдал только одно
 *   реальное качество (после HEAD-проверки манифестов).
 */
data class ResolvedStream(
    val url: String,
    val headers: Map<String, String>,
    val qualities: List<Pair<String, String>>,
)
