// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.player

import kotlinx.coroutines.CancellationException
import su.afk.yummy.pc.api.AnimePage
import su.afk.yummy.pc.api.EpisodeCard
import su.afk.yummy.pc.api.LocalLibrary
import su.afk.yummy.pc.api.VideoRef
import su.afk.yummy.pc.api.YaniRepository

/**
 * Быстрый запуск воспроизведения с главной: кнопка «Смотреть» на баннере и карточки
 * «Продолжить просмотр».
 *
 * Главная знает только id тайтла (и номер серии для «Продолжить просмотр»), а плееру нужен
 * готовый [PlaybackRequest] с прямой ссылкой на поток. Эти функции догружают страницу тайтла,
 * выбирают озвучку/балансер и разрешают поток через [PlayerActions] — без перехода
 * на страницу тайтла и без выбора источника руками.
 *
 * Выбор источника:
 * 1. последняя пара «озвучка + балансер», сохранённая в [LocalLibrary] (тот же источник,
 *    которым тайтл смотрели в прошлый раз);
 * 2. иначе — самая популярная пара: у неё больше всего видео-записей на странице тайтла,
 *    что обычно значит «больше всего серий»; при равенстве выигрывает первая в ответе сервера.
 *
 * Внешние балансеры ([su.afk.yummy.pc.api.ExternalSourcesApi]) не используются: быстрый запуск
 * не должен ждать чужой сервер. Все сетевые и разборные ошибки глушатся — функция вернёт `null`,
 * а главная просто не откроет плеер.
 */

/**
 * Запуск из карточки «Продолжить просмотр».
 *
 * @param animeId id тайтла на yani.
 * @param episode серия из карточки; `null` или пустая строка — последняя доступная серия.
 *   Если указанной серии на странице нет (например, её удалили), берётся последняя доступная.
 * @return готовый запрос для [su.afk.yummy.pc.ui.player.PlayerScreen] или `null` при любой ошибке.
 */
suspend fun resolveContinuePlayback(animeId: Int, episode: String?): PlaybackRequest? =
    quickPlayback(animeId) { page ->
        findEpisode(page, episode) ?: page.episodes.lastOrNull()
    }

/**
 * Запуск кнопкой «Смотреть» на баннере главной: первая серия тайтла.
 *
 * Источник выбирается теми же правилами, что и в [resolveContinuePlayback] (сохранённый
 * выбор, иначе самый популярный), поэтому после просмотра тайтла кнопка открывает привычную
 * озвучку, а у нового тайтла — самую популярную.
 *
 * @return готовый запрос для плеера или `null` при любой ошибке.
 */
suspend fun resolveHeroPlayback(animeId: Int): PlaybackRequest? =
    quickPlayback(animeId) { page -> page.episodes.firstOrNull() }

/**
 * Запоминает источник текущего воспроизведения для быстрого запуска в следующий раз.
 *
 * Вызывается плеером при старте потока и после смены озвучки/балансера. Внешние балансеры
 * (`videoId == 0`) не запоминаются: их пара «озвучка + балансер» не соответствует ни одному
 * видео yani, и сохранение такой записи только сбило бы выбор. Ошибки записи глушит [LocalLibrary].
 */
fun rememberChoice(request: PlaybackRequest) {
    if (request.animeId <= 0 || request.videoId <= 0) return
    LocalLibrary.rememberPlaybackChoice(
        animeId = request.animeId,
        dubbing = request.dubbingName,
        player = request.playerName,
    )
}

/**
 * Общая часть быстрого запуска: страница тайтла → выбранная серия → видео → разрешённый поток.
 *
 * Кандидаты перебираются по порядку предпочтения: если сохранённый балансер перестал
 * работать (сайт убрал озвучку, экстрактор не поддержан), плеер всё равно откроется
 * на следующем подходящем источнике, а не останется с ошибкой.
 */
private suspend fun quickPlayback(
    animeId: Int,
    pickEpisode: (AnimePage) -> EpisodeCard?,
): PlaybackRequest? {
    if (animeId <= 0) return null
    return runCatching {
        val page = YaniRepository().page(animeId)
        val episode = pickEpisode(page) ?: return@runCatching null
        for (video in candidateVideos(page, episode)) {
            val resolved = runCatching {
                PlayerActions.resolve(
                    video = video,
                    pageTitle = page.title,
                    animeId = page.id,
                    // Плейлист — все источники выбранной серии: в плеере можно менять
                    // озвучку/балансер и переходить на следующую серию.
                    episodeVideos = episode.videos,
                    episodes = page.episodes,
                ).getOrNull()
            }.getOrNull()
            if (resolved != null) return@runCatching resolved
        }
        null
    }.getOrElse { error ->
        // Отмена корутины — не ошибка загрузки: пробрасываем, чтобы экран мог отмениться.
        if (error is CancellationException) throw error
        null
    }
}

/**
 * Видео серии [episode] по убыванию предпочтения: сохранённый выбор → самая популярная пара
 * страницы → самая популярная пара серии → первое видео серии.
 *
 * Список без повторов: если сохранённый источник совпал с популярным, попытка будет одна.
 */
private fun candidateVideos(page: AnimePage, episode: EpisodeCard): List<VideoRef> {
    val videos = episode.videos
    if (videos.isEmpty()) return emptyList()
    val candidates = LinkedHashSet<VideoRef>(videos.size)
    LocalLibrary.playbackChoice(page.id)?.let { saved ->
        matchVideo(videos, saved.dubbing, saved.player)?.let { candidates += it }
    }
    popularVideo(page.videos)?.let { popular ->
        matchVideo(videos, popular.dubbing, popular.player)?.let { candidates += it }
    }
    popularVideo(videos)?.let { candidates += it }
    candidates += videos.first()
    return candidates.toList()
}

/**
 * Видео серии, подходящее под сохранённый выбор: озвучка и балансер сравниваются без учёта
 * регистра и лишних пробелов. Пустая строка в выборе — «любой»: сервер не прислал имя.
 */
private fun matchVideo(videos: List<VideoRef>, dubbing: String, player: String): VideoRef? {
    val wantedDubbing = dubbing.trim()
    val wantedPlayer = player.trim()
    if (wantedDubbing.isEmpty() && wantedPlayer.isEmpty()) return null
    return videos.firstOrNull { video ->
        (wantedDubbing.isEmpty() || video.dubbing.trim().equals(wantedDubbing, ignoreCase = true)) &&
            (wantedPlayer.isEmpty() || video.player.trim().equals(wantedPlayer, ignoreCase = true))
    }
}

/**
 * Самая популярная пара «озвучка + балансер»: у неё больше всего видео-записей в [videos]
 * (обычно — больше всего серий). При равенстве выигрывает первая встреченная, как просили
 * в правилах выбора, поэтому счётчик группируется в порядке ответа сервера.
 */
private fun popularVideo(videos: List<VideoRef>): VideoRef? {
    if (videos.isEmpty()) return null
    val counts = LinkedHashMap<Pair<String, String>, Int>()
    for (video in videos) {
        val key = videoKey(video)
        counts[key] = (counts[key] ?: 0) + 1
    }
    var bestKey: Pair<String, String>? = null
    var bestCount = 0
    for ((key, count) in counts) {
        if (count > bestCount) {
            bestKey = key
            bestCount = count
        }
    }
    val key = bestKey ?: return null
    return videos.firstOrNull { videoKey(it) == key }
}

/** Ключ пары «озвучка + балансер»: регистр и пробелы не важны. */
private fun videoKey(video: VideoRef): Pair<String, String> =
    video.dubbing.trim().lowercase() to video.player.trim().lowercase()

/**
 * Серия со страницы по номеру из карточки: точное совпадение, затем числовое (`"02"` = `"2"`),
 * затем по цифрам в подписи (`"Серия 12"` = `"12"`).
 *
 * @return `null` — номер не задан или такой серии на странице нет.
 */
private fun findEpisode(page: AnimePage, wanted: String?): EpisodeCard? {
    val target = wanted?.trim().orEmpty()
    if (target.isEmpty()) return null
    page.episodes.firstOrNull { it.number.trim().equals(target, ignoreCase = true) }?.let { return it }
    target.toIntOrNull()?.let { number ->
        page.episodes.firstOrNull { it.number.trim().toIntOrNull() == number }?.let { return it }
    }
    val targetDigits = episodeDigits(target) ?: return null
    return page.episodes.firstOrNull { episodeDigits(it.number) == targetDigits }
}

/** Первое число в подписи серии: `"12"`/`"Серия 12"` → 12; текстовые серии («OVA») → `null`. */
private fun episodeDigits(raw: String): Int? = DIGITS.find(raw.trim())?.value?.toIntOrNull()

private val DIGITS = Regex("\\d+")
