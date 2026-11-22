// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.rutube

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import su.afk.yummy.pc.extractors.PlaylistVerifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Живой прогон Rutube по настоящему iframe-URL из yani API.
 *
 * Rutube-балансер в yani встречается редко: найден у тайтла «Эф — история мелодий. Пролог»
 * (`GET /anime/27125/videos`, единственная серия). Тест включается `EXTRACTORS_LIVE=1`
 * (см. `docs/extractors-web.md`); без неё — SKIPPED. Проверяет, что из `video_balancer` получен
 * HLS-мастер и выбранный вариант — настоящий `#EXTM3U`-плейлист.
 */
class RutubeLiveTest {

    @Test
    fun `resolves the live yani iframe into an hls playlist`() = runBlocking {
        assumeTrue("EXTRACTORS_LIVE=1 is required for network checks", liveEnabled())
        val resolver = RutubeResolver(onStage = { println("[rutube] $it") })
        val iframeUrl = "https://rutube.ru/play/embed/$YANI_RUTUBE_VIDEO_ID"
        assertTrue(resolver.supports(iframeUrl))

        val stream = resolver.resolve(iframeUrl).getOrElse { error ->
            throw AssertionError("Rutube resolve failed: ${error::class.java.simpleName}: ${error.message}", error)
        }
        println("[rutube] stream: ${stream.url}")
        stream.headers.forEach { (name, value) -> println("[rutube] header: $name: $value") }
        stream.qualities.forEach { (label, url) -> println("[rutube]   $label -> $url") }
        assertTrue(stream.qualities.isNotEmpty(), "Rutube must return at least the auto quality")
        assertTrue(stream.qualities.last().second == stream.url, "last quality pair must be the stream url")

        val probe = PlaylistVerifier().check(stream.url, stream.headers)
        println("[rutube] verify: HTTP ${probe.statusCode}, first line: \"${probe.firstLine.take(120)}\"")
        assertTrue(probe.statusCode in 200..299, "Rutube CDN returned HTTP ${probe.statusCode}")
        assertTrue(probe.isHlsPlaylist, "Rutube stream must start with #EXTM3U")
    }

    private fun liveEnabled(): Boolean = System.getenv("EXTRACTORS_LIVE") == "1"

    private companion object {
        /** Iframe-URL балансера Rutube из `/anime/27125/videos` (аниме «Эф — история мелодий. Пролог»). */
        const val YANI_RUTUBE_VIDEO_ID = "2f22bf4308e52cf2368c3ef7a28f518c"
    }
}
