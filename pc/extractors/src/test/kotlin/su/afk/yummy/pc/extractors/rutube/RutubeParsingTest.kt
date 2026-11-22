// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.rutube

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Офлайн-тесты чистых шагов разбора Rutube: id видео, поток из `video_balancer`, разбор
 * HLS-мастер-плейлиста в лестницу качеств. Сеть не нужна (живой прогон — `RutubeLiveTest`).
 */
class RutubeParsingTest {

    @Test
    fun `supports rutube urls only`() {
        assertTrue(RutubeParsing.isRutubePlayerUrl("https://rutube.ru/play/embed/$VIDEO_ID"))
        assertFalse(RutubeParsing.isRutubePlayerUrl("https://kodik.info/serial/1"))
    }

    @Test
    fun `video id is read from the embed path`() {
        assertEquals(VIDEO_ID, RutubeParsing.extractVideoId("https://rutube.ru/play/embed/$VIDEO_ID"))
        assertEquals(VIDEO_ID, RutubeParsing.extractVideoId("https://rutube.ru/video/$VIDEO_ID/"))
    }

    @Test
    fun `video id is searched in the whole url when the path has none`() {
        assertEquals(
            VIDEO_ID,
            RutubeParsing.extractVideoId("https://rutube.ru/play/embed/?v=$VIDEO_ID"),
        )
        assertNull(RutubeParsing.extractVideoId("https://rutube.ru/play/embed/not-an-id"))
    }

    @Test
    fun `options url points at the play options endpoint`() {
        assertEquals(
            "https://rutube.ru/api/play/options/$VIDEO_ID/?no_404=true",
            RutubeParsing.optionsUrl(VIDEO_ID),
        )
    }

    @Test
    fun `balancer prefers m3u8 and falls back to default`() {
        assertEquals(
            MASTER_URL,
            RutubeParsing.streamUrlFromBalancer(
                JSONObject("""{"m3u8":"$MASTER_URL","default":"https://bl.rutube.ru/default"}"""),
            ),
        )
        assertEquals(
            MASTER_URL,
            RutubeParsing.streamUrlFromBalancer(JSONObject("""{"default":"$MASTER_URL"}""")),
        )
        // JSON null у optString даёт строку "null": её нельзя считать ссылкой.
        assertEquals(
            MASTER_URL,
            RutubeParsing.streamUrlFromBalancer(
                JSONObject("""{"m3u8":null,"default":"$MASTER_URL"}"""),
            ),
        )
        assertNull(
            RutubeParsing.streamUrlFromBalancer(JSONObject("""{"m3u8":"null","default":null}""")),
        )
    }

    @Test
    fun `master variants become an ascending ladder with auto first`() {
        val qualities = RutubeParsing.buildQualityMap(
            streamUrl = MASTER_URL,
            masterPlaylist = master(
                "1280x720" to "720.m3u8",
                "640x360" to "360.m3u8",
            ),
        )
        assertEquals(listOf("auto", "360p", "720p"), qualities.keys.toList())
        assertEquals("https://bl.rutube.ru/route/abc/720.m3u8", qualities.values.last())
        assertEquals(MASTER_URL, qualities["auto"])
    }

    @Test
    fun `unknown resolution is dropped from the ladder`() {
        val qualities = RutubeParsing.buildQualityMap(
            streamUrl = MASTER_URL,
            masterPlaylist = master("1280x720" to "720.m3u8", "854x800" to "800.m3u8"),
        )
        assertEquals(listOf("auto", "720p"), qualities.keys.toList())
    }

    @Test
    fun `root relative variant resolves against the master host`() {
        val qualities = RutubeParsing.buildQualityMap(
            streamUrl = MASTER_URL,
            masterPlaylist = master("1280x720" to "/live/720.m3u8"),
        )
        assertEquals("https://bl.rutube.ru/live/720.m3u8", qualities["720p"])
    }

    @Test
    fun `absolute and protocol relative variants are normalized`() {
        val qualities = RutubeParsing.buildQualityMap(
            streamUrl = MASTER_URL,
            masterPlaylist = master(
                "1280x720" to "//edge.rutube.ru/720.m3u8",
                "640x360" to "http://edge.rutube.ru/360.m3u8",
            ),
        )
        assertEquals("https://edge.rutube.ru/720.m3u8", qualities["720p"])
        assertEquals("https://edge.rutube.ru/360.m3u8", qualities["360p"])
    }

    @Test
    fun `auto entry takes the requested label`() {
        val qualities = RutubeParsing.buildQualityMap(
            streamUrl = MASTER_URL,
            masterPlaylist = master("1280x720" to "720.m3u8"),
            autoQualityLabel = "Авто",
        )
        assertEquals(listOf("Авто", "720p"), qualities.keys.toList())
    }

    @Test
    fun `empty or broken master playlist leaves a single auto stream`() {
        val qualities = RutubeParsing.buildQualityMap(
            streamUrl = MASTER_URL,
            masterPlaylist = "<html>error</html>",
        )
        assertEquals(listOf("auto"), qualities.keys.toList())
        assertEquals(MASTER_URL, qualities["auto"])
    }

    @Test
    fun `resolution parser maps known heights only`() {
        assertEquals("720p", RutubeParsing.qualityLabelFromStreamInfo(
            """#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1280x720""",
        ))
        assertNull(RutubeParsing.qualityLabelFromStreamInfo("""#EXT-X-STREAM-INF:BANDWIDTH=1"""))
        assertNull(RutubeParsing.qualityLabelFromStreamInfo(
            """#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=854x800""",
        ))
    }

    private fun master(vararg variants: Pair<String, String>): String = buildString {
        append("#EXTM3U\n")
        variants.forEach { (resolution, uri) ->
            append("#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=$resolution\n$uri\n")
        }
    }

    private companion object {
        const val VIDEO_ID = "0123456789abcdef0123456789abcdef"
        const val MASTER_URL = "https://bl.rutube.ru/route/abc/master.m3u8"
    }
}
