// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.player.ipc

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Клиент именованного канала Windows (`\\.\pipe\...`) поверх JNA с overlapped-вводом-выводом.
 *
 * Зачем нативный путь: `java.io.RandomAccessFile` на pipe даёт дедлок — блокирующее чтение в
 * отдельном потоке вешает запись в тот же дескриптор (см. «Ограничение IPC» в
 * `pc/docs/port-status.md`). Поэтому дескриптор открывается через `CreateFileW` с
 * `FILE_FLAG_OVERLAPPED`, а каждое чтение/запись сопровождается своим `OVERLAPPED` и событием
 * (`CreateEvent` + `WaitForSingleObject` + `GetOverlappedResult`). Потоки чтения и записи не
 * мешают друг другу: у каждого свой `OVERLAPPED`, а сам дескриптор асинхронный.
 *
 * Класс внутренний: наружу торчит только [MpvController].
 */
internal class NamedPipeClient(
    private val pipePath: String,
    private val log: ((String) -> Unit)? = null,
) {

    init {
        require(Native.POINTER_SIZE == 8) {
            "player-ipc рассчитан на 64-битную JVM: раскладка OVERLAPPED из 32 байт"
        }
    }

    private val kernel32: Kernel32 = Native.load("kernel32", Kernel32::class.java)

    @Volatile
    private var handle: Pointer? = null

    @Volatile
    private var stopRequested = false

    private val terminated = AtomicBoolean(false)
    private val writeQueue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    private var writerThread: Thread? = null

    /** Колбэк на каждую полную строку, пришедшую от mpv. */
    var onLine: ((String) -> Unit)? = null

    /** Колбэк на потерю соединения (mpv закрыл канал или ошибка ввода-вывода). */
    var onClosed: ((String) -> Unit)? = null

    /**
     * Открывает канал с ретраями: mpv создаёт pipe не мгновенно после старта процесса.
     *
     * @return `true`, если дескриптор открыт и потоки чтения/записи запущены.
     */
    fun open(timeoutMs: Long): Boolean {
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        var lastError = 0
        while (!stopRequested) {
            val candidate = kernel32.CreateFileW(
                WString(pipePath),
                GENERIC_READ or GENERIC_WRITE,
                0, // без совместного доступа: клиент у mpv один
                null,
                OPEN_EXISTING,
                FILE_FLAG_OVERLAPPED,
                null,
            )
            if (candidate != null && Pointer.nativeValue(candidate) != INVALID_HANDLE_VALUE) {
                handle = candidate
                log?.invoke("[pipe] connected: $pipePath")
                startThreads()
                return true
            }
            lastError = kernel32.GetLastError()
            if (!isRetryableOpenError(lastError)) {
                log?.invoke("[pipe] CreateFileW failed: $pipePath, error=$lastError")
                return false
            }
            if (System.nanoTime() >= deadlineNs) break
            try {
                Thread.sleep(OPEN_RETRY_DELAY_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        log?.invoke(
            "[pipe] pipe not available after ${timeoutMs} ms: $pipePath (last error=$lastError)",
        )
        return false
    }

    /** Ставит строку в очередь на запись в канал. Возвращает `false`, если канал уже закрыт. */
    fun write(bytes: ByteArray): Boolean {
        if (stopRequested || terminated.get()) return false
        if (handle == null) return false
        return writeQueue.offer(bytes)
    }

    /** Закрывает дескриптор: отменяет незавершённый ввод-вывод, останавливает потоки. */
    fun close() {
        stopRequested = true
        val h = handle
        handle = null
        if (h != null) {
            // CancelIoEx снимает подвисшие overlapped-операции во всех потоках: без него
            // читающий поток остался бы в WaitForSingleObject до бесконечности.
            kernel32.CancelIoEx(h, null)
            readerThread?.interrupt()
            writerThread?.interrupt()
            joinQuietly(readerThread)
            joinQuietly(writerThread)
            kernel32.CloseHandle(h)
        }
        readerThread = null
        writerThread = null
    }

    private fun startThreads() {
        readerThread = thread(isDaemon = true, name = "mpv-ipc-reader") { readerLoop() }
        writerThread = thread(isDaemon = true, name = "mpv-ipc-writer") { writerLoop() }
    }

    private fun readerLoop() {
        val h = handle ?: return
        val buffer = Memory(READ_BUFFER_SIZE.toLong())
        val overlapped = try {
            OverlappedBuffer(kernel32)
        } catch (e: Exception) {
            markTerminated("CreateEventW for read failed: ${e.message}")
            return
        }
        val assembler = MpvLineAssembler { line -> dispatchLine(line) }
        try {
            while (!stopRequested) {
                val read = try {
                    readSome(h, buffer, overlapped)
                } catch (e: Exception) {
                    if (!stopRequested) markTerminated("read failed: ${e.message ?: e::class.java.simpleName}")
                    return
                }
                if (read <= 0) {
                    if (!stopRequested) markTerminated("pipe closed by mpv (EOF)")
                    return
                }
                assembler.feed(buffer.getByteArray(0, read), read)
            }
        } finally {
            overlapped.close()
            buffer.close()
        }
    }

    private fun dispatchLine(line: String) {
        val callback = onLine ?: return
        try {
            callback(line)
        } catch (e: Exception) {
            // Ошибка обработчика не должна убивать читающий поток: канал остаётся рабочим.
            log?.invoke("[pipe] line handler failed: ${e.message ?: e::class.java.simpleName}")
        }
    }

    private fun writerLoop() {
        while (!stopRequested) {
            val bytes = try {
                writeQueue.poll(WRITE_QUEUE_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return
            } ?: continue
            val h = handle ?: return
            try {
                writeAll(h, bytes)
            } catch (e: Exception) {
                if (!stopRequested) markTerminated("write failed: ${e.message ?: e::class.java.simpleName}")
                return
            }
        }
    }

    private fun readSome(h: Pointer, buffer: Memory, overlapped: OverlappedBuffer): Int {
        overlapped.reset()
        val read = IntByReference(0)
        val ok = kernel32.ReadFile(h, buffer, READ_BUFFER_SIZE, read, overlapped.memory)
        if (ok) return read.value
        val error = kernel32.GetLastError()
        if (error != ERROR_IO_PENDING) {
            if (isTerminalIoError(error)) return -1
            throw IOException("ReadFile failed: error=$error")
        }
        awaitOverlapped(h, overlapped, READ_TIMEOUT_MS, "read")
        val transferred = IntByReference(0)
        if (kernel32.GetOverlappedResult(h, overlapped.memory, transferred, false)) {
            return transferred.value
        }
        val resultError = kernel32.GetLastError()
        if (isTerminalIoError(resultError)) return -1
        throw IOException("GetOverlappedResult(read) failed: error=$resultError")
    }

    private fun writeAll(h: Pointer, data: ByteArray) {
        val memory = Memory(data.size.toLong())
        memory.write(0, data, 0, data.size)
        val overlapped = OverlappedBuffer(kernel32)
        try {
            var written = 0
            while (written < data.size) {
                val remaining = data.size - written
                val source = if (written == 0) memory else memory.share(written.toLong())
                overlapped.reset()
                val count = IntByReference(0)
                val ok = kernel32.WriteFile(h, source, remaining, count, overlapped.memory)
                val n: Int
                if (ok) {
                    n = count.value
                } else {
                    val error = kernel32.GetLastError()
                    if (error != ERROR_IO_PENDING) {
                        if (isTerminalIoError(error)) throw IOException("pipe closed while writing: error=$error")
                        throw IOException("WriteFile failed: error=$error")
                    }
                    awaitOverlapped(h, overlapped, WRITE_TIMEOUT_MS, "write")
                    val transferred = IntByReference(0)
                    if (!kernel32.GetOverlappedResult(h, overlapped.memory, transferred, false)) {
                        val resultError = kernel32.GetLastError()
                        if (isTerminalIoError(resultError)) {
                            throw IOException("pipe closed while writing: error=$resultError")
                        }
                        throw IOException("GetOverlappedResult(write) failed: error=$resultError")
                    }
                    n = transferred.value
                }
                if (n <= 0) throw IOException("WriteFile wrote 0 bytes")
                written += n
            }
        } finally {
            overlapped.close()
            memory.close()
        }
    }

    private fun awaitOverlapped(
        h: Pointer,
        overlapped: OverlappedBuffer,
        timeoutMs: Int,
        operation: String,
    ) {
        val wait = kernel32.WaitForSingleObject(overlapped.event, timeoutMs)
        when {
            wait == WAIT_OBJECT_0 -> Unit
            wait == WAIT_TIMEOUT -> {
                kernel32.CancelIoEx(h, overlapped.memory)
                throw IOException("$operation timed out: $timeoutMs ms")
            }
            else -> throw IOException("$operation wait failed: $wait")
        }
    }

    private fun markTerminated(reason: String) {
        if (stopRequested) return
        if (!terminated.compareAndSet(false, true)) return
        log?.invoke("[pipe] connection lost: $reason")
        val callback = onClosed
        if (callback != null) {
            try {
                callback(reason)
            } catch (e: Exception) {
                log?.invoke("[pipe] close handler failed: ${e.message ?: e::class.java.simpleName}")
            }
        }
    }

    private fun joinQuietly(target: Thread?) {
        if (target == null || target === Thread.currentThread()) return
        try {
            target.join(THREAD_JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun isRetryableOpenError(error: Int): Boolean =
        error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND || error == ERROR_PIPE_BUSY

    /** Ошибки, которые означают «канал закрыт», а не сбой нашего кода. */
    private fun isTerminalIoError(error: Int): Boolean =
        error == ERROR_BROKEN_PIPE ||
            error == ERROR_PIPE_NOT_CONNECTED ||
            error == ERROR_OPERATION_ABORTED ||
            error == ERROR_HANDLE_EOF ||
            error == ERROR_INVALID_HANDLE

    /**
     * `OVERLAPPED` + событие ожидания. Раскладка Win32 для x64 (32 байта):
     * `Internal` (0), `InternalHigh` (8), `Offset` (16), `OffsetHigh` (20), `hEvent` (24).
     * Память выделяем вручную, без JNA `Structure`: так нет рефлексии и зависимости от набора
     * полей, а структуру можно переиспользовать между последовательными операциями.
     * Событие создаётся auto-reset (`bManualReset = false`), поэтому отдельный `ResetEvent` не нужен.
     */
    private class OverlappedBuffer(private val kernel32: Kernel32) : AutoCloseable {

        val memory: Memory = Memory(OVERLAPPED_SIZE)

        val event: Pointer = kernel32.CreateEventW(null, false, false, null) ?: throw IOException(
            "CreateEventW failed: error=${kernel32.GetLastError()}",
        )

        init {
            if (Pointer.nativeValue(event) == 0L) {
                throw IOException("CreateEventW failed: error=${kernel32.GetLastError()}")
            }
            memory.clear()
            memory.setPointer(EVENT_OFFSET, event)
        }

        /** Подготавливает структуру к новой операции; hEvent сохраняется. */
        fun reset() {
            memory.setLong(INTERNAL_OFFSET, 0L)
            memory.setLong(INTERNAL_HIGH_OFFSET, 0L)
            memory.setInt(OFFSET_OFFSET, 0)
            memory.setInt(OFFSET_HIGH_OFFSET, 0)
            memory.setPointer(EVENT_OFFSET, event)
        }

        override fun close() {
            kernel32.CloseHandle(event)
        }
    }

    /** Объявления kernel32, которые нужны для работы с именованным каналом. */
    internal interface Kernel32 : Library {
        fun CreateFileW(
            lpFileName: WString,
            dwDesiredAccess: Int,
            dwShareMode: Int,
            lpSecurityAttributes: Pointer?,
            dwCreationDisposition: Int,
            dwFlagsAndAttributes: Int,
            hTemplateFile: Pointer?,
        ): Pointer?

        fun ReadFile(
            hFile: Pointer,
            lpBuffer: Pointer,
            nNumberOfBytesToRead: Int,
            lpNumberOfBytesRead: IntByReference?,
            lpOverlapped: Pointer?,
        ): Boolean

        fun WriteFile(
            hFile: Pointer,
            lpBuffer: Pointer,
            nNumberOfBytesToWrite: Int,
            lpNumberOfBytesWritten: IntByReference?,
            lpOverlapped: Pointer?,
        ): Boolean

        fun CreateEventW(
            lpEventAttributes: Pointer?,
            bManualReset: Boolean,
            bInitialState: Boolean,
            lpName: WString?,
        ): Pointer?

        fun WaitForSingleObject(hHandle: Pointer, dwMilliseconds: Int): Int

        fun GetOverlappedResult(
            hFile: Pointer,
            lpOverlapped: Pointer,
            lpNumberOfBytesTransferred: IntByReference,
            bWait: Boolean,
        ): Boolean

        fun CancelIoEx(hFile: Pointer, lpOverlapped: Pointer?): Boolean

        fun CloseHandle(hObject: Pointer): Boolean

        fun GetLastError(): Int
    }

    private companion object {
        const val GENERIC_READ = 0x80000000.toInt()
        const val GENERIC_WRITE = 0x40000000
        const val OPEN_EXISTING = 3
        const val FILE_FLAG_OVERLAPPED = 0x40000000

        const val ERROR_FILE_NOT_FOUND = 2
        const val ERROR_PATH_NOT_FOUND = 3
        const val ERROR_INVALID_HANDLE = 6
        const val ERROR_HANDLE_EOF = 38
        const val ERROR_BROKEN_PIPE = 109
        const val ERROR_PIPE_BUSY = 231
        const val ERROR_PIPE_NOT_CONNECTED = 233
        const val ERROR_OPERATION_ABORTED = 995
        const val ERROR_IO_PENDING = 997

        const val WAIT_OBJECT_0 = 0
        const val WAIT_TIMEOUT = 258

        const val INVALID_HANDLE_VALUE = -1L

        const val OPEN_RETRY_DELAY_MS = 100L
        const val WRITE_QUEUE_POLL_MS = 200L
        const val THREAD_JOIN_TIMEOUT_MS = 1000L

        const val READ_BUFFER_SIZE = 64 * 1024

        /**
         * Чтение ждёт данные бесконечно: mpv может молчать секундами, пока грузит HLS.
         * Разбудить читающий поток при закрытии может только `CancelIoEx` (см. [close]).
         */
        const val READ_TIMEOUT_MS = -1
        const val WRITE_TIMEOUT_MS = 5000

        /** x64-раскладка `OVERLAPPED`: 8 + 8 + 4 + 4 + 8 = 32 байта. */
        const val OVERLAPPED_SIZE = 32L
        const val INTERNAL_OFFSET = 0L
        const val INTERNAL_HIGH_OFFSET = 8L
        const val OFFSET_OFFSET = 16L
        const val OFFSET_HIGH_OFFSET = 20L
        const val EVENT_OFFSET = 24L
    }
}
