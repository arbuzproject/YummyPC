// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.player.ipc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.system.exitProcess

/**
 * CLI модуля `:player-ipc`: живой сценарий управления mpv по JSON IPC.
 *
 * Сценарий: подключиться к каналу → загрузить HLS → дождаться `duration` и `file-loaded` →
 * перемотать → переключить паузу (играть/пауза) → показать обновления `MpvState` → `quit`.
 * Ничего не запускает: mpv должен быть уже запущен отдельно, например:
 *
 * ```powershell
 * tools\mpv\mpv.exe --no-config --idle=yes --pause=yes --vo=null --ao=null `
 *   --input-ipc-server=\\.\pipe\yummypc-test
 * .\gradlew.bat --console=plain :player-ipc:run --args="--url=https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8 --seek=30"
 * ```
 *
 * Коды возврата: 0 — сценарий прошёл; 1 — не подключились к каналу; 2 — неверные аргументы;
 * 3 — mpv не загрузил поток; 4 — не дождались `duration`; 5 — не сработала перемотка;
 * 6 — не сработало переключение паузы.
 */
fun main(args: Array<String>) {
    forceUtf8Stdio()
    val options = try {
        parseOptions(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("Ошибка аргументов: ${e.message}")
        printUsage()
        exitProcess(EXIT_BAD_ARGUMENTS)
    }
    if (options == null) {
        printUsage()
        exitProcess(EXIT_OK)
    }
    val exitCode = runBlocking { runScenario(options) }
    exitProcess(exitCode)
}

/**
 * Вывод CLI — UTF-8 независимо от кодовой страницы консоли/родительского процесса.
 *
 * Без этого `gradlew :player-ipc:run` на Windows печатает русский текст в кодировке консоли,
 * а лог-приёмник (Gradle/PowerShell/файл) читает его как UTF-8 и получает ромбики.
 */
private fun forceUtf8Stdio() {
    try {
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8))
        System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8))
    } catch (e: Exception) {
        // Не критично: просто останется кодировка по умолчанию.
    }
}

private suspend fun runScenario(options: CliOptions): Int = coroutineScope {
    println("[cli] player-ipc: mpv JSON IPC через JNA overlapped named pipe")
    println("[cli] pipe=${options.pipe}, url=${options.url}")
    println("[cli] seek=${formatSec(options.seekSec)} c, connect-timeout=${options.connectTimeoutMs} ms")

    val controller = MpvController(options.pipe, httpHeaders = options.headers, log = { println(it) })

    var lastStateLine = ""
    var lastStateAt = 0L
    val printer: CoroutineScope = this
    val collector = printer.launch(Dispatchers.Default) {
        controller.state.collect { state ->
            val line = formatState(state)
            if (line == lastStateLine) return@collect
            val now = System.currentTimeMillis()
            if (now - lastStateAt < STATE_PRINT_INTERVAL_MS) return@collect
            lastStateLine = line
            lastStateAt = now
            println("[state] $line")
        }
    }

    try {
        val connected = controller.connect(options.connectTimeoutMs)
        println("[cli] connect -> $connected")
        if (!connected) return@coroutineScope EXIT_CONNECT_FAILED

        val loadStarted = System.currentTimeMillis()
        val loadOk = controller.loadFile(options.url)
        println("[cli] loadfile -> $loadOk")
        if (!loadOk) return@coroutineScope EXIT_LOAD_FAILED

        val loadedState = withTimeoutOrNull(options.loadTimeoutMs) {
            controller.state.first { it.loaded || it.error != null }
        }
        if (loadedState == null) {
            System.err.println("[cli] file-loaded не пришло за ${options.loadTimeoutMs} ms")
            return@coroutineScope EXIT_LOAD_FAILED
        }
        println(
            "[cli] file-loaded за ${System.currentTimeMillis() - loadStarted} ms: " +
                formatState(loadedState),
        )
        if (loadedState.error != null) {
            System.err.println("[cli] mpv сообщил ошибку загрузки: ${loadedState.error}")
            return@coroutineScope EXIT_LOAD_FAILED
        }

        val durationState = withTimeoutOrNull(options.durationTimeoutMs) {
            controller.state.first { it.durationSec > 0.0 || it.ended || it.error != null }
        }
        if (durationState == null) {
            System.err.println("[cli] duration не пришёл за ${options.durationTimeoutMs} ms")
            return@coroutineScope EXIT_NO_DURATION
        }
        println("[cli] duration=${formatSec(durationState.durationSec)} c")

        // Даём позиции немного пройти, чтобы в логе было видно живой поток времени.
        delay(PLAYBACK_PROBE_MS)
        println("[cli] до перемотки: ${formatState(controller.state.value)}")

        val seekOk = controller.seekTo(options.seekSec)
        delay(SEEK_SETTLE_MS)
        println("[cli] seekTo(${formatSec(options.seekSec)}) -> $seekOk, ${formatState(controller.state.value)}")
        if (!seekOk) return@coroutineScope EXIT_SEEK_FAILED

        val playToggled = controller.togglePause()
        val advancedState = withTimeoutOrNull(PLAYBACK_ADVANCE_TIMEOUT_MS) {
            controller.state.first { it.positionSec > options.seekSec + 0.05 }
        }
        println(
            "[cli] togglePause -> $playToggled (сняли паузу); " +
                formatState(advancedState ?: controller.state.value),
        )
        if (advancedState == null) {
            System.err.println(
                "[cli] позиция не сдвинулась за ${PLAYBACK_ADVANCE_TIMEOUT_MS} ms " +
                    "(возможно, поток буферизуется или это live)",
            )
        }
        val pauseToggled = controller.togglePause()
        delay(PAUSE_SETTLE_MS)
        println("[cli] togglePause -> $pauseToggled (ожидаем паузу), ${formatState(controller.state.value)}")
        if (!playToggled || !pauseToggled) return@coroutineScope EXIT_PAUSE_FAILED

        println("[cli] setSpeed(1.25) -> ${controller.setSpeed(1.25)}")
        println("[cli] setVolume(70) -> ${controller.setVolume(70)}")

        println("[cli] слушаем обновления MpvState ${options.collectMs} ms")
        delay(options.collectMs)
        val finalState = controller.state.value
        println("[cli] итоговое состояние: ${formatState(finalState)}")
        println(
            "[cli] итог: connect=$connected, loadfile=$loadOk, " +
                "duration=${formatSec(durationState.durationSec)} c, seek=$seekOk, " +
                "play=$playToggled, pause=$pauseToggled, " +
                "position=${formatSec(finalState.positionSec)} c, paused=${finalState.paused}",
        )

        if (options.quit) {
            val quitSent = controller.quit()
            println("[cli] quit -> $quitSent, ждём закрытия канала")
            delay(QUIT_SETTLE_MS)
        }
        EXIT_OK
    } finally {
        collector.cancel()
        controller.disconnect()
        println("[cli] отключено; isConnected=${controller.isConnected}")
    }
}

private fun formatState(state: MpvState): String =
    "pos=${formatSec(state.positionSec)} dur=${formatSec(state.durationSec)} " +
        "paused=${state.paused} loaded=${state.loaded} ended=${state.ended} " +
        "error=${state.error ?: "none"}"

private fun formatSec(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

private fun parseOptions(args: Array<String>): CliOptions? {
    if (args.any { it == "--help" || it == "-h" || it == "help" }) return null
    var options = CliOptions()
    for (arg in args) {
        when {
            arg.startsWith("--pipe=") -> options = options.copy(pipe = arg.substringAfter('='))
            arg.startsWith("--url=") -> options = options.copy(url = arg.substringAfter('='))
            arg.startsWith("--seek=") -> options = options.copy(
                seekSec = arg.substringAfter('=').toDoubleOrNull()
                    ?: throw IllegalArgumentException("--seek: нужно число секунд"),
            )
            arg.startsWith("--timeout=") -> options = options.copy(
                connectTimeoutMs = arg.substringAfter('=').toLongOrNull()
                    ?: throw IllegalArgumentException("--timeout: нужно число миллисекунд"),
            )
            arg.startsWith("--load-timeout=") -> options = options.copy(
                loadTimeoutMs = arg.substringAfter('=').toLongOrNull()
                    ?: throw IllegalArgumentException("--load-timeout: нужно число миллисекунд"),
            )
            arg.startsWith("--duration-timeout=") -> options = options.copy(
                durationTimeoutMs = arg.substringAfter('=').toLongOrNull()
                    ?: throw IllegalArgumentException("--duration-timeout: нужно число миллисекунд"),
            )
            arg.startsWith("--collect-ms=") -> options = options.copy(
                collectMs = arg.substringAfter('=').toLongOrNull()
                    ?: throw IllegalArgumentException("--collect-ms: нужно число миллисекунд"),
            )
            arg.startsWith("--header=") -> {
                val pair = arg.removePrefix("--header=")
                val name = pair.substringBefore(':', missingDelimiterValue = "")
                val value = pair.substringAfter(':', missingDelimiterValue = "").trim()
                if (name.isBlank() || value.isBlank()) {
                    throw IllegalArgumentException("--header: ожидается \"Имя: значение\"")
                }
                options = options.copy(headers = options.headers + (name.trim() to value))
            }
            arg == "--no-quit" -> options = options.copy(quit = false)
            else -> throw IllegalArgumentException("неизвестный аргумент: $arg")
        }
    }
    return options
}

private fun printUsage() {
    println(
        """
        player-ipc CLI — управление mpv по JSON IPC (JNA overlapped named pipe).

        mpv запускается отдельно, например:
          tools\mpv\mpv.exe --no-config --idle=yes --pause=yes --vo=null --ao=null `
            --input-ipc-server=\\.\pipe\yummypc-test

        Usage:
          gradlew :player-ipc:run --args="[--pipe=ИМЯ] [--url=URL] [--seek=СЕК] [--no-quit]"

        Options:
          --pipe=ИМЯ            имя канала mpv (по умолчанию yummypc-test)
          --url=URL             HLS/медиа для loadfile (по умолчанию тестовый mux HLS)
          --seek=СЕК            позиция абсолютной перемотки (по умолчанию 30)
          --timeout=МС          ожидание подключения к каналу (по умолчанию 15000)
          --load-timeout=МС     ожидание file-loaded (по умолчанию 30000)
          --duration-timeout=МС ожидание duration (по умолчанию 30000)
          --collect-ms=МС       сколько слушать обновления MpvState (по умолчанию 4000)
          --header="Имя: знач"  HTTP-заголовок для set_property http-header-fields
          --no-quit             не завершать mpv в конце
          --help                эта справка
        """.trimIndent(),
    )
}

private data class CliOptions(
    val pipe: String = "yummypc-test",
    val url: String = DEFAULT_HLS,
    val seekSec: Double = 30.0,
    val headers: Map<String, String> = emptyMap(),
    val connectTimeoutMs: Long = 15000,
    val loadTimeoutMs: Long = 30000,
    val durationTimeoutMs: Long = 30000,
    val collectMs: Long = 4000,
    val quit: Boolean = true,
)

private const val DEFAULT_HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

private const val PLAYBACK_PROBE_MS = 1200L
private const val PLAYBACK_ADVANCE_TIMEOUT_MS = 8000L
private const val SEEK_SETTLE_MS = 800L
private const val PAUSE_SETTLE_MS = 600L
private const val QUIT_SETTLE_MS = 1000L
private const val STATE_PRINT_INTERVAL_MS = 500L

private const val EXIT_OK = 0
private const val EXIT_CONNECT_FAILED = 1
private const val EXIT_BAD_ARGUMENTS = 2
private const val EXIT_LOAD_FAILED = 3
private const val EXIT_NO_DURATION = 4
private const val EXIT_SEEK_FAILED = 5
private const val EXIT_PAUSE_FAILED = 6
