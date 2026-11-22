// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.news

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.sun.jna.Memory
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.COMInvoker
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import su.afk.yummy.pc.ui.PosterLoader
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Картинки новостей для десктопа.
 *
 * yani отдаёт превью новостей в **AVIF** (`//static.yani.tv/user-uploads/...avif`), а Skia внутри
 * Compose Desktop AVIF не декодирует: без отдельного декодера все карточки новостей остались бы
 * с заглушками. Windows умеет AVIF через WIC, поэтому здесь маленький декодер на JNA: скачиваем
 * байты, просим WIC отдать кадр в BGRA, сразу уменьшаем его до ширины карточки и превращаем в
 * Compose-растр. Остальные форматы (JPEG/PNG/WebP/GIF) WIC тоже читает, поэтому путь один.
 *
 * Если WIC или AVIF-кодек в системе недоступны (например, не-Windows), честно возвращаем `null`
 * и отдаём загрузку прежнему [PosterLoader] — карточка покажет заглушку, но экран не упадёт.
 * Декодированные превью кэшируются по паре «ссылка + ширина», чтобы прокрутка ленты не
 * декодировала одно и то же повторно.
 */
object NewsImageLoader {

    private val cache = ConcurrentHashMap<String, ImageBitmap>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /**
     * Загружает картинку и, если это возможно, уменьшает её до [maxWidthPx] по ширине.
     *
     * @param url абсолютная ссылка на изображение.
     * @param maxWidthPx желаемая ширина растра в пикселях; 0 — не уменьшать.
     * @return готовый растр или `null`, если картинку не удалось получить/декодировать.
     */
    suspend fun load(url: String?, maxWidthPx: Int = 640): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        val key = "$url#$maxWidthPx"
        cache[key]?.let { return it }
        if (key in failed) return null
        val decoded = withContext(Dispatchers.IO) {
            runCatching { WicAvif.decode(download(url), maxWidthPx) }.getOrNull()
        } ?: runCatching { PosterLoader.load(url) }.getOrNull()
        if (decoded == null) {
            failed += key
            return null
        }
        // Ограничиваем кэш «на глаз»: карточек в ленте немного, а растр после уменьшения весит ~1–3 МБ.
        if (cache.size >= MAX_CACHED) {
            cache.clear()
            failed.clear()
        }
        cache[key] = decoded
        return decoded
    }

    private fun download(url: String): ByteArray {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "YummyPC/0.1 (+desktop port of YummyTV)")
            conn.setRequestProperty("Accept", "image/avif,image/webp,image/jpeg,image/png,image/*")
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code for $url")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { it.copyTo(out) }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    /** Сколько декодированных растров держим в памяти. */
    private const val MAX_CACHED = 160
}

/**
 * Минимальный мост к Windows Imaging Component (WIC) поверх JNA.
 *
 * Вся работа — вызовы COM через vtable, поэтому индексы методов зафиксированы в комментариях
 * (порядок из `wincodec.h`/`wincodec.idl`). Новые интерфейсы не заводим: каждая строка — один
 * вызов уже существующего указателя. Любая ошибка WIC/COM не пробрасывается наружу: вызывающий
 * получает `null` и рисует заглушку.
 */
private object WicAvif {

    private val CLSID_WIC_IMAGING_FACTORY = GUID("{cacaf262-9370-4615-a13b-9f5539da4c0a}")
    private val IID_IWIC_IMAGING_FACTORY = GUID("{ec5ec8a9-c395-4314-9c77-54d7a935ff70}")

    /** `GUID_WICPixelFormat32bppBGRA`: 4 байта на пиксель в порядке B, G, R, A. */
    private val GUID_WIC_PIXEL_FORMAT_32BPP_BGRA = GUID("{6fddc324-4e03-4bfe-b185-3d77768dc90f}")

    // Индексы методов в vtable.
    private const val IUNKNOWN_RELEASE = 2
    private const val IWIC_IMAGING_FACTORY_CREATE_DECODER_FROM_STREAM = 4
    private const val IWIC_IMAGING_FACTORY_CREATE_FORMAT_CONVERTER = 10
    private const val IWIC_IMAGING_FACTORY_CREATE_BITMAP_SCALER = 11
    private const val IWIC_IMAGING_FACTORY_CREATE_STREAM = 14
    private const val IWIC_STREAM_INITIALIZE_FROM_MEMORY = 16
    private const val IWIC_BITMAP_DECODER_GET_FRAME = 13
    private const val IWIC_BITMAP_SOURCE_GET_SIZE = 3
    private const val IWIC_BITMAP_SOURCE_COPY_PIXELS = 7
    private const val IWIC_BITMAP_SCALER_INITIALIZE = 8
    private const val IWIC_FORMAT_CONVERTER_INITIALIZE = 8

    /**
     * Декодирует AVIF/JPEG/PNG/WebP/GIF из памяти и возвращает растр, уменьшенный до [maxWidthPx].
     *
     * @return `null`, если WIC недоступен, формат не поддержан или декодирование сорвалось.
     */
    fun decode(bytes: ByteArray, maxWidthPx: Int): ImageBitmap? {
        if (!Platform.isWindows() || bytes.isEmpty()) return null
        val init = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED)
        val comInitialized = init == null || init.toInt() >= 0
        if (init != null && init.toInt() < 0) {
            // RPC_E_CHANGED_MODE: COM уже инициализирован в другом апартаменте — WIC работает и так.
            if (init.toInt() != RPC_E_CHANGED_MODE) return null
        }
        val created = mutableListOf<ComCall>()
        try {
            val ppFactory = PointerByReference()
            if (Ole32.INSTANCE.CoCreateInstance(
                    CLSID_WIC_IMAGING_FACTORY,
                    null,
                    WTypes.CLSCTX_INPROC_SERVER,
                    IID_IWIC_IMAGING_FACTORY,
                    ppFactory,
                ).toInt() < 0
            ) {
                return null
            }
            val factory = ComCall(ppFactory.value ?: return null).also { created += it }

            // Входной буфер WIC читает по указателю: Memory должен жить до конца декодирования.
            val source = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
            val ppStream = PointerByReference()
            if (factory.hr(IWIC_IMAGING_FACTORY_CREATE_STREAM, ppStream) < 0) return null
            val stream = ComCall(ppStream.value ?: return null).also { created += it }
            if (stream.hr(IWIC_STREAM_INITIALIZE_FROM_MEMORY, source, bytes.size) < 0) return null

            val ppDecoder = PointerByReference()
            if (factory.hr(
                    IWIC_IMAGING_FACTORY_CREATE_DECODER_FROM_STREAM,
                    stream.pointer,
                    null,
                    0, // WICDecodeMetadataCacheOnDemand
                    ppDecoder,
                ) < 0
            ) {
                return null
            }
            val decoder = ComCall(ppDecoder.value ?: return null).also { created += it }

            val ppFrame = PointerByReference()
            if (decoder.hr(IWIC_BITMAP_DECODER_GET_FRAME, 0, ppFrame) < 0) return null
            val frame = ComCall(ppFrame.value ?: return null).also { created += it }

            val srcWidth = IntByReference()
            val srcHeight = IntByReference()
            if (frame.hr(IWIC_BITMAP_SOURCE_GET_SIZE, srcWidth, srcHeight) < 0) return null
            val width = srcWidth.value
            val height = srcHeight.value
            if (width <= 0 || height <= 0) return null

            val (targetWidth, targetHeight) = scaledSize(width, height, maxWidthPx)
            // Масштабируем до конвертации формата: WIC сам интерполирует быстрее, чем Skia после.
            var sourcePtr = frame.pointer
            if (targetWidth != width || targetHeight != height) {
                val ppScaler = PointerByReference()
                if (factory.hr(IWIC_IMAGING_FACTORY_CREATE_BITMAP_SCALER, ppScaler) < 0) return null
                val scaler = ComCall(ppScaler.value ?: return null).also { created += it }
                // WICBitmapInterpolationModeFant (3) — лучшее качество из доступных в WIC.
                if (scaler.hr(IWIC_BITMAP_SCALER_INITIALIZE, frame.pointer, targetWidth, targetHeight, 3) < 0) {
                    return null
                }
                sourcePtr = scaler.pointer
            }

            val ppConverter = PointerByReference()
            if (factory.hr(IWIC_IMAGING_FACTORY_CREATE_FORMAT_CONVERTER, ppConverter) < 0) return null
            val converter = ComCall(ppConverter.value ?: return null).also { created += it }
            if (converter.hr(
                    IWIC_FORMAT_CONVERTER_INITIALIZE,
                    sourcePtr,
                    GUID_WIC_PIXEL_FORMAT_32BPP_BGRA,
                    0, // WICBitmapDitherTypeNone
                    null,
                    0.0,
                    0,
                ) < 0
            ) {
                return null
            }

            val stride = targetWidth * 4
            val bufferSize = stride * targetHeight
            val pixels = Memory(bufferSize.toLong())
            if (converter.hr(IWIC_BITMAP_SOURCE_COPY_PIXELS, null, stride, bufferSize, pixels) < 0) {
                return null
            }
            // WIC читает поток лениво: держим входной буфер Memory живым до конца CopyPixels,
            // иначе GC может освободить native-память прямо во время декодирования.
            source.size()
            val imageInfo = ImageInfo(targetWidth, targetHeight, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
            val raster = Image.makeRaster(imageInfo, pixels.getByteArray(0, bufferSize), stride)
            return raster.toComposeImageBitmap()
        } catch (_: Throwable) {
            return null
        } finally {
            // Освобождаем COM-интерфейсы в обратном порядке; Release может не быть — глушим.
            created.asReversed().forEach { runCatching { it.release() } }
            if (comInitialized) runCatching { Ole32.INSTANCE.CoUninitialize() }
        }
    }

    /** Размер с сохранением пропорций: шире [maxWidthPx] не отдаём, но и не увеличиваем. */
    private fun scaledSize(width: Int, height: Int, maxWidthPx: Int): Pair<Int, Int> {
        if (maxWidthPx <= 0 || width <= maxWidthPx) return width to height
        val scaledHeight = (height.toLong() * maxWidthPx / width).toInt().coerceAtLeast(1)
        return maxWidthPx to scaledHeight
    }

    /**
     * Указатель на COM-интерфейс с вызовом метода по индексу в vtable.
     *
     * [hr] вызывает метод и возвращает HRESULT как Int (отрицательный — ошибка COM/WIC), [release]
     * уменьшает счётчик ссылок.
     */
    private class ComCall(pointer: Pointer) : COMInvoker() {
        init {
            setPointer(pointer)
        }

        fun hr(index: Int, vararg args: Any?): Int =
            _invokeNativeInt(index, arrayOf<Any?>(this.pointer, *args))

        fun release() {
            _invokeNativeInt(IUNKNOWN_RELEASE, arrayOf<Any?>(this.pointer))
        }
    }

    /** `RPC_E_CHANGED_MODE`: COM на потоке уже инициализирован, просто используем его как есть. */
    private const val RPC_E_CHANGED_MODE = 0x80010106.toInt()
}
