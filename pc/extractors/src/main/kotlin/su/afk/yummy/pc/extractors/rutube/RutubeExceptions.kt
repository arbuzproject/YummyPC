// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.rutube

/**
 * Rutube не отдал поток: в iframe-ссылке нет 32-hex id видео, options API ответил ошибкой или без
 * `video_balancer`, балансер без ссылок. Технический отказ, пользователю показывается как
 * «источник недоступен».
 */
class RutubeResolveException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
