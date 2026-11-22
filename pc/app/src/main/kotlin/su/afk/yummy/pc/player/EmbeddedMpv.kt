// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Встроенный плеер: процесс mpv, привязанный к HWND AWT-канвы (`--wid`), + IPC-управление.
 *
 * Флаги взяты из проверенного рецепта `pc/docs/player.md` §7.1: D3D11-вывод, аппаратное
 * декодирование, без собственного окна и OSD (интерфейс рисует Compose). Если модуль `:player-ipc`
 * подключён, команды (пауза/перемотка/скорость) и состояние идут через JSON IPC.
 */
class EmbeddedMpv(
    private val hwnd: Long,
    private val headers: Map<String, String>,
    private val referer: String?,
    private val title: String,
) {

    private var process: Process? = null
    private var bridge: MpvIpcBridge? = null

    private val fallbackState = MutableStateFlow(MpvPlaybackState())
    val state: StateFlow<MpvPlaybackState>
        get() = bridge?.state ?: fallbackState.asStateFlow()

    val logFile: File
        get() {
            val dir = File(System.getProperty("user.home"), ".yummypc").apply { mkdirs() }
            return File(dir, "mpv-embedded.log")
        }

    fun start(url: String): Result<Unit> = runCatching {
        val mpv = MpvLauncher.findMpv() ?: error("mpv не найден: tools/mpv/mpv.exe")
        // Имя канала уникально на инстанс: старый mpv может ещё держать предыдущий pipe.
        val pipe = "yummypc-mpv-${ProcessHandle.current().pid()}-${System.nanoTime()}"
        val args = mutableListOf(
            "--no-config",
            "--terminal=no",
            "--input-ipc-server=\\\\.\\pipe\\$pipe",
            "--wid=$hwnd",
            "--vo=gpu",
            "--gpu-api=d3d11",
            "--hwdec=auto-safe",
            "--force-window=no",
            "--idle=yes",
            "--pause=yes",
            "--keep-open=no",
            "--osc=yes",
            "--script-opts=osc-layout=bottom,osc-seekbarstyle=bar,osc-deadzonesize=0.5,osc-fade=200,osc-hidetimeout=2500,osc-visibility=auto",
            "--osd-level=1",
            "--audio-client-name=YummyPC",
            "--title=$title",
        )
        headers.forEach { (key, value) -> args += "--http-header-fields-append=$key: $value" }
        referer?.let { args += "--referrer=$it" }
        args += url

        val log = logFile
        log.writeText(
            buildString {
                appendLine("# ${java.time.LocalDateTime.now()}")
                appendLine("# wid=$hwnd")
                args.forEach { appendLine("# arg: $it") }
            },
        )

        process = ProcessBuilder(listOf(mpv.absolutePath) + args)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()

        // IPC не обязателен: без него видео играет, а управление доступно горячими клавишами mpv.
        val bridgeLog = File(System.getProperty("user.home"), ".yummypc/bridge.log")
        bridgeLog.parentFile?.mkdirs()
        bridgeLog.appendText("start pipe=$pipe factory=${MpvBridgeFactory.factory != null}\n")
        MpvBridgeFactory.create(pipe)?.let { candidate ->
            val connected = candidate.connect(pipe)
            bridgeLog.appendText("connect=$connected\n")
            if (connected) {
                bridge = candidate
                candidate.setPaused(false)
                bridgeLog.appendText("loadfile=${candidate.loadFile(url)}\n")
            } else {
                bridgeLog.appendText("ipc connect failed for $pipe\n")
                runCatching { candidate.quit() }
            }
        } ?: bridgeLog.appendText("no bridge factory\n")
    }

    /** Переключить поток в уже запущенном mpv (без пересоздания окна/процесса). */
fun loadFile(url: String): Result<Unit> = runCatching {
    val current = bridge ?: error("плеер не запущен")
    check(current.loadFile(url)) { "mpv не принял новый поток" }
}

fun togglePause() {
        val current = state.value.paused
        bridge?.setPaused(!current)
    }

    fun seekTo(seconds: Double) {
        bridge?.seekTo(seconds)
    }

    fun setSpeed(speed: Float) {
        bridge?.setSpeed(speed.toDouble())
    }

    fun dispose() {
        runCatching { bridge?.quit() }
        val p = process
        process = null
        bridge = null
        if (p != null && p.isAlive) {
            p.destroy()
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }


}
