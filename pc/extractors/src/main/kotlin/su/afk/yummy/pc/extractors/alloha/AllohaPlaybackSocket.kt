// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * WebSocket сессии плеера Alloha: пока играет серия, страница держит сокет открытым. Он отправляет
 * `playback_start`, `init`, затем `resumed`/`paused`/`seeked` при смене состояния и `playing` каждые
 * 30 с, пока позиция движется; сервер отвечает `config_update` с `edge_hash`, который страница кладёт в
 * заголовок `Accepts-Controls` каждого медиазапроса.
 *
 * Состояние плеера сюда не передают: в Android-версии оно выводилось из позиции Media3 (`positionMs()`).
 * В ПК-порте позицию даёт [AllohaPlaybackClock] (внешний mpv не отдаёт её), поэтому «идёт
 * воспроизведение» здесь означает «после открытия сессии прошло время» — см. `docs/alloha.md`.
 *
 * Один экземпляр обслуживает одно воспроизведение: [close] при замене следующей сессией.
 */
internal class AllohaPlaybackSocket(
    client: OkHttpClient,
    private val userAgent: String,
    private val origin: String,
    private val onLog: (String) -> Unit = {},
    /** Реальная позиция воспроизведения в мс: сервер сверяет её с качающимися сегментами. */
    private val positionMs: () -> Long,
) {
    private val client: OkHttpClient = client.newBuilder()
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closed = false

    @Volatile
    private var ticket: AllohaSocketTicket? = null

    @Volatile
    private var resolution = DEFAULT_RESOLUTION

    @Volatile
    private var audioId = DEFAULT_AUDIO_ID

    private val startedAtMs = System.currentTimeMillis()
    private val firstEdgeHash = CompletableDeferred<String?>()
    private var heartbeat: Job? = null
    private var reconnect: Job? = null
    private var reconnectAttempts = 0

    @Volatile
    private var moving = false

    /** Открывает сокет и ждёт первый `edge_hash`; `null` — хеш не пришёл за отведённое время. */
    suspend fun open(ticket: AllohaSocketTicket, resolution: String, audioId: String): String? {
        this.ticket = ticket
        this.resolution = resolution
        this.audioId = audioId
        connect()
        watchPlayerState()
        heartbeat = scope.launch {
            while (isActive && !closed) {
                delay(HEARTBEAT_MS)
                if (moving) send("playing", positionSeconds())
            }
        }
        return withTimeoutOrNull(FIRST_HASH_TIMEOUT_MS) { firstEdgeHash.await() }
    }

    /** Читает состояние плеера из позиции раз в секунду и сообщает серверу об изменениях. */
    private fun watchPlayerState() {
        scope.launch {
            var last = positionMs()
            while (isActive && !closed) {
                delay(STATE_POLL_MS)
                val position = positionMs()
                val jumped = abs(position - last) > SEEK_JUMP_MS
                val nowMoving = position != last && !jumped
                last = position
                if (jumped) send("seeked", position.coerceAtLeast(0L) / 1_000L)
                if (nowMoving != moving) {
                    moving = nowMoving
                    send(if (nowMoving) "resumed" else "paused", position.coerceAtLeast(0L) / 1_000L)
                }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        reconnect?.cancel()
        heartbeat?.cancel()
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        scope.cancel()
        onLog("ws closed after ${elapsedSeconds()}s")
    }

    private fun elapsedSeconds(): Long = (System.currentTimeMillis() - startedAtMs) / 1_000L

    private fun positionSeconds(): Long = positionMs().coerceAtLeast(0L) / 1_000L

    private fun connect() {
        val ticket = ticket ?: return
        if (closed) return
        val url = "${ticket.url}?sid=${URLEncoder.encode(ticket.key, "UTF-8")}&v=2.1&t=${System.currentTimeMillis()}"
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Origin", origin)
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .build()
        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts = 0
            onLog("ws open at ${elapsedSeconds()}s")
            webSocket.send(payload("playback_start", positionSeconds()))
            webSocket.send(payload("init", positionSeconds()))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            val hash = message.optString("edge_hash").takeIf { it.isNotBlank() && it != "null" }
            if (message.optString("type") == "config_update" && hash != null) {
                if (!closed) AllohaEdgeHash.current = hash
                onLog(
                    "ws config_update at ${elapsedSeconds()}s pos=${positionSeconds()}s " +
                        "hash=${hash.take(HASH_LOG_CHARS)}",
                )
                if (!firstEdgeHash.isCompleted) firstEdgeHash.complete(hash)
            } else {
                onLog("ws message at ${elapsedSeconds()}s ${text.take(MESSAGE_LOG_CHARS)}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onLog(
                "ws failure at ${elapsedSeconds()}s ${t.javaClass.simpleName} " +
                    "http=${response?.code ?: "-"}",
            )
            if (!firstEdgeHash.isCompleted) firstEdgeHash.complete(null)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onLog("ws closed code=$code at ${elapsedSeconds()}s")
            if (!firstEdgeHash.isCompleted) firstEdgeHash.complete(null)
            if (code != NORMAL_CLOSURE) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (closed || reconnectAttempts >= MAX_RECONNECTS) return
        reconnect?.cancel()
        reconnect = scope.launch {
            val delayMs = min((1_000.0 * 1.5.pow(reconnectAttempts)).toLong(), MAX_RECONNECT_DELAY_MS)
            reconnectAttempts++
            delay(delayMs)
            connect()
        }
    }

    private fun send(type: String, timeSec: Long) {
        val sent = socket?.send(payload(type, timeSec)) ?: false
        if (!sent) scheduleReconnect()
    }

    private fun payload(type: String, timeSec: Long): String =
        """{"type":"$type","current_time":$timeSec,"resolution":"$resolution","track_id":"$audioId",""" +
            """"speed":1,"subtitle":-1,"ts":${System.currentTimeMillis()}}"""

    private companion object {
        const val ACCEPT_LANGUAGE = "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
        const val DEFAULT_RESOLUTION = "1080"
        const val DEFAULT_AUDIO_ID = "1"
        const val PING_INTERVAL_SECONDS = 20L
        const val HEARTBEAT_MS = 30_000L
        const val STATE_POLL_MS = 1_000L
        const val SEEK_JUMP_MS = 3_000L
        const val FIRST_HASH_TIMEOUT_MS = 4_000L
        const val NORMAL_CLOSURE = 1000
        const val MAX_RECONNECTS = 30
        const val MAX_RECONNECT_DELAY_MS = 15_000L
        const val HASH_LOG_CHARS = 8
        const val MESSAGE_LOG_CHARS = 120
    }
}
