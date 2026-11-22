// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

/**
 * Одна озвучка Alloha из `bnsi` с её лесенкой качеств.
 *
 * @property id `audioId` Alloha; если сервер его не прислал — индекс озвучки в ответе, чтобы дорожка
 *   оставалась адресуемой.
 * @property label человекочитаемая метка (`"(Russian) AniLibria"`); `"#N"` — если метки нет.
 * @property isDefault пометил ли сервер озвучку выбранной по умолчанию (`default: true`).
 * @property qualities качества по возрастанию: подпись `"1080p"` -> прямой HLS-URL.
 */
internal data class AllohaAudioTrack(
    val id: String,
    val label: String,
    val isDefault: Boolean,
    val qualities: Map<String, String>,
)

/**
 * Дорожка субтитров Alloha (`tracks[]` с `kind == "captions"`).
 *
 * @property label подпись дорожки (`"Русский"`), `"#N"` — если нет.
 * @property url абсолютный URL файла субтитров.
 * @property language язык из ответа (`"rus"`), необязателен.
 * @property format расширение файла без точки (`"vtt"`/`"srt"`), необязательно.
 */
internal data class AllohaSubtitleTrack(
    val label: String,
    val url: String,
    val language: String?,
    val format: String?,
)

/** Разобранный `bnsi`: озвучки с лесенками и субтитры. */
internal data class AllohaParsedSources(
    val audioTracks: List<AllohaAudioTrack>,
    val subtitles: List<AllohaSubtitleTrack>,
)

/** Что `bnsi` выдал для WebSocket сессии: адрес и ключ (`pnr`/`pnk`). */
internal data class AllohaSocketTicket(val url: String, val key: String)

/** Что страница плеера выдала для HTTP-отчётов: токен, домен-встраиватель и id файла. */
internal data class AllohaViewTicket(val token: String, val domain: String, val fileId: String)

/**
 * Результат одного HTTP-цикла открытия сессии Alloha.
 *
 * @property sources озвучки и субтитры из `bnsi`.
 * @property headers заголовки, которые CDN ждёт на каждом медиазапросе (Origin/Referer, UA,
 *   client hints, `authorizations`); их получает плеер.
 * @property socketTicket куда открывать WebSocket; `null` — `bnsi` не предложил сокет.
 * @property viewTicket что нужно для `/events` и `/stat`; `null` — страница не несла `userParam`.
 */
internal data class AllohaOpenedSession(
    val sources: AllohaParsedSources,
    val headers: Map<String, String>,
    val socketTicket: AllohaSocketTicket? = null,
    val viewTicket: AllohaViewTicket? = null,
)

/**
 * Признаки устройства в порядке алгоритма `Tw()` страницы Alloha: из них считается fingerprint для
 * `Borth`. На JVM часть признаков синтетическая (canvas, WebGL) — см. [allohaClientProfile].
 *
 * @property userAgent UA браузера; в хеш идёт без групп цифр с точками.
 * @property timeZone часовой пояс (`Europe/Moscow`).
 * @property screenLong `max(screen.width, screen.height)` в CSS-пикселях.
 * @property screenShort `min(screen.width, screen.height)` в CSS-пикселях.
 * @property language язык браузера (`ru-RU`).
 * @property deviceMemoryGb `navigator.deviceMemory`: степень двойки до 8.
 * @property cores `navigator.hardwareConcurrency`.
 * @property canvas то, что вернул `canvas.toDataURL()`.
 * @property webglRenderer `UNMASKED_VENDOR_WEBGL|UNMASKED_RENDERER_WEBGL`.
 * @property audioLength длина буфера `OfflineAudioContext(1, 44100, 44100)`.
 */
internal data class AllohaDeviceTraits(
    val userAgent: String,
    val timeZone: String,
    val screenLong: Int,
    val screenShort: Int,
    val language: String,
    val deviceMemoryGb: Double,
    val cores: Int,
    val canvas: String,
    val webglRenderer: String,
    val audioLength: Int = AUDIO_LENGTH,
)

/**
 * Профиль установки для Alloha: fingerprint для `Borth` и те же числа экрана/платформы для `/events` и
 * `/stat`, чтобы все три канала рассказывали одну историю.
 *
 * @property fingerprint 64-hex SHA-256, посчитанный алгоритмом страницы.
 * @property screenWidth ширина экрана в CSS-пикселях.
 * @property screenHeight высота экрана в CSS-пикселях.
 * @property windowWidth ширина окна плеера.
 * @property windowHeight высота окна плеера (16:9 от ширины — как у встроенного на сайте).
 * @property pixelRatio `devicePixelRatio`.
 * @property platform `navigator.platform` (`Win32`, `MacIntel`, `Linux x86_64`).
 */
internal data class AllohaClientProfile(
    val fingerprint: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val windowWidth: Int,
    val windowHeight: Int,
    val pixelRatio: Double,
    val platform: String,
)

/** Что играет: описание потока для отчётов `/events` — манифест, озвучка и высота качества. */
internal data class AllohaPlayedStream(
    val manifestUrl: String,
    val audioLabel: String,
    val qualityHeight: Int,
)

/** То, что нужно для старта сессии: токен плеера из HTML и домен-встраиватель. */
internal data class AllohaPageUserParam(val token: String, val domain: String)
