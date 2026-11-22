// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.ui.profile

import androidx.compose.ui.graphics.Color
import su.afk.yummy.pc.api.UserListEntry
import su.afk.yummy.pc.api.WatchHistoryItem
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Русская локаль: часы и средние оценки показываем с запятой, как в ТВ-версии. */
private val RU_LOCALE: Locale = Locale.forLanguageTag("ru-RU")

/** Сколько секунд в часе. */
private const val HOUR_SECONDS = 3600L

/** Окно «за 7 дней». */
private const val WEEK_SECONDS = 7L * 24L * HOUR_SECONDS

/** Минимальная доля времени с известным типом, при которой показываем легенду по типам. */
private const val MIN_KNOWN_TYPE_SHARE = 0.5

/** Сколько жанров попадает в топ, остальные сворачиваются в «Другие». */
private const val TOP_GENRES = 6

/**
 * Цвета сегментов карточек статистики.
 *
 * Набор совпадает с макетом ТВ-версии: списки — зелёный/фиолетовый/серый/жёлтый/красный,
 * типы тайтлов и топ-жанры — акцентная палитра клиента.
 */
internal object ProfileChartColors {
    /** Смотрено — зелёный. */
    val Watched = Color(0xFF6FCF97)

    /** В планах — фиолетовый. */
    val Planned = Color(0xFFB07CF0)

    /** Брошено — серый. */
    val Dropped = Color(0xFF8E8E9A)

    /** Любимое — жёлтый. */
    val Favorite = Color(0xFFFFC857)

    /** Смотрю — красный. */
    val Watching = Color(0xFFEF6B6B)

    /** Неизвестный статус, остальные жанры, тип без данных. */
    val Other = Color(0xFF5C5C68)

    /** Типы тайтлов для доната времени просмотра. */
    val TypeTv = Color(0xFFF08A4B)
    val TypeOna = Color(0xFF9C7CF0)
    val TypeOva = Color(0xFF6FB7F0)
    val TypeMovie = Color(0xFFFFC857)
    val TypeSpecial = Color(0xFF6FCF97)

    /** Палитра топ-6 жанров. */
    val Genres = listOf(
        Color(0xFFF08A4B),
        Color(0xFFB07CF0),
        Color(0xFF6FCF97),
        Color(0xFF6FB7F0),
        Color(0xFFFFC857),
        Color(0xFFEF6B6B),
    )
}

/** Сегмент круговой диаграммы: подпись, значение и цвет. */
internal data class ChartSlice(
    val label: String,
    val value: Float,
    val color: Color,
    /** Необязательная подпись значения для легенды (например, «57 жанров» у «Других»). */
    val valueLabel: String? = null,
)

/** День мини-календаря активности. */
internal data class ActivityDay(
    val date: LocalDate,
    val count: Int,
    val isToday: Boolean,
    val isFuture: Boolean,
)

/** Сводка блока «Активность»: 35 дней (5 недель Пн–Вс), стрик и счётчики. */
internal data class ActivitySummary(
    val days: List<ActivityDay>,
    val streak: Int,
    val activeDays: Int,
    val totalWatches: Int,
)

/**
 * Сводка «Времени просмотра».
 *
 * [slices] непустые, только если тип удалось определить хотя бы для половины времени:
 * тип тайтла берётся из списков пользователя (`UserListEntry.typeName`) и сопоставляется
 * с историей по `animeId`. Иначе экран покажет одну строку «Просмотрено: N ч»,
 * чтобы не выдумывать разбивку по типам.
 */
internal data class WatchTimeSummary(
    val totalSeconds: Long,
    val weekSeconds: Long,
    val totalRecords: Int,
    val slices: List<ChartSlice>,
    val typesKnown: Boolean,
)

/** Сводка «Продолжительности по спискам»: количество тайтлов по статусам. */
internal data class ListSummary(
    val total: Int,
    val slices: List<ChartSlice>,
)

/** Сводка «Жанров»: топ-6 жанров по количеству тайтлов из списков. */
internal data class GenreSummary(
    val genreCount: Int,
    val slices: List<ChartSlice>,
)

/** Сводка «Оценок»: среднее, гистограмма по 1..10 и число оценённых тайтлов. */
internal data class RatingSummary(
    val average: Double?,
    val histogram: List<Int>,
    val rated: Int,
)

/**
 * Список yani в донате: возможные `href` статуса (у живого API встречаются и полные имена
 * вроде `watching`/`dropped`, и короткие `watch_now`/`lost`), подпись и цвет.
 */
private data class ListCategory(
    val keys: Set<String>,
    val title: String,
    val color: Color,
)

/** Порядок и цвета статусов — как в макете ТВ-версии. */
private val LIST_CATEGORIES = listOf(
    ListCategory(setOf("watched", "completed"), "Смотрено", ProfileChartColors.Watched),
    ListCategory(setOf("will", "planned", "plan"), "В планах", ProfileChartColors.Planned),
    ListCategory(setOf("dropped", "lost"), "Брошено", ProfileChartColors.Dropped),
    ListCategory(setOf("fav", "favorite", "favorites"), "Любимое", ProfileChartColors.Favorite),
    ListCategory(setOf("watching", "watch_now"), "Смотрю", ProfileChartColors.Watching),
)

/** Порядок типов в легенде времени просмотра. */
private val TYPE_ORDER = listOf("TV", "ONA", "OVA", "Фильмы", "Спешл")

/** Мини-календарь активности: 5 недель, начиная с понедельника четыре недели назад. */
internal fun activitySummary(
    history: List<WatchHistoryItem>,
    today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
): ActivitySummary {
    val zone = ZoneId.systemDefault()
    val counts = HashMap<LocalDate, Int>()
    history.forEach { item ->
        val watchedAt = item.watchedAtSec ?: return@forEach
        val day = Instant.ofEpochSecond(watchedAt).atZone(zone).toLocalDate()
        counts[day] = (counts[day] ?: 0) + 1
    }

    val weekStart = today.with(DayOfWeek.MONDAY)
    val firstDay = weekStart.minusWeeks(4)
    val days = (0 until 35).map { offset ->
        val date = firstDay.plusDays(offset.toLong())
        ActivityDay(
            date = date,
            count = counts[date] ?: 0,
            isToday = date == today,
            isFuture = date.isAfter(today),
        )
    }

    // Стрик: подряд идущие дни с просмотрами. День считается, если смотрели сегодня или вчера.
    var streak = 0
    var cursor: LocalDate? = when {
        (counts[today] ?: 0) > 0 -> today
        (counts[today.minusDays(1)] ?: 0) > 0 -> today.minusDays(1)
        else -> null
    }
    while (cursor != null) {
        val day = cursor
        if ((counts[day] ?: 0) <= 0) break
        streak++
        cursor = day.minusDays(1)
    }

    return ActivitySummary(
        days = days,
        streak = streak,
        activeDays = days.count { it.count > 0 },
        totalWatches = days.sumOf { it.count },
    )
}

/** Время просмотра: всего, за 7 дней и разбивка по типам, если типы известны из списков. */
internal fun watchTimeSummary(
    history: List<WatchHistoryItem>,
    lists: List<UserListEntry>,
    nowSec: Long = Instant.now().epochSecond,
): WatchTimeSummary {
    val totalSeconds = history.sumOf { it.durationSec.coerceAtLeast(0).toLong() }
    val weekThreshold = nowSec - WEEK_SECONDS
    val weekSeconds = history.sumOf { item ->
        val watchedAt = item.watchedAtSec
        if (watchedAt != null && watchedAt >= weekThreshold) item.durationSec.coerceAtLeast(0).toLong() else 0L
    }

    // Тип тайтла известен только из пользовательских списков: истории yani тип не отдаёт.
    val typeByAnime = HashMap<Int, String>()
    lists.forEach { entry ->
        val type = typeCategory(entry.typeName) ?: return@forEach
        if (entry.animeId > 0) typeByAnime[entry.animeId] = type
    }

    val secondsByType = LinkedHashMap<String, Long>()
    var knownSeconds = 0L
    history.forEach { item ->
        val duration = item.durationSec.coerceAtLeast(0).toLong()
        if (duration <= 0L) return@forEach
        val type = typeByAnime[item.animeId] ?: return@forEach
        secondsByType[type] = (secondsByType[type] ?: 0L) + duration
        knownSeconds += duration
    }

    val knownShare = if (totalSeconds > 0) knownSeconds.toDouble() / totalSeconds else 0.0
    val typesKnown = knownSeconds > 0 && knownShare >= MIN_KNOWN_TYPE_SHARE
    val slices = if (typesKnown) {
        val byType = TYPE_ORDER.mapNotNull { type ->
            val seconds = secondsByType[type]?.takeIf { it > 0 } ?: return@mapNotNull null
            ChartSlice(type, seconds.toFloat(), typeColor(type))
        }.toMutableList()
        val unknownSeconds = (totalSeconds - knownSeconds).coerceAtLeast(0L)
        if (unknownSeconds > 0) {
            byType += ChartSlice("Другое", unknownSeconds.toFloat(), ProfileChartColors.Other)
        }
        byType
    } else {
        emptyList()
    }

    return WatchTimeSummary(
        totalSeconds = totalSeconds,
        weekSeconds = weekSeconds,
        totalRecords = history.size,
        slices = slices,
        typesKnown = typesKnown,
    )
}

/** Донат по количеству тайтлов в списках. */
internal fun listSummary(entries: List<UserListEntry>): ListSummary {
    val counts = LinkedHashMap<String, Int>()
    var categorized = 0
    entries.forEach { entry ->
        val statusKey = entry.statusKey
        val category = LIST_CATEGORIES.firstOrNull { statusKey in it.keys }
        if (category != null) {
            counts[category.title] = (counts[category.title] ?: 0) + 1
            categorized++
        }
    }
    val slices = LIST_CATEGORIES.mapNotNull { category ->
        val count = counts[category.title] ?: return@mapNotNull null
        if (count <= 0) null else ChartSlice(category.title, count.toFloat(), category.color)
    }.toMutableList()
    val other = entries.size - categorized
    if (other > 0) slices += ChartSlice("Другое", other.toFloat(), ProfileChartColors.Other)
    return ListSummary(total = entries.size, slices = slices)
}

/** Топ-6 жанров по количеству тайтлов из списков; остальные жанры — «Другие». */
internal fun genreSummary(entries: List<UserListEntry>): GenreSummary {
    val counts = LinkedHashMap<String, Int>()
    entries.forEach { entry ->
        entry.genres.forEach { raw ->
            val genre = raw.trim()
            if (genre.isNotEmpty()) counts[genre] = (counts[genre] ?: 0) + 1
        }
    }
    val sorted = counts.entries.sortedWith(
        compareByDescending<Map.Entry<String, Int>> { it.value }
            .thenBy { it.key.lowercase(Locale.ROOT) },
    )
    val top = sorted.take(TOP_GENRES)
    val slices = ArrayList<ChartSlice>(top.size + 1)
    top.forEachIndexed { index, entry ->
        slices += ChartSlice(
            label = entry.key,
            value = entry.value.toFloat(),
            color = ProfileChartColors.Genres[index % ProfileChartColors.Genres.size],
        )
    }
    val rest = sorted.drop(TOP_GENRES).sumOf { it.value }
    if (rest > 0) {
        slices += ChartSlice(
            label = "Другие",
            value = rest.toFloat(),
            color = ProfileChartColors.Other,
            valueLabel = genresWord(sorted.size - top.size),
        )
    }
    return GenreSummary(genreCount = counts.size, slices = slices)
}

/** Гистограмма оценок 1..10 и средняя оценка по личным рейтингам из списков. */
internal fun ratingSummary(entries: List<UserListEntry>): RatingSummary {
    val histogram = MutableList(10) { 0 }
    var sum = 0.0
    var rated = 0
    entries.forEach { entry ->
        val rating = entry.userRating
        if (rating <= 0.0) return@forEach
        val value = rating.roundToInt().coerceIn(1, 10)
        histogram[value - 1]++
        sum += rating
        rated++
    }
    return RatingSummary(
        average = if (rated > 0) sum / rated else null,
        histogram = histogram,
        rated = rated,
    )
}

/** День рождения «24.07.26» → «24 июля 2026»; непонятную строку показываем как есть. */
internal fun formatBirthday(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val parsed = runCatching { LocalDate.parse(value, BIRTHDAY_INPUT) }.getOrNull()
        ?: runCatching { LocalDate.parse(value) }.getOrNull()
    return parsed?.format(BIRTHDAY_OUTPUT) ?: value
}

/** Пол из профиля в читаемом виде; неизвестные значения оставляем как есть. */
internal fun formatSex(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    return when (value.lowercase(Locale.ROOT)) {
        "м", "м.", "муж", "мужской", "m", "male" -> "Мужской"
        "ж", "ж.", "жен", "женский", "f", "female" -> "Женский"
        else -> value
    }
}

/** Часы просмотра: до 10 часов — с десятыми, дальше — целые. */
internal fun formatHours(seconds: Long): String {
    val hours = seconds.coerceAtLeast(0L).toDouble() / HOUR_SECONDS
    return when {
        hours < 0.05 -> "0 ч"
        hours >= 10.0 -> "${hours.roundToInt()} ч"
        else -> "${String.format(RU_LOCALE, "%.1f", hours)} ч"
    }
}

/** Средняя оценка с запятой: 7.4 → «7,4». */
internal fun formatAverage(value: Double): String = String.format(RU_LOCALE, "%.1f", value)

/** Русское склонение числительного: plural(3, "день", "дня", "дней") → «3 дня». */
internal fun plural(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
    return "$count $word"
}

/** «3 дня» / «1 день» / «5 дней». */
internal fun daysWord(count: Int): String = plural(count, "день", "дня", "дней")

/** «12 просмотров» / «1 просмотр» / «3 просмотра». */
internal fun watchesWord(count: Int): String = plural(count, "просмотр", "просмотра", "просмотров")

/** «8 записей» / «1 запись» / «3 записи». */
internal fun recordsWord(count: Int): String = plural(count, "запись", "записи", "записей")

/** «21 тайтл» / «2 тайтла» / «5 тайтлов». */
internal fun titlesWord(count: Int): String = plural(count, "тайтл", "тайтла", "тайтлов")

/** «7 оценок» / «1 оценка» / «4 оценки». */
internal fun ratingsWord(count: Int): String = plural(count, "оценка", "оценки", "оценок")

/** «8 жанров» / «1 жанр» / «3 жанра». */
internal fun genresWord(count: Int): String = plural(count, "жанр", "жанра", "жанров")

/** Формат даты рождения, в котором её отдаёт yani. */
private val BIRTHDAY_INPUT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy", Locale.ROOT)

/** Человеческий формат даты рождения. */
private val BIRTHDAY_OUTPUT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru-RU"))

/** Тип тайтла из yani → понятная подпись легенды (TV/ONA/OVA/Фильмы/Спешл). */
private fun typeCategory(typeName: String?): String? {
    val value = typeName?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (value.isEmpty()) return null
    return when {
        "ona" in value -> "ONA"
        "ova" in value -> "OVA"
        "спешл" in value || "спэшл" in value || "special" in value -> "Спешл"
        "фильм" in value || "movie" in value || "film" in value -> "Фильмы"
        "tv" in value || "сериал" in value || "serial" in value -> "TV"
        else -> null
    }
}

/** Цвет сегмента легенды времени просмотра. */
private fun typeColor(type: String): Color = when (type) {
    "TV" -> ProfileChartColors.TypeTv
    "ONA" -> ProfileChartColors.TypeOna
    "OVA" -> ProfileChartColors.TypeOva
    "Фильмы" -> ProfileChartColors.TypeMovie
    "Спешл" -> ProfileChartColors.TypeSpecial
    else -> ProfileChartColors.Other
}
