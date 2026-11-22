// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.cvh

/**
 * CdnVideoHub помечает плейлист тегом «Blocked» и отдаёт пустой список: контент запрещён на
 * территории РФ. Ссылки не будет ни сейчас, ни после повтора — UI может показать «недоступно в регионе».
 */
class CvhRegionBlockedException(
    val tags: List<Int>,
) : Exception("CVH: плейлист заблокирован в регионе (tags=$tags)")

/**
 * CVH ответил, что играть нечего: плейлист пуст или в нём нет нужной серии/озвучки. Это состояние
 * контента, а не сбой разбора.
 */
class CvhUnavailableException(
    message: String,
) : Exception("CVH: $message")

/** Технический отказ CVH-экстрактора: нет `anime_id`, у элемента нет `vkId`, ответ без источников. */
class CvhResolveException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
