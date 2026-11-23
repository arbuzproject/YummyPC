// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Встроенный mpv через **render API**: mpv декодирует видео и отдаёт кадры в память,
 * приложение рисует их в Compose. Никакого нативного дочернего окна — значит над видео
 * может быть любой Compose-интерфейс, а видео не «прилипает» поверх других окон.
 *
 * Используется программный рендер (`MPV_RENDER_API_TYPE_SW`, формат `rgb0`), потому что
 * он не требует OpenGL-контекста, а нагрузка на 720p/1080p приемлема на десктопе.
 */
class LibmpvRenderPlayer {

    private val lib = LibmpvNative.INSTANCE
    private var mpv: Pointer? = null
    private var renderContext: Pointer? = null
    private var renderThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val signal = Object()

    private val uiState = MutableStateFlow(MpvPlaybackState())
    val state: StateFlow<MpvPlaybackState> = uiState.asStateFlow()

    private val frames = MutableStateFlow<ImageBitmap?>(null)
    val videoFrames: StateFlow<ImageBitmap?> = frames.asStateFlow()

    private var width = 1280
    private var height = 720
    private var pixels: Memory? = null
    private var renderParams: Memory? = null
    private var sizeMem: Memory? = null
    private var strideMem: Memory? = null
    private var formatMem: Memory? = null
    private var pendingRender = true
    private var forceRender = true
    private var loaded = false
    private val createdAtMs = System.currentTimeMillis()
    private fun timing(message: String) {
        runCatching {
            val file = java.io.File(System.getProperty("user.home"), ".yummypc/player.log")
            file.parentFile?.mkdirs()
            file.appendText("[player] ${System.currentTimeMillis() - createdAtMs} ms: $message\n")
        }
    }
    private var lastFrameAt = 0L

    private val updateCallback = LibmpvNative.RenderUpdateCallback {
        synchronized(signal) {
            pendingRender = true
            signal.notifyAll()
        }
    }

    init {
        val handle = lib.mpv_create() ?: error("mpv_create вернул null")
        mpv = handle
        // Настройки до инициализации: рендерим через render API, звук — системный.
        lib.mpv_set_option_string(handle, "vo", "libmpv")
        lib.mpv_set_option_string(handle, "hwdec", "no")
        lib.mpv_set_option_string(handle, "config", "no")
        lib.mpv_set_option_string(handle, "osc", "no")
        lib.mpv_set_option_string(handle, "input-default-bindings", "no")
        lib.mpv_set_option_string(handle, "input-vo-keyboard", "no")
        lib.mpv_set_option_string(handle, "audio-display", "no")
        lib.mpv_set_option_string(handle, "ytdl", "no")
        lib.mpv_set_option_string(handle, "cache", "yes")
        lib.mpv_set_option_string(handle, "demuxer-max-bytes", "128MiB")
        lib.mpv_set_option_string(handle, "demuxer-readahead-secs", "30")
        lib.mpv_set_option_string(handle, "network-timeout", "20")
        lib.mpv_set_option_string(handle, "log-level", "warn")
        val initResult = lib.mpv_initialize(handle)
        check(initResult >= 0) { "mpv_initialize: ${lib.mpv_error_string(initResult)}" }
        createRenderContext(handle)
        startPoller()
        startRenderLoop()
    }

    /** Запускает воспроизведение. Возвращает Result, чтобы экран показал ошибку. */
    fun start(url: String, headers: Map<String, String>, referer: String?): Result<Unit> = runCatching {
        val handle = mpv ?: error("mpv не создан")
        if (headers.isNotEmpty()) {
            val headerLine = headers.entries.joinToString(",") { "${it.key}: ${it.value}" }
            lib.mpv_set_property_string(handle, "http-header-fields", headerLine)
        }
        referer?.let { lib.mpv_set_property_string(handle, "referrer", it) }
        val args = listOf("loadfile", url, "replace")
        command(args)
        loaded = false
        uiState.value = MpvPlaybackState()
        synchronized(signal) { pendingRender = true; signal.notifyAll() }
    }

    /** Размер области видео: кадры рендерятся под него. */
    fun setVideoSize(widthPx: Int, heightPx: Int) {
        val w = widthPx.coerceIn(160, 2560)
        val h = heightPx.coerceIn(90, 1600)
        if (w == width && h == height && pixels != null) return
        width = w
        height = h
        allocateBuffers()
        synchronized(signal) {
            pendingRender = true
            signal.notifyAll()
        }
    }

    /** Смена качества без пересоздания плеера: тот же поток, другой URL. */
    fun loadFile(url: String): Boolean = runCatching {
        command(listOf("loadfile", url, "replace"))
        true
    }.getOrDefault(false)

    fun togglePause() {
        val handle = mpv ?: return
        val paused = uiState.value.paused
        lib.mpv_set_property_string(handle, "pause", if (paused) "no" else "yes")
        uiState.value = uiState.value.copy(paused = !paused)
    }

    fun seekTo(seconds: Double) {
        val handle = mpv ?: return
        command(listOf("seek", seconds.coerceAtLeast(0.0).toString(), "absolute"))
        synchronized(signal) { pendingRender = true; signal.notifyAll() }
    }

    fun setSpeed(speed: Double) {
        mpv?.let { lib.mpv_set_property_string(it, "speed", speed.toString()) }
    }

    fun setSpeed(speed: Float) = setSpeed(speed.toDouble())

    fun setVolume(percent: Int) {
        mpv?.let { lib.mpv_set_property_string(it, "volume", percent.coerceIn(0, 130).toString()) }
    }

    fun setProperty(name: String, value: Any?) {
        mpv?.let { lib.mpv_set_property_string(it, name, value?.toString() ?: "") }
    }

    fun dispose() {
        running.set(false)
        synchronized(signal) { signal.notifyAll() }
        runCatching { renderThread?.join(1500) }
        renderContext?.let { ctx ->
            runCatching { lib.mpv_render_context_free(ctx) }
        }
        renderContext = null
        mpv?.let { handle ->
            runCatching { lib.mpv_set_property_string(handle, "pause", "yes") }
            runCatching { lib.mpv_terminate_destroy(handle) }
        }
        mpv = null
        pixels = null
        frames.value = null
    }

    // ---------- внутренности ----------

    private fun createRenderContext(handle: Pointer) {
        val api = pointerToCString("sw")
        val params = Memory(PARAM_SIZE * 2)
        writeParam(params, 0, 1, api) // MPV_RENDER_PARAM_API_TYPE
        writeParam(params, 1, 0, null) // terminator
        val out = PointerByReference()
        val res = lib.mpv_render_context_create(out, handle, params)
        check(res >= 0) { "mpv_render_context_create: ${lib.mpv_error_string(res)}" }
        renderContext = out.value
        renderContext?.let { lib.mpv_render_context_set_update_callback(it, updateCallback, null) }
    }

    private fun allocateBuffers() {
        pixels = Memory(width.toLong() * height.toLong() * 4L)
        sizeMem = Memory(8).apply { setInt(0, width); setInt(4, height) }
        strideMem = Memory(8).apply { setLong(0, width.toLong() * 4L) }
        formatMem = pointerToCString("rgb0")
        val params = Memory(PARAM_SIZE * 5)
        writeParam(params, 0, 17, sizeMem) // SW_SIZE
        writeParam(params, 1, 18, formatMem) // SW_FORMAT
        writeParam(params, 2, 19, strideMem) // SW_STRIDE
        writeParam(params, 3, 20, pixels) // SW_POINTER
        writeParam(params, 4, 0, null)
        renderParams = params
    }

    private fun renderFrame() {
        val ctx = renderContext ?: return
        val params = renderParams ?: run { allocateBuffers(); renderParams } ?: return
        val buffer = pixels ?: return
        val res = lib.mpv_render_context_render(ctx, params)
        if (res < 0) return
        val rowBytes = width * 4
        val bytes = ByteArray(rowBytes * height)
        buffer.read(0, bytes, 0, bytes.size)
        val info = ImageInfo(
            ColorInfo(ColorType.RGBA_8888, ColorAlphaType.OPAQUE, null),
            width,
            height,
        )
        val image = Image.makeRaster(info, bytes, rowBytes)
        frames.value = image.toComposeImageBitmap()
        if (!loaded) loaded = true
        uiState.value = uiState.value.copy(loaded = true)
    }

    private fun startRenderLoop() {
        running.set(true)
        renderThread = Thread({
            while (running.get()) {
                synchronized(signal) {
                    if (!pendingRender) {
                        runCatching { signal.wait(80) }
                    }
                    pendingRender = false
                }
                val ctx = renderContext
                if (ctx != null) {
                    val flags = runCatching { lib.mpv_render_context_update(ctx) }.getOrDefault(0L)
                    val force = forceRender
                    forceRender = false
                    if ((flags and 1L) != 0L || force) {
                        runCatching { renderFrame() }
                    }
                }
            }
        }, "yummypc-libmpv-render").apply {
            isDaemon = true
            start()
        }
    }

    private fun startPoller() {
        Thread({
            while (running.get()) {
                val handle = mpv ?: break
                runCatching {
                    val pos = getDouble(handle, "time-pos")
                    val duration = getDouble(handle, "duration") ?: 0.0
                    val pause = lib.mpv_get_property_string(handle, "pause")?.let(::readAndFree) == "yes"
                    val eof = lib.mpv_get_property_string(handle, "eof-reached")?.let(::readAndFree) == "yes"
                    uiState.value = uiState.value.copy(
                        positionSec = pos ?: 0.0,
                        durationSec = duration,
                        paused = pause,
                        loaded = loaded || pos != null,
                        ended = eof,
                    )
                }
                Thread.sleep(250)
            }
        }, "yummypc-libmpv-state").apply {
            isDaemon = true
            start()
        }
    }

    private fun getDouble(handle: Pointer, property: String): Double? {
        val pointer = lib.mpv_get_property_string(handle, property) ?: return null
        return readAndFree(pointer).toDoubleOrNull()
    }

    private fun readAndFree(pointer: Pointer): String {
        val value = runCatching { pointer.getString(0, "UTF-8") }.getOrDefault("")
        runCatching { lib.mpv_free(pointer) }
        return value
    }

    private fun command(args: List<String>): Int {
        val handle = mpv ?: return -1
        val strings = args.map { pointerToCString(it) }
        val pointers = Memory((strings.size + 1L) * POINTER_SIZE)
        strings.forEachIndexed { index, memory ->
            pointers.setPointer(index.toLong() * POINTER_SIZE, memory)
        }
        pointers.setPointer(strings.size.toLong() * POINTER_SIZE, null)
        return lib.mpv_command(handle, pointers)
    }

    private fun pointerToCString(value: String): Memory {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val memory = Memory(bytes.size + 1L)
        memory.write(0, bytes, 0, bytes.size)
        memory.setByte(bytes.size.toLong(), 0)
        return memory
    }

    private fun writeParam(array: Memory, index: Int, type: Int, data: Pointer?) {
        val offset = index.toLong() * PARAM_SIZE
        array.setInt(offset, type)
        array.setLong(offset + 8, Pointer.nativeValue(data ?: Pointer.NULL))
    }

    private companion object {
        // struct mpv_render_param { int type; void *data; } на x86_64: 8 + 8 = 16 байт.
        const val PARAM_SIZE = 16L
        const val POINTER_SIZE = 8L
    }
}
