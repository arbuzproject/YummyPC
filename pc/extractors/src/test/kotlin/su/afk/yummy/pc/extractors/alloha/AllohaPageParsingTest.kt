// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Офлайн-тесты разбора страницы плеера Alloha и ответа `bnsi`. Фикстуры сняты с настоящей страницы
 * (22.11.2026), поэтому регэкспы проверяются на реальной разметке, а не на выдуманном HTML.
 */
class AllohaPageParsingTest {

    @Test
    fun `viewporti seed comes from the meta tag`() {
        val page = """
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="viewporti" content="F39Y8l115Y1KZcIR5zZZPRzgWE2VEmkEWDDXNFMx1NlUlRN58z6XiuyWRMTbW1llETTDXVG1SULaRYeUNOMUSMUV">
        """.trimIndent()

        assertEquals(
            "F39Y8l115Y1KZcIR5zZZPRzgWE2VEmkEWDDXNFMx1NlUlRN58z6XiuyWRMTbW1llETTDXVG1SULaRYeUNOMUSMUV",
            AllohaPageParsing.parseViewporti(page),
        )
        assertNull(AllohaPageParsing.parseViewporti("<html></html>"))
    }

    @Test
    fun `active media id comes from the escaped fileList json`() {
        val page = """
            const fileList = JSON.parse('{"type":"serial","active":{"id":1747941,"id_file":null,
            "seasons":2,"episode":1,"translation":"AnimeVost","id_translation":213}}');
        """.trimIndent()

        assertEquals(1747941L, AllohaPageParsing.parseActiveId(page))
        assertNull(AllohaPageParsing.parseActiveId("<html></html>"))
    }

    @Test
    fun `iframe token and origin are read from the player url`() {
        assertEquals("8b5512267a2a52e9de06d67d342e0c", AllohaPageParsing.iframeToken(LIVE_IFRAME))
        assertEquals("https://alloha.yani.tv", AllohaPageParsing.originOf(LIVE_IFRAME))
        assertEquals("https://alloha.yani.tv", AllohaPageParsing.originOf("https://ALLOHA.YANI.TV/?token=x"))
        assertNull(AllohaPageParsing.iframeToken("https://alloha.yani.tv/?season=2"))
    }

    @Test
    fun `iframe url without scheme is normalized`() {
        assertEquals("https://alloha.yani.tv/?token=x", AllohaPageParsing.normalizeIframeUrl("//alloha.yani.tv/?token=x"))
        assertEquals("https://alloha.yani.tv/?token=x", AllohaPageParsing.normalizeIframeUrl("alloha.yani.tv/?token=x"))
        assertEquals("https://alloha.yani.tv/?token=x", AllohaPageParsing.normalizeIframeUrl("https://alloha.yani.tv/?token=x"))
    }

    @Test
    fun `app script path becomes an absolute url on the page origin`() {
        val page = """<script src="/build/app.03ac6d63.js"></script>"""
        val path = AllohaPageParsing.parseAppScriptPath(page)

        assertEquals("/build/app.03ac6d63.js", path)
        assertEquals(
            "https://alloha.yani.tv/build/app.03ac6d63.js",
            AllohaPageParsing.absoluteScriptUrl("/build/app.03ac6d63.js", "https://alloha.yani.tv"),
        )
        assertEquals(
            "https://cdn.example/app.1.js",
            AllohaPageParsing.absoluteScriptUrl("https://cdn.example/app.1.js", "https://alloha.yani.tv"),
        )
        assertNull(AllohaPageParsing.parseAppScriptPath("<script src=\"/js/rmp-vast.min.js\"></script>"))
    }

    @Test
    fun `authorization key is a long alnum constant in the site script`() {
        val key = "pXzvbyDGLYyB6VkwsWZD" + "a".repeat(360)
        assertEquals(380, key.length)
        assertEquals(key, AllohaPageParsing.parseApiKey("""var k='$key';"""))
        assertNull(AllohaPageParsing.parseApiKey("var k='short';"))
    }

    @Test
    fun `userParam gives the report token and domain`() {
        val page = """
            userParam = {
            		token: '8b5512267a2a52e9de06d67d342e0c',
            		domain: 'https%3A%2F%2Fru.yummyani.me%2F',
                device: 0,
            		selector: 2
            }
        """.trimIndent()

        val param = AllohaPageParsing.parseUserParam(page)

        assertEquals("8b5512267a2a52e9de06d67d342e0c", param?.token)
        assertEquals("https%3A%2F%2Fru.yummyani.me%2F", param?.domain)
        assertNull(AllohaPageParsing.parseUserParam("<html></html>"))
    }

    @Test
    fun `view ticket ties userParam to the active file id`() {
        val page = """userParam = { token: 'tok', domain: 'dom' }"""

        val ticket = AllohaPageParsing.parseViewTicket(page, 1747941L)

        assertEquals("tok", ticket?.token)
        assertEquals("dom", ticket?.domain)
        assertEquals("1747941", ticket?.fileId)
    }

    @Test
    fun `socket ticket comes from pnr and pnk of the bnsi response`() {
        val bnsi = """{"hlsSource":[],"pnr":"wss://alloha.yani.tv/ws/","pnk":"avvE8UrVj6JW"}"""

        val ticket = AllohaPageParsing.parseSocketTicket(bnsi)

        assertEquals("wss://alloha.yani.tv/ws/", ticket?.url)
        assertEquals("avvE8UrVj6JW", ticket?.key)
        assertNull(AllohaPageParsing.parseSocketTicket("""{"hlsSource":[]}"""))
        assertNull(AllohaPageParsing.parseSocketTicket("<html>"))
    }

    @Test
    fun `a real page carries every marker the opener needs`() {
        // Минимальная склейка маркеров, как они встречаются на живой странице.
        val page = """
            <meta name="viewporti" content="SEED123">
            const fileList = JSON.parse('{\"type\":\"serial\",\"active\":{\"id\":1747941}}');
            <script src="/build/app.03ac6d63.js"></script>
            userParam = { token: 'tok', domain: 'dom' }
        """.trimIndent()

        assertTrue(AllohaPageParsing.parseViewporti(page) != null)
        assertTrue(AllohaPageParsing.parseActiveId(page) != null)
        assertTrue(AllohaPageParsing.parseAppScriptPath(page) != null)
        assertTrue(AllohaPageParsing.parseUserParam(page) != null)
    }

    private companion object {
        const val LIVE_IFRAME =
            "https://alloha.yani.tv/?token_movie=bfbe2595353b6e21c502eeb82ffae3&translation=213&season=2" +
                "&episode=1&token=8b5512267a2a52e9de06d67d342e0c&hidden=translation,season,episode"
    }
}
