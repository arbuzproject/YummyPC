// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

private const val ACCEPT_LANGUAGE = "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
private val CHROME_VERSION = Regex("""Chrome/(\d+)""")

/**
 * Заголовки, которые CDN Alloha ждёт на каждом медиазапросе.
 *
 * Они должны выглядеть как один Chrome: Chrome-UA без `Accept-Language` и client hints получает 403
 * `client_blocked`, а плеер сам их не добавляет. Hints выводятся из [userAgent], чтобы оба совпадали.
 *
 * Порт `AllohaCdnHeaders.kt` из Android-версии: ключи намеренно в нижнем регистре (так их отдаёт
 * Android-версия), HTTP-имена регистронезависимы.
 */
internal fun allohaCdnHeaders(origin: String, userAgent: String): Map<String, String> = buildMap {
    put("origin", origin)
    put("referer", "$origin/")
    put("user-agent", userAgent)
    put("accept-language", ACCEPT_LANGUAGE)
    put("sec-fetch-dest", "empty")
    put("sec-fetch-mode", "cors")
    put("sec-fetch-site", "cross-site")
    CHROME_VERSION.find(userAgent)?.groupValues?.get(1)?.let { major ->
        val android = userAgent.contains("Android", ignoreCase = true)
        // Chrome на Android перечисляет бренды в таком порядке, десктопный начинает с Chromium.
        put(
            "sec-ch-ua",
            if (android) {
                "\"Google Chrome\";v=\"$major\", \"Chromium\";v=\"$major\", \"Not)A;Brand\";v=\"24\""
            } else {
                "\"Chromium\";v=\"$major\", \"Google Chrome\";v=\"$major\", \"Not_A Brand\";v=\"24\""
            },
        )
        put("sec-ch-ua-mobile", if (userAgent.contains("Mobile")) "?1" else "?0")
        put("sec-ch-ua-platform", platformHintFor(userAgent))
    }
}

private fun platformHintFor(userAgent: String): String = when {
    userAgent.contains("Android", ignoreCase = true) -> "\"Android\""
    userAgent.contains("Windows", ignoreCase = true) -> "\"Windows\""
    userAgent.contains("Macintosh", ignoreCase = true) -> "\"macOS\""
    else -> "\"Linux\""
}
