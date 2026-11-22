// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import su.afk.yummy.pc.extractors.PlaylistVerifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Живой прогон Alloha на настоящем iframe из yani API: resolve -> HTTP-проверка плейлиста.
 *
 * Обычный `:extractors:test` в сеть не ходит (тест пропускается), потому что токены в ссылке
 * короткоживущие, а CDN Alloha режет частые прогоны с одного IP. Включается переменной окружения:
 *
 * ```
 * $env:ALLOHA_LIVE='1'
 * .\gradlew.bat --no-daemon --console=plain :extractors:test --tests "*AllohaLiveTest*"
 * ```
 *
 * `--no-daemon` нужен, чтобы тестовый worker унаследовал переменную окружения от свежего Gradle-процесса.
 * Ссылка `LIVE_IFRAME` взята из `pc/samples/balancer-samples.json` (yani API, тайтл 21747, серия 1).
 * Если токены протухли — обновить: `GET https://api.yani.tv/anime/21747/videos` с заголовками
 * `X-Application: ze645twqfeql6l1u`, `Lang: ru`, искать запись с `data.player = "Плеер Alloha"`.
 */
class AllohaLiveTest {

    @Test
    fun `live iframe resolves to a playable hls playlist`() = runBlocking {
        assumeTrue(
            "живой прогон выключен: выставьте ALLOHA_LIVE=1",
            System.getenv("ALLOHA_LIVE") == "1",
        )
        val resolver = AllohaResolver(onStage = { stage -> println("[alloha-live] $stage") })
        try {
            val result = resolver.resolve(LIVE_IFRAME)
            val stream = assertNotNull(
                result.getOrNull(),
                "Alloha resolve failed: ${result.exceptionOrNull()?.message}",
            )
            println("[alloha-live] stream: ${stream.url}")
            stream.headers.forEach { (name, value) -> println("[alloha-live] header: $name: $value") }
            stream.qualities.forEach { (label, url) -> println("[alloha-live] quality: $label -> $url") }

            assertTrue(stream.url.startsWith("https://"), "stream url must be absolute https")
            assertTrue(stream.qualities.isNotEmpty(), "stream must carry a quality ladder")
            assertEquals(stream.url, stream.qualities.last().second)
            assertTrue(stream.headers.containsKey("origin"), "CDN headers must include origin")

            val probe = PlaylistVerifier().check(stream.url, headers = stream.headers)
            println(
                "[alloha-live] verify: HTTP ${probe.statusCode}, " +
                    "first line: \"${probe.firstLine}\"",
            )
            assertEquals(200, probe.statusCode)
            assertTrue(probe.isHlsPlaylist, "CDN response must start with #EXTM3U")
        } finally {
            // В живом тесте сессию закрываем сразу: иначе WS и отчёты висят до конца процесса.
            AllohaResolver.releaseActiveSession()
        }
    }

    private companion object {
        const val LIVE_IFRAME =
            "https://alloha.yani.tv/?token_movie=bfbe2595353b6e21c502eeb82ffae3&translation=213&season=2" +
                "&episode=1&token=8b5512267a2a52e9de06d67d342e0c&hidden=translation,season,episode"
    }
}
