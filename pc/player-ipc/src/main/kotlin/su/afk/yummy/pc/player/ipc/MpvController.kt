// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.player.ipc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Результат одной команды mpv JSON IPC.
 *
 * @property requestId `request_id`, с которым команда ушла в mpv (0 — команда не отправлялась).
 * @property ok `true`, если mpv ответил `error: "success"`.
 * @property error код или текст ошибки mpv (`property unavailable`, `invalid parameter`, `timeout`).
 * @property data payload ответа, если он был.
 */
data class MpvCommandResult(
    val requestId: Int,
    val ok: Boolean,
    val error: String? = null,
    val data: JsonElement? = null,
)

/**
 * Управление mpv через его JSON IPC на именованном канале Windows.
 *
 * Модуль не запускает mpv: клиент обязан стартовать процесс с
 * `--input-ipc-server=\\.\pipe\<pipeName>` (рецепт — `pc/docs/player.md` §7.1), а контроллер
 * подключается к этому каналу. Реализация ввода-вывода — JNA + overlapped I/O
 * ([NamedPipeClient]): один асинхронный дескриптор, отдельные потоки чтения и записи,
 * произведения в одном дескрипторе не блокируют друг друга.
 *
 * Типичный сценарий (проверен живым прогоном, лог — `pc/docs/player-ipc.md`):
 * ```
 * val controller = MpvController(pipeName = "yummypc-mpv")
 * if (controller.connect()) {
 *     controller.loadFile(hlsUrl)   // HTTP-заголовки из httpHeaders уйдут через set_property
 *     controller.seekTo(resumeSec)
 *     controller.play()
 *     // UI подписывается на controller.state
 * }
 * // при закрытии экрана:
 * controller.quit()
 * controller.disconnect()
 * ```
 *
 * Потокобезопасность: команды можно звать из любого потока; [state] обновляется из потока
 * читателя канала и безопасно читается/коллектится из корутин.
 *
 * @property pipeName имя именованного канала mpv без префикса (`yummypc-mpv`) либо полный путь
 *   `\\.\pipe\yummypc-mpv`.
 * @property httpHeaders заголовки источника, которые нужно применить к mpv перед загрузкой файла.
 *   В аргументы процесса mpv они не попадают (процесс запускает клиент): контроллер передаёт их
 *   свойством `http-header-fields` через IPC. См. ограничения в документации модуля.
 * @property log колбэк трассировки IPC (команды, ответы, события) для логов/CLI; может быть `null`.
 */
class MpvController(
    val pipeName: String = "yummypc-mpv",
    val httpHeaders: Map<String, String> = emptyMap(),
    private val log: ((String) -> Unit)? = null,
) {

    private val _state = MutableStateFlow(MpvState())

    /** Живое состояние mpv; обновляется из событий IPC. */
    val state: StateFlow<MpvState> = _state.asStateFlow()

    private val requestIds = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<MpvMessage.Response>>()
    private val lifecycleLock = Any()

    @Volatile
    private var pipe: NamedPipeClient? = null

    @Volatile
    private var connected = false

    @Volatile
    private var headersApplied = false

    /** Подключён ли контроллер к каналу mpv. */
    val isConnected: Boolean
        get() = connected

    /**
     * Подключается к именованному каналу mpv с ретраями (mpv создаёт pipe не сразу после старта).
     *
     * После открытия канала проверяет двусторонний обмен командой `get_property idle-active` и
     * подписывается на `time-pos`, `duration`, `pause`, `eof-reached`.
     *
     * @param timeoutMs бюджет ожидания появления канала.
     * @return `true`, если канал открыт и mpv ответил на пробную команду.
     */
    fun connect(timeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS): Boolean = synchronized(lifecycleLock) {
        if (connected) return true
        val path = normalizePipePath(pipeName)
        val client = NamedPipeClient(path, log)
        client.onLine = ::handleLine
        client.onClosed = ::handlePipeClosed
        if (!client.open(timeoutMs)) return false
        pipe = client
        headersApplied = false
        val probe = sendInternal(listOf("get_property", "idle-active"), PROBE_TIMEOUT_MS)
        if (!probe.ok) {
            log?.invoke("[ipc] connect probe failed: error=${probe.error}")
            pipe = null
            client.close()
            return false
        }
        connected = true
        observeProperty(OBSERVE_ID_TIME_POS, "time-pos")
        observeProperty(OBSERVE_ID_DURATION, "duration")
        observeProperty(OBSERVE_ID_PAUSE, "pause")
        observeProperty(OBSERVE_ID_EOF_REACHED, "eof-reached")
        log?.invoke("[ipc] connected: $path")
        true
    }

    /**
     * Загружает поток в mpv (`loadfile <url> replace`).
     *
     * Если задан [httpHeaders], перед загрузкой один раз применяет их свойством
     * `http-header-fields` — mpv пробрасывает их в HTTP-запросы плейлиста и сегментов.
     *
     * @return `true`, если mpv принял команду. Ошибку открытия потока mpv сообщает позже событием
     *   `end-file` — она попадёт в [MpvState.error].
     */
    fun loadFile(url: String): Boolean {
        if (url.isBlank()) {
            log?.invoke("[ipc] loadFile: пустой URL")
            return false
        }
        if (httpHeaders.isNotEmpty() && !headersApplied) applyHttpHeaders()
        val result = sendCommand(listOf("loadfile", url, "replace"), LOADFILE_TIMEOUT_MS)
        if (result.ok) {
            _state.update {
                it.copy(
                    positionSec = 0.0,
                    durationSec = 0.0,
                    loaded = false,
                    ended = false,
                    error = null,
                )
            }
        }
        return result.ok
    }

    /** Снимает паузу (`set_property pause false`). */
    fun play(): Boolean = setPaused(false)

    /** Ставит на паузу (`set_property pause true`). */
    fun pause(): Boolean = setPaused(true)

    /** Переключает паузу: читает текущее значение и инвертирует его. */
    fun togglePause(): Boolean {
        val current = getPropertyBoolean("pause") ?: _state.value.paused
        log?.invoke("[ipc] togglePause: pause было $current")
        return setPaused(!current)
    }

    /**
     * Абсолютная перемотка (`seek <seconds> absolute`). Работает и на паузе — это проверено в
     * `pc/docs/player.md` §6.3.
     */
    fun seekTo(seconds: Double): Boolean {
        if (!seconds.isFinite() || seconds < 0.0) {
            log?.invoke("[ipc] seekTo: некорректная позиция $seconds")
            return false
        }
        val result = sendCommand(listOf("seek", seconds, "absolute"))
        if (result.ok) {
            _state.update { it.copy(positionSec = seconds, ended = false) }
        }
        return result.ok
    }

    /** Скорость воспроизведения (`set_property speed`, 1.0 — обычная). */
    fun setSpeed(speed: Double): Boolean {
        if (!speed.isFinite() || speed <= 0.0) {
            log?.invoke("[ipc] setSpeed: некорректная скорость $speed")
            return false
        }
        return sendCommand(listOf("set_property", "speed", speed)).ok
    }

    /** Громкость mpv (`set_property volume`, 0–100 и выше). */
    fun setVolume(volume: Int): Boolean =
        sendCommand(listOf("set_property", "volume", volume)).ok

    /** Универсальная установка свойства mpv (субтитры, масштаб и т.п.). */
    fun setProperty(name: String, value: Any?): Boolean =
        sendCommand(listOf("set_property", name, value)).ok

    /**
     * Просит mpv завершиться (`quit`). Ответ не ждём: mpv может закрыть канал раньше, чем
     * отправит его (см. `pc/docs/player.md` §6.1).
     */
    fun quit(): Boolean {
        val client = pipe ?: return false
        val requestId = requestIds.incrementAndGet()
        val line = MpvProtocol.command(listOf("quit"), requestId)
        log?.invoke("[ipc] -> #$requestId $line (ответ не ждём)")
        return client.write(encodeLine(line))
    }

    /**
     * Отправляет произвольную команду mpv (например, `set_property audio-delay 0.1`) и ждёт ответ.
     * Полезно для команд, которых нет в типизированном API.
     */
    fun sendCommand(
        command: List<Any?>,
        timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    ): MpvCommandResult {
        if (!connected) return MpvCommandResult(0, false, "mpv IPC is not connected")
        return sendInternal(command, timeoutMs)
    }

    /** Закрывает канал и останавливает потоки. Процесс mpv не завершает (им владеет клиент). */
    fun disconnect() {
        synchronized(lifecycleLock) {
            connected = false
            val client = pipe
            pipe = null
            client?.close()
            failPending(IOException("mpv IPC disconnected"))
        }
    }

    private fun setPaused(paused: Boolean): Boolean {
        val result = sendCommand(listOf("set_property", "pause", paused))
        if (result.ok) _state.update { it.copy(paused = paused) }
        return result.ok
    }

    private fun observeProperty(observeId: Int, propertyName: String) {
        val result = sendInternal(listOf("observe_property", observeId, propertyName), PROBE_TIMEOUT_MS)
        if (!result.ok) {
            log?.invoke("[ipc] observe $propertyName: ошибка ${result.error}")
        }
    }

    private fun applyHttpHeaders() {
        val fields = httpHeaders.map { (name, value) -> "$name: $value" }
        val result = sendInternal(listOf("set_property", "http-header-fields", fields), PROBE_TIMEOUT_MS)
        headersApplied = result.ok
        log?.invoke(
            if (result.ok) {
                "[ipc] http-header-fields: применено ${fields.size} заголовков (${fields.joinToString("; ")})"
            } else {
                "[ipc] http-header-fields: ошибка ${result.error}"
            },
        )
    }

    private fun getPropertyBoolean(name: String): Boolean? {
        val result = sendCommand(listOf("get_property", name))
        return if (result.ok) MpvProtocol.dataAsBoolean(result.data) else null
    }

    private fun sendInternal(command: List<Any?>, timeoutMs: Long): MpvCommandResult {
        val client = pipe ?: return MpvCommandResult(0, false, "mpv IPC is not connected")
        val requestId = requestIds.incrementAndGet()
        val line = MpvProtocol.command(command, requestId)
        val future = CompletableFuture<MpvMessage.Response>()
        pending[requestId] = future
        log?.invoke("[ipc] -> #$requestId $line")
        if (!client.write(encodeLine(line))) {
            pending.remove(requestId)
            log?.invoke("[ipc] -> #$requestId не отправлено: канал закрыт")
            return MpvCommandResult(requestId, false, "pipe is closed")
        }
        return try {
            val response = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            log?.invoke("[ipc] <- #$requestId error=${response.error}${formatDataSuffix(response.data)}")
            MpvCommandResult(requestId, response.ok, response.error, response.data)
        } catch (e: TimeoutException) {
            pending.remove(requestId)
            log?.invoke("[ipc] <- #$requestId timeout ${timeoutMs}ms")
            MpvCommandResult(requestId, false, "timeout ${timeoutMs}ms")
        } catch (e: ExecutionException) {
            pending.remove(requestId)
            MpvCommandResult(requestId, false, e.cause?.message ?: "pipe closed")
        } catch (e: InterruptedException) {
            pending.remove(requestId)
            Thread.currentThread().interrupt()
            MpvCommandResult(requestId, false, "interrupted")
        }
    }

    private fun handleLine(line: String) {
        when (val message = MpvProtocol.parse(line)) {
            null -> log?.invoke("[ipc] <- невалидный JSON: ${line.take(LOG_LINE_LIMIT)}")
            is MpvMessage.Response -> {
                val requestId = message.requestId
                val waiter = if (requestId != null) pending.remove(requestId) else null
                if (waiter != null) {
                    waiter.complete(message)
                } else {
                    log?.invoke("[ipc] <- ответ без ожидающего: #${requestId ?: "-"} error=${message.error}")
                }
            }
            is MpvMessage.Event -> {
                logEvent(message)
                _state.update { current -> MpvStateReducer.reduce(current, message) }
            }
            is MpvMessage.Unknown -> log?.invoke("[ipc] <- необработанное: ${message.raw.take(LOG_LINE_LIMIT)}")
        }
    }

    private fun handlePipeClosed(reason: String) {
        connected = false
        failPending(IOException("mpv IPC closed: $reason"))
    }

    private fun failPending(cause: Throwable) {
        if (pending.isEmpty()) return
        pending.values.forEach { future -> future.completeExceptionally(cause) }
        pending.clear()
    }

    /** Пишет событие в лог; частые `time-pos` не логируем, иначе лог забивается кадрами. */
    private fun logEvent(event: MpvMessage.Event) {
        val logger = log ?: return
        if (event.name == "property-change" && event.propertyName == "time-pos") return
        logger(
            buildString {
                append("[ipc] <- event ").append(event.name)
                event.propertyName?.let { append(" name=").append(it) }
                event.observeId?.let { append(" id=").append(it) }
                event.reason?.let { append(" reason=").append(it) }
                event.errorText?.let { append(" error=").append(it) }
                append(formatDataSuffix(event.data))
            },
        )
    }

    private fun formatDataSuffix(data: JsonElement?): String {
        if (data == null) return ""
        val text = data.toString()
        return " data=" + if (text.length > LOG_LINE_LIMIT) text.take(LOG_LINE_LIMIT) + "…" else text
    }

    private fun encodeLine(json: String): ByteArray = (json + "\n").toByteArray(StandardCharsets.UTF_8)

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15000L
        const val DEFAULT_COMMAND_TIMEOUT_MS = 5000L
        const val PROBE_TIMEOUT_MS = 3000L
        const val LOADFILE_TIMEOUT_MS = 10000L
        const val LOG_LINE_LIMIT = 200

        const val OBSERVE_ID_TIME_POS = 1
        const val OBSERVE_ID_DURATION = 2
        const val OBSERVE_ID_PAUSE = 3
        const val OBSERVE_ID_EOF_REACHED = 4

        /** `yummypc-mpv` → `\\.\pipe\yummypc-mpv`; готовый полный путь не трогаем. */
        fun normalizePipePath(pipeName: String): String {
            val trimmed = pipeName.trim()
            if (trimmed.startsWith(PIPE_PREFIX)) return trimmed
            return "$PIPE_PREFIX$trimmed"
        }

        const val PIPE_PREFIX = "\\\\.\\pipe\\"
    }
}
