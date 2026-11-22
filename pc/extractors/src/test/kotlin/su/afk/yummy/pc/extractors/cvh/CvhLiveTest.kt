// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.cvh

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import su.afk.yummy.pc.extractors.PlaylistVerifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Живой прогон CVH по настоящему iframe-URL yani (`pc/samples/balancer-samples.json`).
 *
 * Включается `EXTRACTORS_LIVE=1` (см. `docs/extractors-web.md`). CVH отдаёт mp4 на okcdn:
 * проверяем, что ссылка отвечает HTTP 2xx и что качеств не меньше одного.
 */
class CvhLiveTest {

    @Test
    fun `resolves the live iframe from balancer samples`() = runBlocking {
        assumeTrue("EXTRACTORS_LIVE=1 is required for network checks", liveEnabled())
        val resolver = CvhResolver(onStage = { println("[cvh] $it") })
        val iframeUrl = "https://ru.yummyani.me/iframeCVH.html?dubbing_code=AnimeVost" +
            "&anime_id=61967&episode=1&dubbing=%D0%9E%D0%B7%D0%B2%D1%83%D1%87%D0%BA%D0%B0+AnimeVost"
        assertTrue(resolver.supports(iframeUrl))

        val stream = resolver.resolve(iframeUrl).getOrElse { error ->
            throw AssertionError("CVH resolve failed: ${error::class.java.simpleName}: ${error.message}", error)
        }
        println("[cvh] stream: ${stream.url}")
        stream.headers.forEach { (name, value) -> println("[cvh] header: $name: $value") }
        stream.qualities.forEach { (label, url) -> println("[cvh]   $label -> $url") }
        assertTrue(stream.qualities.isNotEmpty(), "CVH must return at least one mp4 quality")
        assertTrue(stream.qualities.last().second == stream.url, "last quality pair must be the stream url")

        val probe = PlaylistVerifier().check(stream.url, stream.headers)
        println("[cvh] verify: HTTP ${probe.statusCode}, content prefix: \"${probe.prefix.take(40)}\"")
        assertTrue(probe.statusCode in 200..299, "CVH CDN returned HTTP ${probe.statusCode}")
    }

    private fun liveEnabled(): Boolean = System.getenv("EXTRACTORS_LIVE") == "1"
}
