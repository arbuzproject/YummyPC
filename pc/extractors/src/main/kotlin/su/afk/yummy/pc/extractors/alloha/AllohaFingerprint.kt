// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import java.security.MessageDigest
import java.util.Random

/** Длина буфера `OfflineAudioContext(1, 44100, 44100)` — 44100 кадров. */
internal const val AUDIO_LENGTH = 44_100

private val VERSION_GROUP = Regex("""\d+(?:[._]\d+)+""")

/**
 * Fingerprint страницы Alloha: SHA-256 (64 строчных hex) от признаков, склеенных через `||`. UA идёт
 * без групп цифр с точками, как его режет страница.
 *
 * Порт `AllohaFingerprint.kt`: сервер принимает хеш, собранный по этому алгоритму из правдоподобных
 * признаков; случайный 64-hex он отбивает `client_blocked` (см. `docs/alloha-player.md`, H14/H15).
 */
internal fun allohaFingerprint(traits: AllohaDeviceTraits): String {
    val values = listOf(
        traits.userAgent.replace(VERSION_GROUP, ""),
        traits.timeZone,
        "${traits.screenLong}x${traits.screenShort}",
        traits.language,
        jsNumber(traits.deviceMemoryGb),
        traits.cores.toString(),
        traits.canvas,
        traits.webglRenderer,
        traits.audioLength.toString(),
    )
    return MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("||").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Число так, как его печатает JavaScript: `8`, а не `8.0`. */
internal fun jsNumber(value: Double): String =
    if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()

private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
private const val CANVAS_PREFIX = "data:image/png;base64,iVBORw0KGgo"
private const val CANVAS_BODY_CHARS = 1_800

/**
 * Псевдо-PNG того же размера, что даёт маленький текстовый canvas. Настоящие пиксели зависят от GPU и
 * шрифтов и без браузера недоступны; страница отдаёт только хеш, поэтому картинка — детерминированная
 * случайная строка по [seed], разная у разных установок.
 */
internal fun allohaCanvasSample(seed: Long): String {
    val random = Random(seed)
    val body = CharArray(CANVAS_BODY_CHARS) { BASE64_ALPHABET[random.nextInt(BASE64_ALPHABET.length)] }
    return CANVAS_PREFIX + String(body)
}

/**
 * Рендереры, которые реально отдаёт Chrome на десктопе (ANGLE поверх D3D11 на Windows, Metal/OpenGL на
 * macOS/Linux). Fingerprint установки выбирает один из них по [seed].
 */
private val WEBGL_RENDERERS = listOf(
    "Google Inc. (NVIDIA)|ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)",
    "Google Inc. (NVIDIA)|ANGLE (NVIDIA, NVIDIA GeForce GTX 1660 SUPER Direct3D11 vs_5_0 ps_5_0, D3D11)",
    "Google Inc. (Intel)|ANGLE (Intel, Intel(R) UHD Graphics 630 Direct3D11 vs_5_0 ps_5_0, D3D11)",
    "Google Inc. (AMD)|ANGLE (AMD, AMD Radeon RX 580 Series Direct3D11 vs_5_0 ps_5_0, D3D11)",
    "Google Inc. (Apple)|ANGLE (Apple, Apple M1, OpenGL 4.1 Metal - 83)",
    "Google Inc. (Intel)|ANGLE (Intel, Mesa Intel(R) UHD Graphics 620 (KBL GT2), OpenGL 4.6)",
)

/** Рендерер этой установки; для одного и того же [seed] не меняется. */
internal fun allohaWebglRenderer(seed: Long): String =
    WEBGL_RENDERERS[Math.floorMod(seed xor (seed ushr 17), WEBGL_RENDERERS.size.toLong()).toInt()]
