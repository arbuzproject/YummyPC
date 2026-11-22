// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

/**
 * Alloha открыла сессию, но источника для запрошенной озвучки/серии нет: пустой `hlsSource`, ни одной
 * лесенки качеств, страница плеера ответила 404 со заглушкой «Ошибка!».
 *
 * Аналог `AllohaSourceUnavailableException` из Android-версии: повторы того же адреса не помогут,
 * пользователю нужно выбрать другую озвучку или серию. В отличие от технических сбоев не повторяется.
 */
class AllohaSourceUnavailableException(message: String? = null) : Exception(message)

/**
 * Технический отказ экстрактора Alloha: страница плеера не открылась, в HTML нет `viewporti`/`fileList`,
 * `bnsi` вернул не-JSON или пустое тело, ссылок не оказалось и т.п.
 *
 * Порт `IOException`-ветки Android-экстрактора: пользователю показывается [message], по нему видно,
 * где именно сломалось (сеть, HTTP-код, разметка страницы).
 */
class AllohaResolveException(message: String, cause: Throwable? = null) : Exception(message, cause)
