// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.aksor

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import su.afk.yummy.pc.extractors.PlaylistVerifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Живой прогон Aksor по настоящему iframe-URL yani (`pc/samples/balancer-samples.json`).
 *
 * Включается `EXTRACTORS_LIVE=1` (см. `docs/extractors-web.md`). Aksor отдаёт DASH/mp4:
 * проверяем, что player API вернул качества и ссылка отвечает HTTP 2xx.
 */
class AksorLiveTest {

    @Test
    fun `resolves the live player url from balancer samples`() = runBlocking {
        assumeTrue("EXTRACTORS_LIVE=1 is required for network checks", liveEnabled())
        val resolver = AksorResolver(onStage = { println("[aksor] $it") })
        val iframeUrl = "https://player.aksor.tv/video/8d4b9ff83b37b94e542c49fa3a8d2286"
        assertTrue(resolver.supports(iframeUrl))

        val stream = resolver.resolve(iframeUrl).getOrElse { error ->
            throw AssertionError("Aksor resolve failed: ${error::class.java.simpleName}: ${error.message}", error)
        }
        println("[aksor] stream: ${stream.url}")
        stream.headers.forEach { (name, value) -> println("[aksor] header: $name: $value") }
        stream.qualities.forEach { (label, url) -> println("[aksor]   $label -> $url") }
        assertTrue(stream.qualities.isNotEmpty(), "Aksor must return at least one quality")
        assertTrue(stream.qualities.last().second == stream.url, "last quality pair must be the stream url")

        val probe = PlaylistVerifier().check(stream.url, stream.headers)
        println("[aksor] verify: HTTP ${probe.statusCode}, content prefix: \"${probe.prefix.take(40)}\"")
        assertTrue(
            probe.statusCode in 200..299 || probe.statusCode == HTTP_PARTIAL_CONTENT,
            "Aksor CDN returned HTTP ${probe.statusCode}",
        )
    }

    private fun liveEnabled(): Boolean = System.getenv("EXTRACTORS_LIVE") == "1"

    private companion object {
        const val HTTP_PARTIAL_CONTENT = 206
    }
}
