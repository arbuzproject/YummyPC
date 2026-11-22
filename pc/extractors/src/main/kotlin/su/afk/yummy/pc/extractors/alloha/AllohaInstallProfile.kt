// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import java.awt.GraphicsEnvironment
import java.security.MessageDigest
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

private const val LANGUAGE = "ru-RU"
private const val MAX_DEVICE_MEMORY_GB = 8.0
private const val MIN_DEVICE_MEMORY_GB = 0.25
private const val BYTES_PER_GB = 1_073_741_824.0
private const val PLAYER_ASPECT_HEIGHT = 9
private const val PLAYER_ASPECT_WIDTH = 16
private const val DEFAULT_SCREEN_WIDTH = 1920
private const val DEFAULT_SCREEN_HEIGHT = 1080

/** Версия Chrome в UA по умолчанию — та же, что у остальных экстракторов ПК-порта. */
private const val CHROME_VERSION = "149.0.0.0"

/**
 * UA десктопного Chrome под текущую ОС: fingerprint и client hints строятся из него, и они должны
 * сходиться с `navigator.platform` в `/stat` и `/events`.
 */
internal fun allohaDefaultUserAgent(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        "win" in os -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$CHROME_VERSION Safari/537.36"
        "mac" in os -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$CHROME_VERSION Safari/537.36"
        else -> "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$CHROME_VERSION Safari/537.36"
    }
}

/**
 * Профиль установки для Alloha на JVM: fingerprint для [allohaBorthHeader] и те же числа экрана и
 * платформы для отчётов `/events` и `/stat`.
 *
 * Android-версия берёт размеры из `displayMetrics`, память из `ActivityManager`, а seed хранит в
 * `SharedPreferences`. На десктопе:
 * - размер экрана — из AWT, если дисплей доступен (иначе 1920x1080, как у типичного десктопа);
 * - `deviceMemory` — из `Runtime.maxMemory()`, округлённый вниз до степени двойки (как браузер);
 * - seed — детерминированный SHA-256 от пользователя/ОС/архитектуры: стабилен между запусками и
 *   разный у разных установок, но не требует файла настроек.
 *
 * Canvas и WebGL без браузера не получить — они синтетические по seed, как в Android-версии.
 */
internal fun allohaInstallProfile(userAgent: String): AllohaClientProfile {
    val seed = installSeed()
    val (screenWidth, screenHeight) = screenSize()
    val longSide = max(screenWidth, screenHeight)
    val shortSide = min(screenWidth, screenHeight)
    val traits = AllohaDeviceTraits(
        userAgent = userAgent,
        timeZone = TimeZone.getDefault().id,
        screenLong = longSide,
        screenShort = shortSide,
        language = LANGUAGE,
        deviceMemoryGb = deviceMemoryGb(),
        cores = Runtime.getRuntime().availableProcessors(),
        canvas = allohaCanvasSample(seed),
        webglRenderer = allohaWebglRenderer(seed),
    )
    return AllohaClientProfile(
        fingerprint = allohaFingerprint(traits),
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        windowWidth = screenWidth,
        windowHeight = screenWidth * PLAYER_ASPECT_HEIGHT / PLAYER_ASPECT_WIDTH,
        pixelRatio = 1.0,
        platform = navigatorPlatform(),
    )
}

/** Одно и то же число на установку: от него зависят синтетические canvas и WebGL. */
private fun installSeed(): Long {
    val machine = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME").orEmpty()
    val identity = listOf(
        "alloha-client",
        System.getProperty("user.name").orEmpty(),
        System.getProperty("os.name").orEmpty(),
        System.getProperty("os.arch").orEmpty(),
        machine,
    ).joinToString("|")
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
    var seed = 0L
    for (index in 0 until 8) seed = (seed shl 8) or (digest[index].toLong() and 0xFF)
    return seed
}

/** `navigator.deviceMemory`: объём памяти, округлённый вниз до степени двойки, не больше 8 ГБ. */
private fun deviceMemoryGb(): Double {
    val gb = Runtime.getRuntime().maxMemory() / BYTES_PER_GB
    var bucket = MIN_DEVICE_MEMORY_GB
    while (bucket * 2 <= gb && bucket < MAX_DEVICE_MEMORY_GB) bucket *= 2
    return bucket
}

/** `navigator.platform` так, как его отдаёт Chrome на десктопе. */
private fun navigatorPlatform(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        "win" in os -> "Win32"
        "mac" in os -> "MacIntel"
        else -> "Linux x86_64"
    }
}

/** Размер экрана в «CSS-пикселях»: AWT, если дисплей есть, иначе типичный десктопный 1920x1080. */
private fun screenSize(): Pair<Int, Int> = runCatching {
    if (GraphicsEnvironment.isHeadless()) return@runCatching null
    val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice
        .defaultConfiguration
        .bounds
    if (bounds.width > 0 && bounds.height > 0) bounds.width to bounds.height else null
}.getOrNull() ?: (DEFAULT_SCREEN_WIDTH to DEFAULT_SCREEN_HEIGHT)
