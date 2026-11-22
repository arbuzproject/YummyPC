// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.aksor

/**
 * Aksor не отдал поток: в ссылке нет hash видео, player API без качеств, запасной разбор страницы
 * (мета `video_url`/скрипты) не нашёл API. Если причина — сетевой сбой, [message] начинается с типа
 * исключения (`SocketTimeoutException: ...`), как в Android-версии: по префиксу аналитика отличала
 * проблему сети пользователя от «потока нет».
 */
class AksorResolveException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
