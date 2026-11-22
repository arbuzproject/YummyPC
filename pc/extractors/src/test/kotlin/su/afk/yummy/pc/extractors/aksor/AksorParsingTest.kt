// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.aksor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Офлайн-тесты чистых шагов разбора Aksor: hash из ссылки, метки качеств, запасной разбор HTML. */
class AksorParsingTest {

    private val playerUrl = "https://player.aksor.tv/video/8d4b9ff83b37b94e542c49fa3a8d2286"

    @Test
    fun `supports aksor urls only`() {
        assertTrue(AksorParsing.isAksorPlayerUrl(playerUrl))
        assertFalse(AksorParsing.isAksorPlayerUrl("https://rutube.ru/play/embed/abc"))
    }

    @Test
    fun `hash is the segment after video`() {
        assertEquals(
            "8d4b9ff83b37b94e542c49fa3a8d2286",
            AksorParsing.extractHash(playerUrl),
        )
        assertEquals(
            "abc123",
            AksorParsing.extractHash("//player.aksor.tv/video/abc123/extra"),
        )
    }

    @Test
    fun `hash falls back to the last path segment`() {
        assertEquals("abc123", AksorParsing.extractHash("https://player.aksor.tv/abc123"))
        assertNull(AksorParsing.extractHash("https://player.aksor.tv/"))
    }

    @Test
    fun `quality labels match the android ladder`() {
        assertEquals("360p", AksorParsing.qualityLabel("q360"))
        assertEquals("1080p", AksorParsing.qualityLabel("q1080"))
        assertEquals("2K", AksorParsing.qualityLabel("q2k"))
        assertEquals("4K", AksorParsing.qualityLabel("q4k"))
    }

    @Test
    fun `spaces in stream urls are percent encoded`() {
        assertEquals(
            "https://c/JAM%20CLUB/720.mp4",
            AksorParsing.sanitizeStreamUrl("https://c/JAM CLUB/720.mp4"),
        )
        assertEquals("https://c/720.mp4", AksorParsing.sanitizeStreamUrl("https://c/720.mp4"))
    }

    @Test
    fun `meta video url is extracted except templates`() {
        assertEquals(
            "https://c/JAM CLUB/m.mp4",
            AksorParsing.extractMetaVideoUrl(
                """<meta name="video_url" content="https://c/JAM CLUB/m.mp4">""",
            ),
        )
        assertNull(
            AksorParsing.extractMetaVideoUrl(
                """<meta name="video_url" content="{{ video.url }}">""",
            ),
        )
        assertNull(AksorParsing.extractMetaVideoUrl("<html>nothing</html>"))
    }

    @Test
    fun `script urls are made absolute`() {
        val urls = AksorParsing.scriptUrls(
            """<script src="/static/app.js"></script><script src="//cdn.aksor.tv/x.js"></script>""",
        )
        assertEquals(
            listOf(
                "https://player.aksor.tv/static/app.js",
                "https://cdn.aksor.tv/x.js",
            ),
            urls,
        )
    }

    @Test
    fun `api path is read from a player script`() {
        assertEquals(
            "https://api.aksor.tv/api",
            AksorParsing.apiPathFromScript("""const base = "https://api.aksor.tv/api";"""),
        )
        assertNull(AksorParsing.apiPathFromScript("console.log(1)"))
    }

    @Test
    fun `player url is normalized to https`() {
        assertEquals(
            "https://player.aksor.tv/video/abc",
            AksorParsing.normalizeUrl("//player.aksor.tv/video/abc"),
        )
        assertEquals(
            "https://player.aksor.tv/video/abc",
            AksorParsing.normalizeUrl("http://player.aksor.tv/video/abc"),
        )
    }
}
