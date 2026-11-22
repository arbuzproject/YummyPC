// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.sibnet

/** Sibnet закрыл страницу плеера (403) для этой сети: видео не при чём, повтор не поможет. */
class SibnetAccessForbiddenException : Exception("Sibnet: HTTP 403: access is forbidden")

/**
 * Технический отказ Sibnet-экстрактора: страница плеера не открылась (не 403) или в ней нет
 * ссылки на mp4.
 */
class SibnetResolveException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
