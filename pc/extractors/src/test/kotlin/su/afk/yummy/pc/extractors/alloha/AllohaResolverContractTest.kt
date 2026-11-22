// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Контракт [AllohaResolver] как экстрактора реестра: распознавание ссылки и имя для логов/UI. */
class AllohaResolverContractTest {

    private val resolver = AllohaResolver()

    @Test
    fun `supports only alloha iframe urls`() {
        assertTrue(resolver.supports(LIVE_IFRAME))
        assertTrue(resolver.supports("//alloha.yani.tv/?token_movie=x"))
        assertFalse(resolver.supports("https://kodikplayer.com/season/1/abc/720p?episode=1"))
        assertFalse(resolver.supports("https://ru.yummyani.me/iframeVK.html?id=1"))
    }

    @Test
    fun `id is the balancer name used in logs`() {
        assertEquals("Alloha", resolver.id)
    }

    private companion object {
        const val LIVE_IFRAME =
            "https://alloha.yani.tv/?token_movie=bfbe2595353b6e21c502eeb82ffae3&translation=213&season=2" +
                "&episode=1&token=8b5512267a2a52e9de06d67d342e0c&hidden=translation,season,episode"
    }
}
