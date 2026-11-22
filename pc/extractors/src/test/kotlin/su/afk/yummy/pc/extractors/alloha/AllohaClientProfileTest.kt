// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Профиль установки на JVM: fingerprint должен быть тем же при каждом вызове (иначе каждая сессия
 * выглядит новым устройством), а платформа — соответствовать ОС.
 */
class AllohaClientProfileTest {

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/149.0.0.0 Safari/537.36"

    @Test
    fun `profile is stable across calls and has a 64 hex fingerprint`() {
        val first = allohaInstallProfile(userAgent)
        val second = allohaInstallProfile(userAgent)

        assertEquals(first, second)
        assertEquals(64, first.fingerprint.length)
        assertTrue(first.fingerprint.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(first.screenWidth > 0 && first.screenHeight > 0)
    }

    @Test
    fun `window keeps the player aspect ratio`() {
        val profile = allohaInstallProfile(userAgent)

        assertEquals(profile.screenWidth * 9 / 16, profile.windowHeight)
    }

    @Test
    fun `different user agents give different fingerprints`() {
        val first = allohaInstallProfile(userAgent)
        val second = allohaInstallProfile(userAgent.replace("Windows NT 10.0", "X11; Linux x86_64"))

        assertTrue(first.fingerprint != second.fingerprint)
    }

    @Test
    fun `default user agent is a chrome desktop string`() {
        val userAgent = allohaDefaultUserAgent()

        assertTrue(userAgent.contains("Chrome/149"))
        assertTrue(userAgent.contains("Safari/537.36"))
    }
}
