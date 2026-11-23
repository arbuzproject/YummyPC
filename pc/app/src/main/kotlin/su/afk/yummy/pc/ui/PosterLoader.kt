// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Загрузчик постеров для десктопа.
 *
 * В Android-версии картинки грузит Coil; на JVM новый движок не нужен: качаем байты обычным
 * соединением и декодируем Skia (он уже внутри Compose Desktop).
 */
object PosterLoader {

    private val cache = ConcurrentHashMap<String, ImageBitmap>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    suspend fun load(url: String?): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        cache[url]?.let { return it }
        if (url in failed) return null
        return withContext(Dispatchers.IO) {
            runCatching { download(url) }
                .mapCatching { bytes -> SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }
                .onSuccess { cache[url] = it }
                .onFailure { failed.add(url) }
                .getOrNull()
        }
    }

    private fun download(url: String): ByteArray {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "YummyPC/0.1 (+desktop port of YummyTV)")
            conn.setRequestProperty("Accept", "image/jpeg,image/png,image/webp,image/*")
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code for $url")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { it.copyTo(out) }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }
}
