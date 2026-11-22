// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors

import kotlinx.coroutines.runBlocking

/**
 * Дымовой тест реестра: `gradlew :extractors:smoke -Purl="<iframe-url>"`.
 *
 * Печатает, какой экстрактор взял ссылку, итоговый поток и проверяет доступность плейлиста.
 */
fun main(args: Array<String>) = runBlocking {
    val targets = if (args.isNotEmpty()) args.toList() else DEFAULT_TARGETS
    println("экстракторы: ${ExtractorRegistry.ids().joinToString()}")
    for (url in targets) {
        val resolver = ExtractorRegistry.resolverFor(url)
        println("\n=== $url")
        println("резолвер: ${resolver?.id ?: "НЕ НАЙДЕН"}")
        if (resolver == null) continue
        val started = System.currentTimeMillis()
        val result = resolver.resolve(url)
        val elapsed = System.currentTimeMillis() - started
        result.fold(
            onSuccess = { stream ->
                println("OK за ${elapsed} мс")
                println("stream: ${stream.url}")
                println("headers: ${stream.headers.keys}")
                println("qualities: ${stream.qualities.joinToString { it.first }}")
            },
            onFailure = { error ->
                println("FAIL за ${elapsed} мс: ${error::class.simpleName}: ${error.message}")
            },
        )
    }
}

private val DEFAULT_TARGETS = listOf(
    "https://kodikplayer.com/season/121175/1942d12bb85cb49fc5efc91637b12170/720p?translations=false&only_episode=true&only_season=true&episode=1",
    "https://alloha.yani.tv/?token_movie=bfbe2595353b6e21c502eeb82ffae3&translation=213&season=2&episode=1&token=8b5512267a2a52e9de06d67d342e0c&hidden=translation,season,episode",
    "https://ru.yummyani.me/iframeCVH.html?dubbing_code=AnimeVost&anime_id=61967&episode=1",
    "https://ru.yummyani.me/iframeVK.html?id=-234920203_456239687",
    "https://player.aksor.tv/video/8d4b9ff83b37b94e542c49fa3a8d2286",
)
