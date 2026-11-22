// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.zedfilm

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import su.afk.yummy.pc.extractors.PlaylistVerifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Живой прогон Zedfilm по настоящему iframe-URL из yani API (`/anime/11932/videos`, тайтл
 * «Плеер Zedfilm»; живых Zedfilm-раздач в yani немного — см. `docs/extractors-web.md`).
 *
 * Включается `EXTRACTORS_LIVE=1`. Zedfilm отдаёт DASH `.mpd` или mp4: проверяем HTTP 2xx.
 */
class ZedfilmLiveTest {

    @Test
    fun `resolves a live zedfilm iframe`() = runBlocking {
        assumeTrue("EXTRACTORS_LIVE=1 is required for network checks", liveEnabled())
        val resolver = ZedfilmResolver(onStage = { println("[zedfilm] $it") })
        val iframeUrl = "https://zedfilm.ru/208134"
        assertTrue(resolver.supports(iframeUrl))

        val stream = resolver.resolve(iframeUrl).getOrElse { error ->
            throw AssertionError("Zedfilm resolve failed: ${error::class.java.simpleName}: ${error.message}", error)
        }
        println("[zedfilm] stream: ${stream.url}")
        stream.headers.forEach { (name, value) -> println("[zedfilm] header: $name: $value") }
        stream.qualities.forEach { (label, url) -> println("[zedfilm]   $label -> $url") }
        assertTrue(stream.qualities.isNotEmpty(), "Zedfilm must return at least one quality")
        assertTrue(stream.qualities.last().second == stream.url, "last quality pair must be the stream url")

        val probe = PlaylistVerifier().check(stream.url, stream.headers)
        println("[zedfilm] verify: HTTP ${probe.statusCode}, content prefix: \"${probe.prefix.take(40)}\"")
        assertTrue(
            probe.statusCode in 200..299 || probe.statusCode == HTTP_PARTIAL_CONTENT,
            "Zedfilm CDN returned HTTP ${probe.statusCode}",
        )
    }

    private fun liveEnabled(): Boolean = System.getenv("EXTRACTORS_LIVE") == "1"

    private companion object {
        const val HTTP_PARTIAL_CONTENT = 206
    }
}
