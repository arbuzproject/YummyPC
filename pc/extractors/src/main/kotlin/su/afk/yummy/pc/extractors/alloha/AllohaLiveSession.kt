// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Часы воспроизведения для WS Alloha. В Android-версии позицию отдавал Media3; в ПК-порте плеер внешний
 * (mpv) и позицию не сообщает, поэтому часы идут от открытия сессии по настенным часам. Паузы и
 * перемотки внешнего плеера отсюда не видны — это осознанное упрощение, см. `docs/alloha.md`.
 */
internal class AllohaPlaybackClock {
    private val startedAtNanos = System.nanoTime()

    fun positionMs(): Long = (System.nanoTime() - startedAtNanos) / 1_000_000L
}

/**
 * Живая сессия одного воспроизведения Alloha: WebSocket (свежий `edge_hash`) и HTTP-отчёты
 * `/events`/`/stat`. В Android-версии её держал `AllohaExtractor` (поля `activeSocket`/`activeReporter`);
 * в ПК-порте — [AllohaSessionKeeper], потому что `StreamResolver` отдаёт ссылку один раз и не получает
 * события «плеер закрылся».
 *
 * Сессия живёт до [close], следующего resolve или [ttlMs] (страховка от вечно висящего сокета и
 * радиоприёмника в фоне). Хост может закрыть её раньше через [AllohaResolver.releaseActiveSession].
 */
internal class AllohaLiveSession(
    private val socket: AllohaPlaybackSocket,
    private val reporter: AllohaViewReporter?,
    ttlMs: Long = SESSION_TTL_MS,
    private val onLog: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)

    init {
        scope.launch {
            delay(ttlMs)
            onLog("session ttl ${ttlMs / 1_000}s reached, closing ws")
            close()
        }
    }

    /** Открывает сокет и ждёт первый `edge_hash`; `null` — хеша за 4 с не было. */
    suspend fun open(ticket: AllohaSocketTicket, resolution: String, audioId: String): String? =
        socket.open(ticket, resolution = resolution, audioId = audioId)

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        runCatching { reporter?.close() }
        AllohaEdgeHash.current = null
        AllohaEdgeHash.bytesLoaded.set(0L)
        scope.cancel()
    }

    private companion object {
        /** Страховочный TTL: дольше серия обычно не идёт, а вечный сокет без просмотра — сигнал бота. */
        const val SESSION_TTL_MS = 45 * 60 * 1_000L
    }
}

/**
 * Держит одну активную сессию Alloha на процесс: новое открытие закрывает предыдущую (воспроизведение
 * одно за раз, как в Android-версии). На выходе JVM сессия закрывается хуком.
 */
internal object AllohaSessionKeeper {
    private val lock = Any()
    private var active: AllohaLiveSession? = null
    private var hookInstalled = false

    fun attach(session: AllohaLiveSession) {
        synchronized(lock) {
            active?.close()
            active = session
            installShutdownHook()
        }
    }

    fun release() {
        val session = synchronized(lock) {
            val current = active
            active = null
            current
        }
        session?.close()
    }

    private fun installShutdownHook() {
        if (hookInstalled) return
        hookInstalled = true
        runCatching { Runtime.getRuntime().addShutdownHook(Thread(::release)) }
    }
}
