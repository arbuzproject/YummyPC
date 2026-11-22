// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** `https://host:port/path` -> `host`; для логов и кэша вердиктов зеркал. */
internal fun String.toHttpUrlHost(): String = toHttpUrlOrNull()?.host ?: substringBefore('/')

/**
 * Самый свежий `edge_hash`, который выдал WebSocket плеера Alloha. CDN ждёт его в заголовке
 * `Accepts-Controls` и меняет каждые 2-6 минут, устаревший отклоняет `403 session_blocked`.
 *
 * Порт `core/common/AllohaEdgeHash.kt`. В Android-версии медиазапросы читали хеш на лету (интерцептор
 * OkHttp). В ПК-порте плеер внешний (mpv), поэтому в `ResolvedStream.headers` уходит только первый хеш,
 * полученный при открытии сессии; живой `edge_hash` здесь поддерживается для будущего loopback-прокси с
 * ротацией (в текущем порте не используется).
 */
internal object AllohaEdgeHash {
    const val HEADER = "accepts-controls"

    @Volatile
    var current: String? = null

    /** Байты медиа, которые плеер загрузил с CDN за это воспроизведение (`bytesLoadedTotal` в `/events`). */
    val bytesLoaded = java.util.concurrent.atomic.AtomicLong(0L)
}
