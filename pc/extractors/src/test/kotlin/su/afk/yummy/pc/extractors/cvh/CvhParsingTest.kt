// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.cvh

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Офлайн-тесты чистых шагов разбора CVH: query, плейлист, выбор озвучки/серии, регион-блок,
 * лестница mp4-качеств и подмена узла okcdn. Сеть не нужна (живой прогон — `CvhLiveTest`).
 */
class CvhParsingTest {

    private fun item(
        vkId: String,
        voiceStudio: String,
        voiceType: String = "Дубляж",
        episode: Int? = null,
    ) = CvhPlaylistItem(
        vkId = vkId,
        voiceStudio = voiceStudio,
        voiceType = voiceType,
        episode = episode,
    )

    @Test
    fun `query is parsed and url decoded`() {
        val params = CvhParsing.parseQuery(
            "dubbing_code=AnimeVost&anime_id=61967&episode=1&dubbing=%D0%9E%D0%B7%D0%B2%D1%83%D1%87%D0%BA%D0%B0+AnimeVost",
        )
        assertEquals("AnimeVost", params["dubbing_code"])
        assertEquals("61967", params["anime_id"])
        assertEquals("1", params["episode"])
        assertEquals("Озвучка AnimeVost", params["dubbing"])
    }

    @Test
    fun `playlist is flattened from json`() {
        val playlist = CvhParsing.parsePlaylist(
            JSONObject(
                """{"isSerial":true,"tags":[1,5],
                   "items":[{"vkId":"vk1","voiceStudio":"AniLibria","voiceType":"Озвучка","episode":2},
                            {"vkId":"vk2","voiceStudio":null,"episode":null}]}""",
            ),
        )
        assertEquals(listOf(1, 5), playlist.tags)
        assertTrue(playlist.isSerial)
        assertTrue(playlist.hasItems)
        assertEquals(2, playlist.items?.size)
        assertEquals("vk1", playlist.items?.get(0)?.vkId)
        assertEquals("", playlist.items?.get(1)?.voiceStudio)
        assertNull(playlist.items?.get(1)?.episode)
    }

    @Test
    fun `playlist without items field is distinguishable from an empty list`() {
        val missing = CvhParsing.parsePlaylist(JSONObject("""{"tags":[]}"""))
        assertNull(missing.items)
        assertFalse(missing.hasItems)

        val empty = CvhParsing.parsePlaylist(JSONObject("""{"items":[]}"""))
        assertTrue(empty.items?.isEmpty() == true)
        assertFalse(empty.hasItems)
    }

    @Test
    fun `empty playlist with the blocked tag is a region block`() {
        assertTrue(CvhParsing.isCvhRegionBlocked(tags = listOf(5), hasItems = false))
        assertTrue(CvhParsing.isCvhRegionBlocked(tags = listOf(1, 3, 5), hasItems = false))
        assertFalse(CvhParsing.isCvhRegionBlocked(tags = listOf(5), hasItems = true))
        assertFalse(CvhParsing.isCvhRegionBlocked(tags = emptyList(), hasItems = false))
        assertFalse(CvhParsing.isCvhRegionBlocked(tags = listOf(1, 3), hasItems = false))
    }

    @Test
    fun `movie item without an episode field is still selected`() {
        val movie = item(vkId = "9199690144362", voiceStudio = "ОРТ")
        val selected = CvhParsing.selectCvhItem(
            items = listOf(movie),
            isSerial = false,
            episodeNum = 1,
            dubbingCode = "ort",
            dubbingLabel = "Озвучка Дубляж ОРТ",
        )
        assertEquals(movie, selected)
    }

    @Test
    fun `transliterated dubbing code falls back to the dubbing label`() {
        val ort = item(vkId = "ort", voiceStudio = "ОРТ", episode = 1)
        val other = item(vkId = "other", voiceStudio = "AniDUB", voiceType = "Многоголосый", episode = 1)
        val selected = CvhParsing.selectCvhItem(
            items = listOf(other, ort),
            isSerial = true,
            episodeNum = 1,
            dubbingCode = "ort",
            dubbingLabel = "Озвучка Дубляж ОРТ",
        )
        assertEquals(ort, selected)
    }

    @Test
    fun `serial picks the requested voice studio and not just the first item`() {
        val star = item(vkId = "star", voiceStudio = "AniStar", episode = 10)
        val libria = item(vkId = "libria", voiceStudio = "AniLibria", episode = 10)
        val dub = item(vkId = "dub", voiceStudio = "AniDUB", voiceType = "Многоголосый", episode = 10)
        val selected = CvhParsing.selectCvhItem(
            items = listOf(star, libria, dub),
            isSerial = true,
            episodeNum = 10,
            dubbingCode = "AniLibria",
            dubbingLabel = "Озвучка AniLibria",
        )
        assertEquals(libria, selected)
    }

    @Test
    fun `serial ignores items of other episodes`() {
        val selected = CvhParsing.selectCvhItem(
            items = listOf(
                item(vkId = "e9", voiceStudio = "AniLibria", episode = 9),
                item(vkId = "e11", voiceStudio = "AniLibria", episode = 11),
            ),
            isSerial = true,
            episodeNum = 10,
            dubbingCode = "AniLibria",
            dubbingLabel = "Озвучка AniLibria",
        )
        assertNull(selected)
    }

    @Test
    fun `unknown voice falls back to the first candidate of the requested episode`() {
        val firstOfEpisode = item(vkId = "star", voiceStudio = "AniStar", episode = 10)
        val selected = CvhParsing.selectCvhItem(
            items = listOf(
                item(vkId = "e9", voiceStudio = "AniLibria", episode = 9),
                firstOfEpisode,
                item(vkId = "dub", voiceStudio = "AniDUB", episode = 10),
            ),
            isSerial = true,
            episodeNum = 10,
            dubbingCode = "SomethingGone",
            dubbingLabel = "Озвучка SomethingGone",
        )
        assertEquals(firstOfEpisode, selected)
    }

    @Test
    fun `blank dubbing hints do not match an empty voice studio`() {
        val subtitles = item(vkId = "subs", voiceStudio = "", voiceType = "Субтитры", episode = 1)
        val libria = item(vkId = "libria", voiceStudio = "AniLibria", episode = 1)
        val selected = CvhParsing.selectCvhItem(
            items = listOf(subtitles, libria),
            isSerial = true,
            episodeNum = 1,
            dubbingCode = "AniLibria",
            dubbingLabel = "",
        )
        assertEquals(libria, selected)
    }

    @Test
    fun `mp4 ladder is ascending with the best quality as the last entry`() {
        val qualities = CvhParsing.buildQualityLadder(
            sources = JSONObject(
                """{"mpegFullHdUrl":"https://vh.okcdn.ru/f.mp4?s=1",
                    "mpegLowUrl":"https://vh.okcdn.ru/l.mp4?s=1",
                    "mpegHighUrl":"https://vh.okcdn.ru/h.mp4?s=1"}""",
            ),
            failoverHost = null,
            forceFailover = false,
        )
        assertEquals(listOf("360p", "720p", "1080p"), qualities.keys.toList())
        assertEquals("https://vh.okcdn.ru/f.mp4?s=1", qualities.values.last())
    }

    @Test
    fun `ladder includes 1440 and 4k with 4k last`() {
        val qualities = CvhParsing.buildQualityLadder(
            sources = JSONObject(
                """{"mpegFullHdUrl":"https://vh.okcdn.ru/f.mp4",
                    "mpeg2kUrl":"https://vh.okcdn.ru/2k.mp4",
                    "mpeg4kUrl":"https://vh.okcdn.ru/4k.mp4"}""",
            ),
            failoverHost = null,
            forceFailover = false,
        )
        assertEquals(listOf("1080p", "1440p", "2160p"), qualities.keys.toList())
        assertEquals("https://vh.okcdn.ru/4k.mp4", qualities["2160p"])
    }

    @Test
    fun `qhd key wins over 2k so 1440p is not duplicated`() {
        val qualities = CvhParsing.buildQualityLadder(
            sources = JSONObject(
                """{"mpegQhdUrl":"https://vh.okcdn.ru/qhd.mp4","mpeg2kUrl":"https://vh.okcdn.ru/2k.mp4"}""",
            ),
            failoverHost = null,
            forceFailover = false,
        )
        assertEquals(mapOf("1440p" to "https://vh.okcdn.ru/qhd.mp4"), qualities)
    }

    @Test
    fun `raw ip links are moved to the failover host even without a refresh`() {
        val qualities = CvhParsing.buildQualityLadder(
            sources = JSONObject("""{"mpegLowUrl":"https://10.1.2.3/l.mp4?s=1"}"""),
            failoverHost = "vh2.okcdn.ru",
            forceFailover = false,
        )
        assertEquals("https://vh2.okcdn.ru/l.mp4?s=1", qualities["360p"])
    }

    @Test
    fun `named host links move to the failover host only on refresh`() {
        val sources = JSONObject("""{"mpegLowUrl":"https://vh.okcdn.ru/l.mp4?s=1"}""")
        val normal = CvhParsing.buildQualityLadder(sources, "vh2.okcdn.ru", forceFailover = false)
        val refreshed = CvhParsing.buildQualityLadder(sources, "vh2.okcdn.ru", forceFailover = true)
        assertEquals("https://vh.okcdn.ru/l.mp4?s=1", normal["360p"])
        assertEquals("https://vh2.okcdn.ru/l.mp4?s=1", refreshed["360p"])
    }

    @Test
    fun `blank source urls are skipped`() {
        val qualities = CvhParsing.buildQualityLadder(
            sources = JSONObject("""{"mpegLowestUrl":"","mpegLowUrl":"   ","mpegHighUrl":"https://vh/720.mp4"}"""),
            failoverHost = null,
            forceFailover = false,
        )
        assertEquals(listOf("720p"), qualities.keys.toList())
    }

    @Test
    fun `host utilities detect ipv4 and replace the host`() {
        assertTrue(CvhParsing.isIpv4Host("10.1.2.3"))
        assertFalse(CvhParsing.isIpv4Host("vh.okcdn.ru"))
        assertEquals("vh2.okcdn.ru", CvhParsing.cdnHostOrNull("https://vh2.okcdn.ru/l.mp4"))
        assertEquals(
            "https://vh2.okcdn.ru/l.mp4?x=1",
            CvhParsing.withCdnHost("https://10.1.2.3/l.mp4?x=1", "vh2.okcdn.ru"),
        )
    }
}
