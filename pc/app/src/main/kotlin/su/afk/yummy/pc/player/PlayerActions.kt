// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.player

import su.afk.yummy.pc.api.EpisodeCard
import su.afk.yummy.pc.api.SkipRange
import su.afk.yummy.pc.api.VideoRef
import su.afk.yummy.pc.extractors.ExtractorRegistry
import su.afk.yummy.pc.extractors.ResolvedStream
import java.util.concurrent.ConcurrentHashMap

/** Запрос на воспроизведение: поток + заголовки + контекст серии (озвучки/серии/скипы). */
data class PlaybackRequest(
    val url: String,
    val headers: Map<String, String>,
    val referer: String?,
    val title: String,
    val subtitle: String? = null,
    val pageTitle: String = "",
    val episodeNumber: String = "",
    /** id видео yani: по нему синхронизируются просмотры с сайтом. 0 — привязки нет. */
    val videoId: Int = 0,
    val episodeVideos: List<VideoRef> = emptyList(),
    val episodes: List<EpisodeCard> = emptyList(),
    val animeId: Int = 0,
    val dubbingName: String = "",
    val playerName: String = "",
    val qualityLabel: String = "",
    val openingSkip: SkipRange? = null,
    val endingSkip: SkipRange? = null,
    /** Доступные качества потока: подпись -> URL (для кнопки выбора качества). */
    val qualities: List<Pair<String, String>> = emptyList(),
)

/** Действие «Смотреть»: разрешает поток и отдаёт запрос на воспроизведение. */
object PlayerActions {

    /**
     * Кэш разрешённых потоков по iframe-ссылке балансера (в памяти процесса).
     *
     * Повторный выбор той же озвучки/качества, автопереход на следующую серию или открытие
     * уже разрешённого потока не ходят в сеть: попадание в кэш отдаёт [ResolvedStream] сразу.
     * Ключ — iframe-URL, поэтому кэш не зависит от серии/подписей.
     */
    private val resolvedStreams = ConcurrentHashMap<String, ResolvedStream>()

    /** Предел кэша: при переполнении сбрасываем целиком, чтобы долгая сессия не росла бесконечно. */
    private const val RESOLVED_CACHE_LIMIT = 64

    /**
     * Разрешает iframe-ссылку в прямой поток. Попадание в [resolvedStreams] — без сети;
     * успешный резолв складывается в кэш для последующих переходов.
     */
    private suspend fun streamFor(video: VideoRef): Result<ResolvedStream> {
        val iframeUrl = video.iframeUrl
        if (iframeUrl.isBlank()) {
            return Result.failure(IllegalStateException("Пустая ссылка балансера"))
        }
        resolvedStreams[iframeUrl]?.let { cached -> return Result.success(cached) }

        val stream = if (MpvLauncher.isDirectStream(iframeUrl)) {
            ResolvedStream(iframeUrl, emptyMap(), emptyList())
        } else {
            val resolver = ExtractorRegistry.resolverFor(iframeUrl)
                ?: return Result.failure(
                    IllegalStateException(
                        "Балансер «${video.player.ifBlank { "неизвестен" }}» пока не поддержан " +
                            "(известные: ${ExtractorRegistry.ids().joinToString()})",
                    ),
                )
            resolver.resolve(iframeUrl).getOrElse { return Result.failure(it) }
        }

        if (resolvedStreams.size >= RESOLVED_CACHE_LIMIT) resolvedStreams.clear()
        resolvedStreams[iframeUrl] = stream
        return Result.success(stream)
    }

    suspend fun resolve(
        video: VideoRef,
        pageTitle: String,
        animeId: Int = 0,
        episodeVideos: List<VideoRef> = listOf(video),
        episodes: List<EpisodeCard> = emptyList(),
    ): Result<PlaybackRequest> {
        val stream = streamFor(video).getOrElse { return Result.failure(it) }

        return Result.success(
            PlaybackRequest(
                url = stream.url,
                headers = stream.headers,
                referer = video.iframeUrl,
                title = "$pageTitle — серия ${video.episode}",
                subtitle = listOf(video.dubbing, video.player).filter { it.isNotBlank() }.joinToString(" · "),
                pageTitle = pageTitle,
                episodeNumber = video.episode,
                videoId = video.videoId,
                episodeVideos = episodeVideos,
                episodes = episodes,
                animeId = animeId,
                dubbingName = video.dubbing,
                playerName = video.player,
                openingSkip = video.openingSkip,
                endingSkip = video.endingSkip,
                qualities = stream.qualities,
                qualityLabel = stream.qualities.firstOrNull { it.second == stream.url }?.first.orEmpty(),
            ),
        )
    }

    /**
     * Заранее разрешает поток следующей серии и кладёт его в кэш: когда пользователь
     * досмотрит текущую (автопереход или кнопка «Серия →»), сеть уже не нужна.
     *
     * Best-effort: ошибки глушатся, на воспроизведение текущей серии это не влияет.
     */
    suspend fun prefetch(video: VideoRef): Unit {
        runCatching { streamFor(video) }
    }

    /** Запасной путь: открыть поток в отдельном окне mpv вместо встроенного плеера. */
    fun playExternal(request: PlaybackRequest): Result<Process> =
        MpvLauncher.play(
            url = request.url,
            headers = request.headers,
            referer = request.referer,
            title = request.title,
        )
}
