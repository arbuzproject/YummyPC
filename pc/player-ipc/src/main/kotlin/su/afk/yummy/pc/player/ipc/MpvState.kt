// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.player.ipc

/**
 * Снимок состояния встроенного mpv, собранный из событий JSON IPC.
 *
 * Все поля обновляются асинхронно: [MpvController.state] — это `StateFlow`, поэтому UI может
 * подписаться на него и перерисовывать панель управления без опроса mpv.
 *
 * @property positionSec текущая позиция воспроизведения в секундах (0, пока mpv её не отдал).
 * @property durationSec длительность файла в секундах; 0 для live-потоков, где длительности нет.
 * @property paused стоит ли воспроизведение на паузе.
 * @property loaded загружен ли файл (пришло событие `file-loaded` и ещё не было `end-file`).
 * @property ended файл доигран до конца (событие `end-file` с причиной `eof` или `eof-reached=true`).
 * @property error текст ошибки загрузки/воспроизведения, если mpv её сообщил; иначе `null`.
 */
data class MpvState(
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val paused: Boolean = true,
    val loaded: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
)
