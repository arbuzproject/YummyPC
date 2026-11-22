// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Офлайн-тесты чистых шагов разбора Kodik. Живой сквозной прогон — CLI с `--verify`
 * (см. `docs/kodik.md`), здесь же фикстуры, снятые с настоящего ответа `/ftor`.
 */
class KodikParsingTest {

    @Test
    fun `normalizeIframeUrl adds scheme`() {
        assertEquals(
            "https://kodikplayer.com/season/1",
            KodikParsing.normalizeIframeUrl("//kodikplayer.com/season/1"),
        )
        assertEquals(
            "https://kodikplayer.com/season/1",
            KodikParsing.normalizeIframeUrl("kodikplayer.com/season/1"),
        )
        assertEquals(
            "https://kodikplayer.com/season/1",
            KodikParsing.normalizeIframeUrl("https://kodikplayer.com/season/1"),
        )
    }

    @Test
    fun `originOf strips path and query`() {
        assertEquals("https://kodikplayer.com", KodikParsing.originOf("https://kodikplayer.com/a/b?c=d"))
        assertEquals("https://kodikplayer.com:8443", KodikParsing.originOf("https://kodikplayer.com:8443/a"))
    }

    @Test
    fun `decodeKodikSrc decodes rot18 plus base64`() {
        assertEquals(DECODED_360, KodikParsing.decodeKodikSrc(SRC_360))
        assertEquals(DECODED_480, KodikParsing.decodeKodikSrc(SRC_720))
        assertNull(KodikParsing.decodeKodikSrc("not-base64!!"))
    }

    @Test
    fun `decodeEndpointPaths takes only short absolute paths`() {
        val script = """
            a(atob("L2Z0b3I="));
            b(atob("Ly8oPzpnZXR8Y2xvdWQpLmtvZGlrLWNkbi5jb20="));
            c(atob("YWxsdmlkZW9tZXRyaWthLmNvbS9rb2Rpa3N0YXRzLnBocA=="));
        """.trimIndent()
        assertEquals(listOf("/ftor"), KodikParsing.decodeEndpointPaths(script))
    }

    @Test
    fun `hls manifest quality is parsed and replaced`() {
        assertEquals(480, KodikParsing.hlsManifestQuality(CDN_480))
        val repaired = KodikParsing.replaceHlsManifestQuality(CDN_480, 720)
        assertEquals(720, KodikParsing.hlsManifestQuality(repaired))
        assertTrue(repaired.endsWith("/720.mp4:hls:manifest.m3u8"))
        assertNull(KodikParsing.hlsManifestQuality("https://cdn.example/720.mp4"))
    }

    @Test
    fun `parseQualityLinks maps qualities ascending and fixes protocol`() {
        val links = KodikParsing.parseQualityLinks(FTOR_RESPONSE)
        assertEquals(listOf(360, 480, 720), links.map { it.first })
        // 720p в фикстуре — тот же подписанный src, что 480p: реальное качество проверяется HEAD'ом.
        assertEquals(listOf(CDN_360, CDN_480, CDN_480), links.map { it.second })
    }

    @Test
    fun `parseQualityLinks tolerates response without links`() {
        assertTrue(KodikParsing.parseQualityLinks("""{"advert_script":"","links":null}""").isEmpty())
    }

    @Test
    fun `buildFtorBody keeps already encoded ref`() {
        val body = KodikParsing.buildFtorBody(URL_PARAMS, type = "seria", hash = HASH, id = "940184")
        assertTrue(body.contains("d=yani.tv"))
        assertTrue(body.contains("d_sign=abc%3A123"))
        assertTrue(body.contains("ref=https%3A%2F%2Fyani.tv%2F"))
        assertTrue(body.contains("type=seria"))
        assertTrue(body.contains("hash=$HASH"))
        assertTrue(body.contains("id=940184"))
        assertTrue(body.contains("info=%7B%7D"))
    }

    @Test
    fun `parseBlockedMessage extracts message div`() {
        assertEquals(
            "Видео не найдено",
            KodikParsing.parseBlockedMessage("""<div class="message">Видео не найдено</div>"""),
        )
        assertNull(KodikParsing.parseBlockedMessage("<html><body>ok</body></html>"))
    }

    private companion object {
        const val HASH = "9f8291faa668c3357e0822ba23c3a366"

        /** `links["360"][0].src` из реального ответа `/ftor`. */
        const val SRC_360 =
            "Tg9rjO91HLQck29aj2ZrHO4cG29bT3DhHFR1kOfdGEZhT2U2HONqGrk3TBQ3VhYbVLVpHA05VLHuTBZtVhZrUEQhU" +
                "rDqVq8gGhI4WLk2GEVrG2G3G2U0VhDqUrM1UrQ1U2M3Vrs2GBwgULQ2UBMgUrQeThU2UK5bkLY6iOfhWu1pjutuHFV0Tu0hlBo"

        /** `links["720"][0].src`: та же подписанная ссылка, что у 480p. */
        const val SRC_720 =
            "Tg9rjO91HLQck29aj2ZrHO4cG29bT3DhHFR1kOfdGEZhT2U2HONqGrk3TBQ3VhYbVLVpHA05VLHuTBZtVhZrUEQhU" +
                "rDqVq8gGhI4WLk2GEVrG2G3G2U0VhDqUrM1UrQ1U2M3Vrs2GBwgULQ2UBMgUrQeThY4UK5bkLY6iOfhWu1pjutuHFV0Tu0hlBo"

        const val CDN_BASE =
            "https://cloud2.solodcdn.com/useruploads/c6dabb77-2774-43ae-946f-4e74c1b325b6/" +
                "2c08876acccf7cc475b2152253a7696a:2026112220"
        const val DECODED_360 = "//cloud2.solodcdn.com/useruploads/c6dabb77-2774-43ae-946f-4e74c1b325b6/" +
            "2c08876acccf7cc475b2152253a7696a:2026112220/360.mp4:hls:manifest.m3u8"
        const val DECODED_480 = "//cloud2.solodcdn.com/useruploads/c6dabb77-2774-43ae-946f-4e74c1b325b6/" +
            "2c08876acccf7cc475b2152253a7696a:2026112220/480.mp4:hls:manifest.m3u8"
        const val CDN_360 = "$CDN_BASE/360.mp4:hls:manifest.m3u8"
        const val CDN_480 = "$CDN_BASE/480.mp4:hls:manifest.m3u8"

        const val URL_PARAMS =
            "{\"d\":\"yani.tv\",\"d_sign\":\"abc:123\",\"pd\":\"kodikplayer.com\"," +
                "\"pd_sign\":\"def:123\",\"ref\":\"https%3A%2F%2Fyani.tv%2F\"," +
                "\"ref_sign\":\"ghi:123\",\"translations\":false,\"first_url\":false}"

        val FTOR_RESPONSE =
            """{"links":{"360":[{"src":"$SRC_360"}],"480":[{"src":"$SRC_720"}],"720":[{"src":"$SRC_720"}]}}"""
    }
}
