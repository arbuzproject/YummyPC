// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors

/**
 * Kodik ответил ошибкой вместо страницы/ссылок: HTTP 403/404 от DDoS-Guard, «Видео не найдено»,
 * 5xx после повтора и т.п. В Android-версии это отдельный статус `KodikBlocked` для UI.
 */
class KodikBlockedException(
    val statusCode: Int?,
    message: String? = null,
) : Exception(message ?: "Kodik blocked the request${statusCode?.let { " (HTTP $it)" }.orEmpty()}")

/**
 * Технический отказ экстрактора: в ответе нет `urlParams`/`vInfo`, не разобрался путь эндпоинта,
 * эндпоинт вернул JSON без ссылок и т.п. Пользователю показывается [message].
 */
class KodikResolveException(message: String, cause: Throwable? = null) : Exception(message, cause)
