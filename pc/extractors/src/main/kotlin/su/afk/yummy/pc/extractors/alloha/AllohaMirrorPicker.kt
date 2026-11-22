// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Выбирает рабочий CDN из зеркал, которые Alloha перечислила для одного качества (`"url1 or url2"`).
 *
 * Зеркала пробуются по порядку, возвращается первое ответившее; вердикт кэшируется по хосту, поэтому
 * каждое зеркало за сессию пробуется один раз (упавший узел лежит для всех качеств на нём).
 *
 * Отличие от Android-версии: там зеркал было ровно два и при отказе первого брался последний. На живом
 * `bnsi` (22.11.2026) их три, и порядок разный по доступности: в прогоне первый хост не отвечал, второй
 * отвечал, третий не отвечал — выбор «последнего» давал нерабочую ссылку. Поэтому порядок перебора, а не
 * «первый/последний». Если не ответило ни одно (проба сама может блокироваться CDN), берётся первое —
 * основное зеркало сайта.
 *
 * @param isReachable отдаёт ли CDN [url] с теми заголовками, с которыми пойдёт плеер.
 */
internal class AllohaMirrorPicker(
    private val isReachable: (url: String) -> Boolean,
) {
    private val verdicts = HashMap<String, Boolean>()

    /** Зеркало, которое играть из [mirrors] (список не пуст). */
    fun pick(mirrors: List<String>): String {
        if (mirrors.size == 1) return mirrors.first()
        for (url in mirrors) {
            val works = verdicts.getOrPut(hostOf(url)) { isReachable(url) }
            if (works) return url
        }
        return mirrors.first()
    }

    private fun hostOf(url: String): String =
        url.toHttpUrlOrNull()?.host?.takeIf(String::isNotBlank)
            ?: url.substringBefore('/').takeIf(String::isNotBlank)
            ?: url
}
