// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Настройки клиента YummyPC.
 *
 * Токены приложения yani (публичный/приватный) в клиенте не хранятся: их знает только
 * сервер YummyPC. Здесь остаётся пользовательская сессия (`Authorization: Bearer`),
 * адрес сервера и настройки интерфейса/плеера.
 */
object YummyConfig {

    /** Сервер YummyPC: через него идут запросы к yani (токены остаются на сервере). */
    const val DEFAULT_BACKEND_URL = "" // адрес выдаётся локально, см. defaultBackendUrl()

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val settingsFile: File
        get() {
            val dir = File(System.getProperty("user.home"), ".yummypc").apply { mkdirs() }
            return File(dir, "settings.json")
        }

    @Volatile
    var userToken: String = ""
        private set

    @Volatile
    var backendUrl: String = defaultBackendUrl()
        private set

    /** id пользователя yani: нужен для списков и счётчиков профиля. */
    @Volatile
    var userId: Int = 0
        private set

    @Volatile
    var languageCode: String = "ru"
        private set

    /** Масштаб карточек: 0.85 — компактно, 1.0 — обычно, 1.2 — крупно. */
    @Volatile
    var cardScale: Float = 1.0f
        private set

    @Volatile
    var showRatings: Boolean = true
        private set

    @Volatile
    var autoplayNextEpisode: Boolean = true
        private set

    @Volatile
    var autoSkipOpening: Boolean = true
        private set

    @Volatile
    var autoSkipEnding: Boolean = false
        private set

    @Volatile
    var skipDelaySec: Int = 0
        private set

    /** Тема оформления: system / dark / light. */
    @Volatile
    var themeMode: String = "system"
        private set

    val isAuthorized: Boolean
        get() = userToken.isNotBlank()

    /**
     * Адрес сервера: переменная окружения `YUMMYPC_BACKEND` важнее, затем `settings.json`,
     * затем адрес, зашитый в релизную сборку (`BuildDefaults`, генерируется из `pc/secrets.properties`).
     */
    fun defaultBackendUrl(): String {
        val fromEnv = System.getenv("YUMMYPC_BACKEND")?.trim()?.trimEnd('/').orEmpty()
        return fromEnv.ifBlank { BuildDefaults.BACKEND_URL }
    }

    fun load() {
        val file = settingsFile
        if (!file.isFile) { save(); return }
        val stored = runCatching { json.decodeFromString<SettingsDto>(file.readText()) }.getOrNull() ?: SettingsDto()
        userToken = stored.userToken
        backendUrl = stored.backendUrl.trim().trimEnd('/').ifBlank { defaultBackendUrl() }
        userId = stored.userId
        languageCode = stored.language.ifBlank { "ru" }
        cardScale = stored.cardScale.takeIf { it in 0.7f..1.5f } ?: 1.0f
        showRatings = stored.showRatings
        autoplayNextEpisode = stored.autoplayNext
        autoSkipOpening = stored.autoSkipOpening
        autoSkipEnding = stored.autoSkipEnding
        skipDelaySec = stored.skipDelaySec.coerceIn(0, 30)
        themeMode = stored.themeMode.ifBlank { "system" }
    }

    fun save() {
        val dto = SettingsDto(
            userToken = userToken,
            backendUrl = backendUrl,
            userId = userId,
            language = languageCode,
            cardScale = cardScale,
            showRatings = showRatings,
            autoplayNext = autoplayNextEpisode,
            autoSkipOpening = autoSkipOpening,
            autoSkipEnding = autoSkipEnding,
            skipDelaySec = skipDelaySec,
            themeMode = themeMode,
        )
        runCatching { settingsFile.writeText(json.encodeToString(SettingsDto.serializer(), dto)) }
    }

    fun updateTokens(userToken: String? = null, backendUrl: String? = null) {
        if (userToken != null) this.userToken = userToken.trim()
        if (backendUrl != null) {
            this.backendUrl = backendUrl.trim().trimEnd('/').ifBlank { defaultBackendUrl() }
        }
        save()
    }

    fun clearSession() {
        userToken = ""
        userId = 0
        save()
    }

    fun updateUserId(id: Int) {
        if (id > 0 && id != userId) { userId = id; save() }
    }

    fun updatePreferences(
        language: String? = null,
        cardScale: Float? = null,
        showRatings: Boolean? = null,
        autoplayNext: Boolean? = null,
        autoSkipOpening: Boolean? = null,
        autoSkipEnding: Boolean? = null,
        skipDelaySec: Int? = null,
        themeMode: String? = null,
    ) {
        if (language != null) languageCode = language
        if (cardScale != null) this.cardScale = cardScale
        if (showRatings != null) this.showRatings = showRatings
        if (autoplayNext != null) autoplayNextEpisode = autoplayNext
        if (autoSkipOpening != null) this.autoSkipOpening = autoSkipOpening
        if (autoSkipEnding != null) this.autoSkipEnding = autoSkipEnding
        if (skipDelaySec != null) this.skipDelaySec = skipDelaySec.coerceIn(0, 30)
        if (themeMode != null) this.themeMode = themeMode
        save()
    }

    /** Через сервер токены не уезжают в клиент: заголовок X-Application добавляет бэкенд. */
    val usesBackend: Boolean
        get() = backendUrl.isNotBlank()

    /** База запросов к yani: свой сервер (`/yani`) или прямой api.yani.tv. */
    fun yaniBaseUrl(): String = if (usesBackend) "$backendUrl/yani" else "https://api.yani.tv"

    @Serializable
    private data class SettingsDto(
        val userToken: String = "",
        val backendUrl: String = defaultBackendUrl(),
        val userId: Int = 0,
        val language: String = "ru",
        val cardScale: Float = 1.0f,
        val showRatings: Boolean = true,
        val autoplayNext: Boolean = true,
        val autoSkipOpening: Boolean = true,
        val autoSkipEnding: Boolean = false,
        val skipDelaySec: Int = 0,
        val themeMode: String = "system",
    )
}

/** Заголовки авторизации для запросов к yani. */
internal object YaniAuthHeaders {
    const val AUTHORIZATION = "Authorization"
    const val BEARER = "Bearer "
    const val LANGUAGE = "Lang"
}

@Serializable
data class YaniLoginBodyDto(
    val login: String,
    val password: String,
    @SerialName("need_json") val needJson: Boolean = true,
    @SerialName("recaptcha_response") val captchaResponse: String? = null,
)

@Serializable
data class YaniLoginResponseDto(val response: YaniLoginResultDto = YaniLoginResultDto())

@Serializable
data class YaniLoginResultDto(
    val success: Boolean = false,
    val token: String = "",
)

@Serializable
data class YaniTokenResponseDto(val response: YaniTokenDto = YaniTokenDto())

@Serializable
data class YaniTokenDto(val token: String = "")

@Serializable
data class YaniProfileResponseDto(val response: YaniProfileDto = YaniProfileDto())

@Serializable
data class YaniProfileDto(
    val id: Int = 0,
    val nickname: String = "",
    val about: String = "",
    val banned: Boolean = false,
    val avatars: YaniAvatarDto = YaniAvatarDto(),
    val banner: YaniBannerDto? = null,
    val bdate: String? = null,
    val sex: Int? = null,
    val roles: List<String> = emptyList(),
    @SerialName("register_date") val registerDate: Long? = null,
    @SerialName("last_online") val lastOnline: Long? = null,
)

@Serializable
data class YaniAvatarDto(
    val small: String? = null,
    val big: String? = null,
    val full: String? = null,
)
@Serializable
data class YaniBannerDto(
    val cropped: String? = null,
    val full: String? = null,
)