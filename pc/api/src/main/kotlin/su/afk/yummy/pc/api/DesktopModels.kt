// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.api

/** Карточка тайтла в списках (главная, поиск, каталог, топ). */
data class AnimeCard(
    val id: Int,
    val title: String,
    val posterUrl: String?,
    val rating: Double?,
    val year: Int?,
    val description: String = "",
    val typeName: String? = null,
    val episodesCount: Int? = null,
    val views: Long? = null,
    /** Место в топе (для сетки с номерами). */
    val rank: Int? = null,
)

/** Ряд на главной странице. */
data class FeedRow(
    val title: String,
    val items: List<AnimeCard>,
    val kind: RowKind = RowKind.CARDS,
) {
    enum class RowKind { CARDS, WIDE_CARDS }
}

/** Большой баннер на главной. */
data class HeroSlide(
    val id: Int,
    val title: String,
    val description: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val rating: Double?,
    val year: Int?,
    val typeName: String?,
    val episodesCount: Int?,
)

/** Участок серии, который можно пропустить (опенинг/эндинг). */
data class SkipRange(
    val startSec: Int,
    val lengthSec: Int,
) {
    val endSec: Int get() = startSec + lengthSec
}

/** Серия/озвучка/балансер: то, что можно открыть в плеере. */
data class VideoRef(
    val videoId: Int,
    val episode: String,
    val player: String,
    val dubbing: String,
    val iframeUrl: String,
    val durationSec: Int? = null,
    /** Опенинг из yani (`skips.opening`): откуда и сколько длится. */
    val openingSkip: SkipRange? = null,
    /** Эндинг из yani (`skips.ending`). */
    val endingSkip: SkipRange? = null,
    /** Просмотры этого видео на yani — подпись на карточке серии; null — сервер не прислал. */
    val views: Int? = null,
    /**
     * Подписан ли пользователь на новые серии этой озвучки/плеера (`subscribed` в ответе
     * `/anime/{id}/videos`). Поле приходит только авторизованному пользователю, поэтому у гостя
     * всегда `false`. Подписка общая для группы серий одной озвучки.
     */
    val subscribed: Boolean = false,
)

/** Озвучка со списком балансеров (панель выбора в плеере). */
data class DubbingGroup(
    val name: String,
    val players: List<String>,
    val videos: List<VideoRef>,
    val episodes: Int,
) {
    val viewsLabel: String
        get() = episodes.toString()
}

/** Карточка серии на странице тайтла. */
data class EpisodeCard(
    val number: String,
    val title: String?,
    val thumbnailUrl: String?,
    val durationSec: Int?,
    val videos: List<VideoRef>,
)

/** Элемент «порядка просмотра». */
data class ViewingOrderItem(
    val id: Int,
    val title: String,
    val posterUrl: String?,
    val year: Int?,
    val rating: Double?,
    val typeName: String?,
    val relation: String?,
    val position: Int,
)

/** Страница тайтла. */
data class AnimePage(
    val id: Int,
    val title: String,
    val description: String,
    val posterUrl: String?,
    val rating: Double?,
    val kinopoiskRating: Double?,
    val shikimoriRating: Double?,
    val myAnimeListRating: Double?,
    val year: Int?,
    val genres: List<String>,
    val status: String?,
    val typeName: String?,
    val ageRating: String?,
    val views: Long?,
    val episodesCount: Int?,
    val airedCount: Int?,
    val videos: List<VideoRef>,
    val dubbings: List<DubbingGroup>,
    val episodes: List<EpisodeCard>,
    val viewingOrder: List<ViewingOrderItem>,
    val screenshots: List<String>,
) {
    val posterForHero: String?
        get() = screenshots.firstOrNull() ?: posterUrl
}

/** День расписания. */
data class ScheduleDay(
    val timestampSec: Long,
    val items: List<ScheduleItem>,
)

data class ScheduleItem(
    val card: AnimeCard,
    val nextEpisodeAtSec: Long?,
    val aired: Int?,
    val episodesCount: Int?,
)

/** Тип аниме из каталога (`Сериал`, `Фильм`, `ONA` …). */
data class AnimeType(
    val name: String,
    val value: Int,
    val count: Int,
)

/** Жанр для панели фильтров. */
data class GenreOption(
    val id: Int,
    val title: String,
    val groupId: Int? = null,
)

/** Состояние фильтров поиска (значения как на сайте yani). */
data class SearchFilters(
    val genres: List<Int> = emptyList(),
    val types: List<Int> = emptyList(),
    val statuses: List<String> = emptyList(),
    val seasons: List<String> = emptyList(),
    val ageRatings: List<Int> = emptyList(),
    val fromYear: Int? = null,
    val toYear: Int? = null,
    val sort: String = "top",
    val sortForward: Boolean = false,
) {
    val activeCount: Int
        get() = genres.size + types.size + statuses.size + seasons.size + ageRatings.size +
            listOfNotNull(fromYear, toYear).size + if (sort != "rating" || !sortForward) 1 else 0
}
