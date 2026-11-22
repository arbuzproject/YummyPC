// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AllohaCdnHeadersTest {

    private val chromeWindows =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/149.0.0.0 Safari/537.36"

    @Test
    fun `origin referer and language go with the user agent`() {
        val headers = allohaCdnHeaders("https://alloha.yani.tv", chromeWindows)

        assertEquals("https://alloha.yani.tv", headers["origin"])
        assertEquals("https://alloha.yani.tv/", headers["referer"])
        assertEquals(chromeWindows, headers["user-agent"])
        assertEquals("ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7", headers["accept-language"])
        assertEquals("cors", headers["sec-fetch-mode"])
    }

    @Test
    fun `client hints are derived from the chrome user agent`() {
        val headers = allohaCdnHeaders("https://alloha.yani.tv", chromeWindows)

        assertEquals(
            "\"Chromium\";v=\"149\", \"Google Chrome\";v=\"149\", \"Not_A Brand\";v=\"24\"",
            headers["sec-ch-ua"],
        )
        assertEquals("?0", headers["sec-ch-ua-mobile"])
        assertEquals("\"Windows\"", headers["sec-ch-ua-platform"])
    }

    @Test
    fun `platform follows the user agent`() {
        val mac = allohaCdnHeaders(
            "https://a",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/133.0.0.0",
        )
        val linux = allohaCdnHeaders("https://a", "Mozilla/5.0 (X11; Linux x86_64) Chrome/130.0.0.0")

        assertEquals("\"macOS\"", mac["sec-ch-ua-platform"])
        assertEquals("\"Linux\"", linux["sec-ch-ua-platform"])
    }

    @Test
    fun `no client hints for a user agent that is not chrome`() {
        val headers = allohaCdnHeaders(
            "https://a",
            "Mozilla/5.0 (X11; Linux x86_64; rv:159.0) Gecko/20100101 Firefox/159.0",
        )

        assertFalse(headers.containsKey("sec-ch-ua"))
    }
}
