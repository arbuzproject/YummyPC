// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import su.afk.yummy.pc.api.YummyConfig

/** Тема оформления. */
enum class YummyThemeMode(val key: String, val label: String) {
    SYSTEM("system", "Как в системе"),
    DARK("dark", "Тёмная"),
    LIGHT("light", "Светлая");

    companion object {
        fun fromKey(key: String): YummyThemeMode =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** Хранит выбранную тему; смена темы мгновенно перерисовывает интерфейс. */
object YummyThemeController {
    var mode by mutableStateOf(YummyThemeMode.fromKey(YummyConfig.themeMode))
        private set

    fun apply(newMode: YummyThemeMode) {
        mode = newMode
        YummyConfig.updatePreferences(themeMode = newMode.key)
    }

    fun refreshFromConfig() {
        mode = YummyThemeMode.fromKey(YummyConfig.themeMode)
    }
}

/** Определяет светлую тему Windows (реестр) для режима «как в системе». */
internal object SystemTheme {
    fun isLight(): Boolean = runCatching {
        val process = ProcessBuilder(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output.contains("0x1")
    }.getOrDefault(false)
}

/**
 * Палитра YummyTV. Цвета зависят от темы: тёмная (по умолчанию) и светлая.
 *
 * Геттеры читают Compose-состояние, поэтому смена темы автоматически перерисовывает UI.
 */
object YummyPalette {
    private var dark by mutableStateOf(true)
    private val version = mutableIntStateOf(0)

    fun applyDark(value: Boolean) {
        if (dark != value) {
            dark = value
            version.intValue++
        }
    }

    private fun color(darkColor: Long, lightColor: Long): Color {
        version.intValue // подписка на смену темы
        return Color(if (dark) darkColor else lightColor)
    }

    val Background get() = color(0xFF0F0F13, 0xFFF4F4F7)
    val BackgroundElevated get() = color(0xFF14141A, 0xFFFFFFFF)
    val Surface get() = color(0xFF17171D, 0xFFFFFFFF)
    val SurfaceHigh get() = color(0xFF23232B, 0xFFECECF1)
    val SurfaceHover get() = color(0xFF2C2C36, 0xFFE2E2EA)
    val Accent get() = color(0xFFF08A4B, 0xFFE97A35)
    val AccentSoft get() = color(0xFF3A2A20, 0xFFFBE3D2)
    val Text get() = color(0xFFF2F2F5, 0xFF1B1B20)
    val TextDim get() = color(0xFF9A9AA6, 0xFF5A5A66)
    val TextFaint get() = color(0xFF6C6C79, 0xFF8A8A96)
    val RatingStar get() = color(0xFFFFC857, 0xFFE0A400)
    val Success get() = color(0xFF6FCF97, 0xFF2E9E5B)
    val Divider get() = color(0xFF262630, 0xFFDCDCE4)
}

private val YummyTypography = Typography(
    displayLarge = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun YummyTheme(content: @Composable () -> Unit) {
    // Режим: системный -> определяем по Windows, иначе явно.
    val mode = YummyThemeController.mode
    val light = when (mode) {
        YummyThemeMode.LIGHT -> true
        YummyThemeMode.DARK -> false
        YummyThemeMode.SYSTEM -> androidx.compose.runtime.remember(mode) { SystemTheme.isLight() }
    }
    YummyPalette.applyDark(!light)

    val scheme = if (light) {
        lightColorScheme(
            primary = YummyPalette.Accent,
            onPrimary = Color(0xFFFFFFFF),
            secondary = YummyPalette.RatingStar,
            background = YummyPalette.Background,
            onBackground = YummyPalette.Text,
            surface = YummyPalette.Surface,
            onSurface = YummyPalette.Text,
            surfaceVariant = YummyPalette.SurfaceHigh,
            onSurfaceVariant = YummyPalette.TextDim,
            outline = YummyPalette.Divider,
        )
    } else {
        darkColorScheme(
            primary = YummyPalette.Accent,
            onPrimary = Color(0xFF181008),
            secondary = YummyPalette.RatingStar,
            background = YummyPalette.Background,
            onBackground = YummyPalette.Text,
            surface = YummyPalette.Surface,
            onSurface = YummyPalette.Text,
            surfaceVariant = YummyPalette.SurfaceHigh,
            onSurfaceVariant = YummyPalette.TextDim,
            outline = YummyPalette.Divider,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = YummyTypography, content = content)
}
