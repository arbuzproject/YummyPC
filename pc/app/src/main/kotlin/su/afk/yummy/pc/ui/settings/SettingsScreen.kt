// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import su.afk.yummy.pc.api.YummyConfig
import su.afk.yummy.pc.api.isNewerVersion
import su.afk.yummy.pc.api.UpdateInfo
import su.afk.yummy.pc.api.UpdateChecker
import su.afk.yummy.pc.api.AppInfo
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.Button
import su.afk.yummy.pc.ui.theme.YummyThemeMode
import su.afk.yummy.pc.ui.theme.YummyThemeController
import androidx.compose.runtime.produceState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import su.afk.yummy.pc.ui.components.SectionHeader
import su.afk.yummy.pc.ui.theme.YummyPalette

/** Настройки интерфейса и плеера. */
@Composable
fun SettingsScreen() {
    var language by remember { mutableStateOf(YummyConfig.languageCode) }
    var scale by remember { mutableStateOf(YummyConfig.cardScale) }
    var showRatings by remember { mutableStateOf(YummyConfig.showRatings) }
    var autoplayNext by remember { mutableStateOf(YummyConfig.autoplayNextEpisode) }
    var skipOpening by remember { mutableStateOf(YummyConfig.autoSkipOpening) }
    var skipEnding by remember { mutableStateOf(YummyConfig.autoSkipEnding) }
    var openingDelay by remember { mutableStateOf(YummyConfig.openingSkipDelaySec) }
    var endingDelay by remember { mutableStateOf(YummyConfig.endingSkipDelaySec) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var latestRelease by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateChecked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun checkUpdates() {
        if (checkingUpdates) return
        checkingUpdates = true
        scope.launch {
            latestRelease = runCatching { UpdateChecker().latest(force = true) }.getOrNull()
            checkingUpdates = false
            updateChecked = true
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineLarge, color = YummyPalette.Text)
        Text(
            "Язык контента, размер карточек, рейтинги, автопереход между сериями и пропуск опенингов.",
            style = MaterialTheme.typography.bodyMedium,
            color = YummyPalette.TextDim,
        )

        Spacer(Modifier.height(18.dp))
        SectionHeader("Интерфейс")
        SettingRow("Тема оформления") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YummyThemeMode.entries.forEach { theme ->
                    ChoiceChip(theme.label, YummyThemeController.mode == theme) {
                        YummyThemeController.apply(theme)
                    }
                }
            }
        }
        SettingRow("Язык названий и описаний") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ru" to "Русский", "en" to "English", "uk" to "Українська").forEach { (code, label) ->
                    ChoiceChip(label, language == code) {
                        language = code
                        YummyConfig.updatePreferences(language = code)
                    }
                }
            }
        }
        SettingRow("Размер карточек") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.85f to "Компактно", 1.0f to "Обычно", 1.2f to "Крупно").forEach { (value, label) ->
                    ChoiceChip(label, scale == value) {
                        scale = value
                        YummyConfig.updatePreferences(cardScale = value)
                    }
                }
            }
        }
        SettingRow("Показывать рейтинги на карточках") {
            Switch(
                checked = showRatings,
                onCheckedChange = {
                    showRatings = it
                    YummyConfig.updatePreferences(showRatings = it)
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        SectionHeader("Плеер")
        SettingRow("Автоматически открывать следующую серию") {
            Switch(
                checked = autoplayNext,
                onCheckedChange = {
                    autoplayNext = it
                    YummyConfig.updatePreferences(autoplayNext = it)
                },
            )
        }
        SettingRow("Автопропуск опенинга") {
            Switch(
                checked = skipOpening,
                onCheckedChange = {
                    skipOpening = it
                    YummyConfig.updatePreferences(autoSkipOpening = it)
                },
            )
        }
        SettingRow("Автопропуск эндинга") {
            Switch(
                checked = skipEnding,
                onCheckedChange = {
                    skipEnding = it
                    YummyConfig.updatePreferences(autoSkipEnding = it)
                },
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Задержка автопропуска опенинга", style = MaterialTheme.typography.titleSmall, color = YummyPalette.Text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Slider(
                    value = openingDelay.toFloat(),
                    onValueChange = {
                        val seconds = it.toInt().coerceIn(1, 10)
                        openingDelay = seconds
                        YummyConfig.updatePreferences(openingSkipDelaySec = seconds)
                    },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.weight(1f),
                )
                Text("$openingDelay с", color = YummyPalette.TextDim)
            }
            Text("Задержка автопропуска эндинга", style = MaterialTheme.typography.titleSmall, color = YummyPalette.Text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Slider(
                    value = endingDelay.toFloat(),
                    onValueChange = {
                        val seconds = it.toInt().coerceIn(1, 10)
                        endingDelay = seconds
                        YummyConfig.updatePreferences(endingSkipDelaySec = seconds)
                    },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.weight(1f),
                )
                Text("$endingDelay с", color = YummyPalette.TextDim)
            }
        }
        Text(
            "Панель управления в плеере скрывается через 3 секунды бездействия. Полный экран — F11, выход — Esc.",
            style = MaterialTheme.typography.labelSmall,
            color = YummyPalette.TextDim,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(Modifier.height(8.dp))
        SectionHeader("Обновления")
        SettingRow("Версия ${AppInfo.VERSION}") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val fresh = latestRelease
                when {
                    checkingUpdates -> Text("Проверяем…", color = YummyPalette.TextDim, style = MaterialTheme.typography.bodySmall)
                    fresh != null && isNewerVersion(fresh.version, AppInfo.VERSION) -> {
                        Text("Доступно обновление ${fresh.version}", color = YummyPalette.Accent, style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = {
                                runCatching {
                                    java.awt.Desktop.getDesktop().browse(java.net.URI(fresh.downloadUrl ?: fresh.pageUrl))
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("Обновить") }
                    }
                    updateChecked -> Text("У вас последняя версия", color = YummyPalette.TextDim, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { checkUpdates() },
                    enabled = !checkingUpdates,
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Проверить обновления") }
            }
        }

        SectionHeader("Подключение")
        val connected by produceState<Boolean?>(null) {
            value = runCatching {
                val connection = java.net.URI("${YummyConfig.backendUrl}/health").toURL()
                    .openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 4000
                connection.readTimeout = 4000
                connection.requestMethod = "GET"
                connection.responseCode in 200..499
            }.getOrDefault(false)
        }
        Row(
            Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        when (connected) {
                            true -> Color(0xFF4CAF50)
                            false -> Color(0xFFE53935)
                            null -> YummyPalette.TextFaint
                        },
                    ),
            )
            Text(
                when (connected) {
                    true -> "Подключено"
                    false -> "Нет подключения"
                    null -> "Проверяем подключение…"
                },
                color = YummyPalette.Text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingRow(title: String, control: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = YummyPalette.Text)
        control()
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Color(0xFF1A0F07) else YummyPalette.Text,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) YummyPalette.Accent else YummyPalette.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        fontWeight = FontWeight.Medium,
    )
}
