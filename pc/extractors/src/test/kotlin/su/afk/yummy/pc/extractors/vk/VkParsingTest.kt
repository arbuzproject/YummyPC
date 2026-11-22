// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.vk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Офлайн-тесты чистых шагов разбора VK. Живой сквозной прогон — `VkLiveTest` с `EXTRACTORS_LIVE=1`
 * (см. `docs/extractors-web.md`): здесь фикстуры, повторяющие разметку страниц VK, без сети.
 */
class VkParsingTest {

    @Test
    fun `supports vk iframe wrapper and direct player urls`() {
        assertTrue(VkParsing.isVkPlayerUrl("//ru.yummyani.me/iframeVK.html?id=-234920203_456239687"))
        assertTrue(VkParsing.isVkPlayerUrl("https://vk.com/video_ext.php?oid=1&id=2&hd=1"))
        assertTrue(VkParsing.isVkPlayerUrl("https://vkvideo.ru/video-1_2"))
        assertFalse(VkParsing.isVkPlayerUrl("https://kodikplayer.com/season/1"))
    }

    @Test
    fun `video ext url is built from the wrapper id parameter`() {
        assertEquals(
            "https://vk.com/video_ext.php?oid=-234920203&id=456239687&hd=1",
            VkParsing.resolveVideoExtUrl(
                pageHtml = "<html></html>",
                iframeUrl = "//ru.yummyani.me/iframeVK.html?id=-234920203_456239687",
            ),
        )
    }

    @Test
    fun `video ext url is taken from the wrapper html when the id is absent`() {
        val html = """<iframe src="https://vk.com/video_ext.php?oid=123&id=456&hash=abc"></iframe>"""
        assertEquals(
            "https://vk.com/video_ext.php?oid=123&id=456&hd=1",
            VkParsing.resolveVideoExtUrl(html, "//ru.yummyani.me/iframeVK.html"),
        )
    }

    @Test
    fun `direct video ext url passes through`() {
        val url = "https://vk.com/video_ext.php?oid=1&id=2&hd=1"
        assertEquals(url, VkParsing.resolveVideoExtUrl("", url))
    }

    @Test
    fun `named mp4 fields become a quality ladder`() {
        val html = """
            {"mp4_720":"https://cdn.okcdn.ru/video.720.mp4",
             "mp4_480":"https:\/\/cdn.okcdn.ru\/video.480.mp4"}
        """.trimIndent()
        val candidates = VkParsing.collectCandidates(html, baseUrl = "")
        assertEquals("https://cdn.okcdn.ru/video.480.mp4", candidates["480p"])
        assertEquals("https://cdn.okcdn.ru/video.720.mp4", candidates["720p"])
    }

    @Test
    fun `hls field becomes the auto quality`() {
        val candidates = VkParsing.collectCandidates(
            """{"hls":"//vk.com/hls/master.m3u8"}""",
            baseUrl = "",
        )
        assertEquals("https://vk.com/hls/master.m3u8", candidates["auto"])
    }

    @Test
    fun `files block fields are decoded to quality labels`() {
        val html = """{"files":{"mp4_360":"https://cdn.okcdn.ru/360.mp4","url":"https://cdn.okcdn.ru/master.m3u8"},"trailer":"x"}"""
        val candidates = VkParsing.collectCandidates(html, baseUrl = "")
        assertEquals("https://cdn.okcdn.ru/360.mp4", candidates["360p"])
        assertEquals("https://cdn.okcdn.ru/master.m3u8", candidates["auto"])
    }

    @Test
    fun `quality ladder is ordered ascending with auto first`() {
        val ordered = VkParsing.orderQualities(
            linkedMapOf(
                "720p" to "https://cdn/720.mp4",
                "1080p" to "https://cdn/1080.mp4",
                "auto" to "https://cdn/master.m3u8",
            ),
        )
        assertEquals(listOf("auto", "720p", "1080p"), ordered.keys.toList())
        assertEquals("https://cdn/1080.mp4", ordered.values.last())
    }

    @Test
    fun `auto quality can be renamed`() {
        val ordered = VkParsing.orderQualities(
            linkedMapOf("auto" to "https://cdn/master.m3u8", "360p" to "https://cdn/360.mp4"),
            autoQualityLabel = "Авто",
        )
        assertEquals(listOf("Авто", "360p"), ordered.keys.toList())
    }

    @Test
    fun `vk unavailable message is read from windows-1251 bytes`() {
        val message = "Видеофайл не найден."
        val bytes = """<div id="video_ext_msg">$message</div>"""
            .toByteArray(VkParsing.VK_PAGE_CHARSET)
        assertEquals(message, VkParsing.vkUnavailableMessage(bytes))
    }

    @Test
    fun `video not found message is recognised by the russian fragment`() {
        assertTrue(VkParsing.isVkVideoNotFoundMessage("Видеофайл не найден."))
        assertFalse(VkParsing.isVkVideoNotFoundMessage("Видео доступно только друзьям."))
    }

    @Test
    fun `access denied is recognised in the prefetch cache`() {
        assertTrue(
            VkParsing.isVkAccessDenied(
                """{"error_code":204,"error_msg":"Access denied"}""",
            ),
        )
        assertFalse(VkParsing.isVkAccessDenied("""{"error_code":2040}"""))
        assertFalse(VkParsing.isVkAccessDenied("""{"error_code":14}"""))
    }

    @Test
    fun `stream like accepts media urls and named vk cdn urls`() {
        assertTrue(VkParsing.isStreamLike("https://cdn.example/master.m3u8", "auto"))
        assertTrue(VkParsing.isStreamLike("https://cdn.example/video.mp4", "auto"))
        // Именованное качество + известный CDN VK: расширения в ссылке может не быть.
        assertTrue(VkParsing.isStreamLike("https://vkvd123.okcdn.ru/abc", "720p"))
        assertFalse(VkParsing.isStreamLike("https://vkvd123.okcdn.ru/abc", "auto"))
        assertFalse(VkParsing.isStreamLike("https://example.com/cover.jpg", "720p"))
    }

    @Test
    fun `quality is read from the url and from the field name`() {
        assertEquals("720p", VkParsing.normalizeQualityFromUrl("https://cdn.example/720.mp4"))
        assertEquals("1080p", VkParsing.normalizeQualityFromUrl("https://cdn.example/1080/index.m3u8"))
        assertEquals("auto", VkParsing.normalizeQualityFromUrl("https://cdn.example/video.mp4"))

        assertEquals("720p", VkParsing.normalizeQualityLabel("mp4_720"))
        assertEquals("auto", VkParsing.normalizeQualityLabel("hls_fmp4"))
        assertEquals("auto", VkParsing.normalizeQualityLabel("url"))
        assertEquals("custom", VkParsing.normalizeQualityLabel("custom"))
    }

    @Test
    fun `relative urls are resolved against the base url`() {
        assertEquals(
            "https://vk.com/foo/master.m3u8",
            VkParsing.normalizeUrl("/foo/master.m3u8", "https://vk.com/video_ext.php?id=1"),
        )
        assertEquals(
            "https://cdn.example/video.mp4",
            VkParsing.normalizeUrl("//cdn.example/video.mp4"),
        )
        assertEquals(
            "https://cdn.example/video.mp4",
            VkParsing.normalizeUrl("http://cdn.example/video.mp4"),
        )
    }

    @Test
    fun `page diagnostics contains title and videoExt flag`() {
        val diagnostics = VkParsing.pageDiagnostics(
            "<html><title>VK</title></html>",
            viaVideoExt = true,
        )
        assertEquals("videoExt=true, title=\"VK\"", diagnostics)
    }

    @Test
    fun `parse video pair from id and text`() {
        assertEquals("-234920203" to "456239687", VkParsing.parseVideoPairFromId("-234920203_456239687"))
        assertNull(VkParsing.parseVideoPairFromId("456239687"))
        assertEquals("-1" to "2", VkParsing.parseVideoPairFromText("https://vk.com/video-1_2"))
    }

    @Test
    fun `spa page markers are recognised`() {
        assertTrue(VkParsing.isSpaPage(SPA_PAGE))
        assertFalse(VkParsing.isSpaPage("<html><body>old player</body></html>"))
        assertEquals("anonym.token-123", VkParsing.extractWebToken(SPA_PAGE))
        assertEquals("8af521fbc75aa45807dec4051065afde", VkParsing.extractVideoHash(SPA_PAGE))
    }

    @Test
    fun `player api url contains ids hash and token`() {
        val url = VkParsing.buildEmbedApiUrl(
            ownerId = "-234920203",
            videoId = "456239687",
            accessToken = "anonym.token",
            videoHash = "8af521fbc75aa45807dec4051065afde",
        )
        assertTrue(url.startsWith("https://api.vk.com/method/video.getEmbed?owner_id=-234920203"))
        assertTrue(url.contains("&video_id=456239687"))
        assertTrue(url.contains("&hash=8af521fbc75aa45807dec4051065afde"))
        assertTrue(url.contains("&v=5.289"))
        assertTrue(url.endsWith("&access_token=anonym.token"))
    }

    @Test
    fun `embed files become an ascending ladder with auto from hls`() {
        val candidates = VkParsing.parseEmbedFiles(EMBED_RESPONSE)
        assertEquals(listOf("auto", "360p", "720p"), candidates.keys.toList())
        assertEquals("https://vkvd450.okcdn.ru/video.m3u8?cmd=videoPlayerCdn", candidates["auto"])
        assertEquals("https://vkvd450.okcdn.ru/?type=3&id=1", candidates["720p"])
        // dash_sep — отдельный поток без манифеста, в лестницу не попадает.
        assertFalse(candidates.containsKey("auto_sep"))
    }

    @Test
    fun `embed response without files gives an empty ladder`() {
        assertTrue(VkParsing.parseEmbedFiles("""{"response":{"video":{}}}""").isEmpty())
        assertTrue(VkParsing.parseEmbedFiles("<html>error</html>").isEmpty())
    }

    @Test
    fun `embed api error is parsed`() {
        assertEquals(
            204 to "Access denied",
            VkParsing.parseEmbedApiError("""{"error":{"error_code":204,"error_msg":"Access denied"}}"""),
        )
        assertNull(VkParsing.parseEmbedApiError("""{"response":{"video":{}}}"""))
    }

    private companion object {
        const val SPA_PAGE = """
            <script>window.vk = { webToken: {"access_token":"anonym.token-123"}, };
            window.cur.apiPrefetchCache = [{"method":"video.getHashes",
              "response":{"csrf":"abc","video":"8af521fbc75aa45807dec4051065afde"}}];</script>
        """

        val EMBED_RESPONSE = """
            {"response":{"video":{"files":{
                "hls":"https://vkvd450.okcdn.ru/video.m3u8?cmd=videoPlayerCdn",
                "mp4_360":"https://vkvd450.okcdn.ru/?type=1&id=1",
                "mp4_720":"https://vkvd450.okcdn.ru/?type=3&id=1",
                "dash_sep":"https://vkvd450.okcdn.ru/?type=1&id=2"
            }}}}
        """
    }
}
