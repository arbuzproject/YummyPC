// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.vk

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import su.afk.yummy.pc.extractors.PlaylistVerifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Живой прогон VK по настоящему iframe-URL yani (`pc/samples/balancer-samples.json`).
 *
 * Сеть в обычном `:extractors:test` не трогается: тест включается переменной окружения
 * `EXTRACTORS_LIVE=1` (см. `docs/extractors-web.md`). Проверяет, что ссылка на поток отдаёт
 * HTTP 2xx: для HLS — тело с `#EXTM3U`, для mp4 — статус 200/206.
 */
class VkLiveTest {

    @Test
    fun `resolves the live iframe from balancer samples`() = runBlocking {
        assumeTrue("EXTRACTORS_LIVE=1 is required for network checks", liveEnabled())
        val resolver = VkResolver(onStage = { println("[vk] $it") })
        val iframeUrl = "https://ru.yummyani.me/iframeVK.html?id=-234920203_456239687"
        assertTrue(resolver.supports(iframeUrl))

        val stream = resolver.resolve(iframeUrl).getOrElse { error ->
            throw AssertionError("VK resolve failed: ${error::class.java.simpleName}: ${error.message}", error)
        }
        println("[vk] stream: ${stream.url}")
        stream.headers.forEach { (name, value) -> println("[vk] header: $name: $value") }
        stream.qualities.forEach { (label, url) -> println("[vk]   $label -> $url") }
        assertTrue(stream.qualities.last().second == stream.url, "last quality pair must be the stream url")

        val probe = PlaylistVerifier().check(stream.url, stream.headers)
        println("[vk] verify: HTTP ${probe.statusCode}, first line: \"${probe.firstLine.take(120)}\"")
        assertTrue(probe.statusCode in 200..299, "VK CDN returned HTTP ${probe.statusCode}")
        if (stream.url.contains(".m3u8") || probe.isHlsPlaylist) {
            assertTrue(probe.isHlsPlaylist, "HLS stream must start with #EXTM3U")
        }
    }

    private fun liveEnabled(): Boolean = System.getenv("EXTRACTORS_LIVE") == "1"
}
