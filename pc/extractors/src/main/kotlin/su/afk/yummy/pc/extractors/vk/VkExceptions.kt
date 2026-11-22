// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.vk

/**
 * VK ответил страницей-заглушкой вместо плеера: видео удалено, приватное или закрыто для региона.
 *
 * Это состояние контента, а не сбой разбора: повтор запроса не поможет, UI может показать
 * «источник недоступен». [vkMessage] — собственный текст VK, если его удалось прочитать
 * (страница `video_ext.php` в windows-1251). [videoNotFound] — VK прямо сообщил, что видеофайла нет.
 */
class VkVideoUnavailableException(
    val vkMessage: String?,
    val videoNotFound: Boolean,
) : Exception(
    if (videoNotFound) {
        "VK: видеофайл не найден"
    } else {
        "VK: ${vkMessage ?: "видео недоступно"}"
    },
)

/**
 * Технический отказ экстрактора VK: нет ссылок на поток в странице, сеть не отдала `video_ext.php`,
 * пустая лестница качеств. [message] — короткая причина для логов, пользователю не показывается.
 */
class VkResolveException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
