// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.zedfilm

/**
 * Zedfilm не отдал поток: iframe-страница не загрузилась или в ней нет ни `video_Init`-полезной
 * нагрузки, ни ссылок на `.mpd`/`.m3u8`/`.mp4`. WebView-фолбэка нет (как и в Android-версии):
 * если контракт страницы сменится, экстрактор честно сообщает об отказе, а не молчит.
 */
class ZedfilmResolveException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
