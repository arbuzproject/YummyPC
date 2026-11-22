// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.sibnet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Офлайн-тесты чистых шагов разбора Sibnet: поиск mp4-источника в HTML и нормализация ссылок. */
class SibnetParsingTest {

    private val playerUrl = "https://video.sibnet.ru/shell.php?videoid=6181468"

    @Test
    fun `supports sibnet urls only`() {
        assertTrue(SibnetParsing.isSibnetPlayerUrl("//video.sibnet.ru/shell.php?videoid=6181468"))
        assertFalse(SibnetParsing.isSibnetPlayerUrl("https://rutube.ru/play/embed/abc"))
    }

    @Test
    fun `player src call gives the stream url`() {
        val page = """<script>player.src([{src: "/v/abc/1.mp4", type: "video/mp4"}]);</script>"""
        assertEquals(
            "https://video.sibnet.ru/v/abc/1.mp4",
            SibnetParsing.extractStreamUrl(page, playerUrl),
        )
    }

    @Test
    fun `source tag is used when there is no player src call`() {
        val page = """<video><source src="//cdn.sibnet.ru/v/2.mp4" type="video/mp4"></video>"""
        assertEquals("https://cdn.sibnet.ru/v/2.mp4", SibnetParsing.extractStreamUrl(page, playerUrl))
    }

    @Test
    fun `page without a source gives null`() {
        assertNull(SibnetParsing.extractStreamUrl("<html>no player here</html>", playerUrl))
    }

    @Test
    fun `relative url is resolved against the player url`() {
        assertEquals(
            "https://video.sibnet.ru/v/2.mp4",
            SibnetParsing.normalizeUrl("v/2.mp4", playerUrl),
        )
        assertEquals(
            "https://cdn.sibnet.ru/v/2.mp4",
            SibnetParsing.normalizeUrl("//cdn.sibnet.ru/v/2.mp4"),
        )
        assertEquals(
            "https://cdn.sibnet.ru/v/2.mp4",
            SibnetParsing.normalizeUrl("http://cdn.sibnet.ru/v/2.mp4"),
        )
        assertEquals(
            "https://cdn.sibnet.ru/v/2.mp4",
            SibnetParsing.normalizeUrl("https:\\/\\/cdn.sibnet.ru\\/v\\/2.mp4"),
        )
    }
}
