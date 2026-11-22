// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.zedfilm

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Офлайн-тесты чистых шагов разбора Zedfilm: `video_Init` (base64 JSON), поиск ссылок на поток,
 * качества из текста, отбрасывание рекламы, нормализация экранированных URL.
 */
class ZedfilmParsingTest {

    private val playerUrl = "https://zedfilm.ru/208134"

    private fun videoInit(json: String): String =
        "video_Init('${java.util.Base64.getEncoder().encodeToString(json.toByteArray())}');"

    @Test
    fun `supports zedfilm and hlamer urls`() {
        assertTrue(ZedfilmParsing.isZedfilmPlayerUrl("https://zedfilm.ru/embed/1"))
        assertTrue(ZedfilmParsing.isZedfilmPlayerUrl("https://hlamer.ru/embed/1"))
        assertFalse(ZedfilmParsing.isZedfilmPlayerUrl("https://kodik.info/serial/1"))
    }

    @Test
    fun `video init payload is decoded from base64`() {
        val data = ZedfilmParsing.decodeVideoInit(
            videoInit("""{"url":"https://hlamer.ru/v/720.mpd","url2":"https://hlamer.ru/v/720.mp4"}"""),
        )
        assertNotNull(data)
        assertEquals("https://hlamer.ru/v/720.mpd", data.optString("url"))
        assertNull(ZedfilmParsing.decodeVideoInit("<html>no payload</html>"))
    }

    @Test
    fun `video init url wins over the fallback`() {
        val candidates = ZedfilmParsing.collectCandidates(
            videoInit("""{"url":"https://hlamer.ru/v/720.mpd","url2":"https://hlamer.ru/v/720.mp4"}"""),
            playerUrl,
        )
        assertEquals("https://hlamer.ru/v/720.mpd", candidates.values.single())
    }

    @Test
    fun `video init falls back to the second url`() {
        val candidates = ZedfilmParsing.collectCandidates(
            videoInit("""{"url":"","url2":"https://hlamer.ru/v/480.mp4"}"""),
            playerUrl,
        )
        assertEquals("https://hlamer.ru/v/480.mp4", candidates.values.single())
    }

    @Test
    fun `video init with the null text url falls back too`() {
        val candidates = ZedfilmParsing.collectCandidates(
            videoInit("""{"url":"null","url2":"https://hlamer.ru/v/480.mp4"}"""),
            playerUrl,
        )
        assertEquals("https://hlamer.ru/v/480.mp4", candidates.values.single())
    }

    @Test
    fun `page candidates form an ascending ladder`() {
        val candidates = ZedfilmParsing.collectCandidates(
            """<source src="https://cdn.example/v/360.mp4" type="video/mp4">
               <script>var player = {file: "https://cdn.example/v/720.mp4"};</script>""",
            playerUrl,
        )
        assertEquals(listOf("360p", "720p"), ZedfilmParsing.orderQualityMap(candidates).keys.toList())
        assertEquals("https://cdn.example/v/720.mp4", candidates.values.last())
    }

    @Test
    fun `escaped urls in a script are unescaped`() {
        val candidates = ZedfilmParsing.collectCandidates(
            """var cfg = {"file":"https:\/\/cdn.example\/v\/720.mp4?a=1&b=2"};""",
            playerUrl,
        )
        assertEquals("https://cdn.example/v/720.mp4?a=1&b=2", candidates.values.single())
    }

    @Test
    fun `urls without a host or scheme are completed`() {
        assertEquals(
            "https://hlamer.ru/media/720.mp4",
            ZedfilmParsing.normalizeUrl("/media/720.mp4", playerUrl),
        )
        assertEquals(
            "https://cdn.example/720.mp4",
            ZedfilmParsing.normalizeUrl("//cdn.example/720.mp4", playerUrl),
        )
        assertEquals(
            "https://cdn.example/720.mp4",
            ZedfilmParsing.normalizeUrl("http://cdn.example/720.mp4", playerUrl),
        )
    }

    @Test
    fun `1440p is not read as 144p`() {
        val candidates = ZedfilmParsing.collectCandidates(
            """<source src="https://cdn.example/v/1440p/index.m3u8">
               <source src="https://cdn.example/v/720p/index.m3u8">""",
            playerUrl,
        )
        assertEquals(listOf("720p", "1440p"), ZedfilmParsing.orderQualityMap(candidates).keys.toList())
    }

    @Test
    fun `quality is not taken from the host name`() {
        val candidates = ZedfilmParsing.collectCandidates(
            """<source src="https://s240.cdn.example/video/index.m3u8">""",
            playerUrl,
        )
        assertEquals("auto", candidates.keys.single())
    }

    @Test
    fun `legitimate urls containing ima or ads inside words are kept`() {
        val candidates = ZedfilmParsing.collectCandidates(
            """<source src="https://cdn.example/animation/360.mp4">
               <source src="https://cdn.example/uploads/720.mp4">""",
            playerUrl,
        )
        assertEquals(listOf("360p", "720p"), ZedfilmParsing.orderQualityMap(candidates).keys.toList())
    }

    @Test
    fun `advertising streams are ignored`() {
        val candidates = ZedfilmParsing.collectCandidates(
            """<source src="https://ads.example.com/promo/360.mp4">
               <source src="https://pubads.g.doubleclick.net/ad/480.mp4">
               <source src="https://cdn.example/v/720.mp4">""",
            playerUrl,
        )
        assertEquals("https://cdn.example/v/720.mp4", candidates.values.single())
        assertEquals(listOf("720p"), candidates.keys.toList())
    }

    @Test
    fun `auto quality is renamed and ordered by the standard ladder`() {
        val ordered = ZedfilmParsing.orderQualityMap(
            linkedMapOf(
                "720p" to "https://cdn/720.mp4",
                "auto" to "https://cdn/index.m3u8",
            ),
            autoQualityLabel = "Авто",
        )
        assertEquals(listOf("Авто", "720p"), ordered.keys.toList())
    }

    @Test
    fun `page without any stream gives no candidates`() {
        assertTrue(
            ZedfilmParsing.collectCandidates(
                "<html><body>player is rendered by a script</body></html>",
                playerUrl,
            ).isEmpty(),
        )
    }

    @Test
    fun `video init json can be inspected as json object`() {
        val data = ZedfilmParsing.decodeVideoInit(
            videoInit("""{"url":"https://hlamer.ru/v/720.mpd"}"""),
        )
        assertNotNull(data)
        assertEquals(setOf("url"), JSONObject(data.toString()).keySet())
    }
}
