// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.api

private val BR = Regex("<br[^>]*>", RegexOption.IGNORE_CASE)
private val ANY_TAG = Regex("<[^<>]+>")
private val MANY_NEWLINES = Regex("\\n{3,}")

/** В описаниях yani приходит HTML — для десктопного текста теги убираем и раскрываем сущности. */
fun String.stripHtml(): String {
    var text = replace(BR, "\n")
    text = ANY_TAG.replace(text, "")
    text = text.replace("&nbsp;", " ")
    text = text.replace("&quot;", "\"")
    text = text.replace("&#039;", "'")
    text = text.replace("&amp;", "&")
    text = text.replace("&lt;", "<")
    text = text.replace("&gt;", ">")
    text = MANY_NEWLINES.replace(text, "\n\n")
    return text.trim()
}
