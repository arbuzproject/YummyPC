// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.player

import kotlinx.coroutines.flow.StateFlow

/** Состояние воспроизведения, которым пользуется оверлей плеера. */
data class MpvPlaybackState(
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val paused: Boolean = true,
    val loaded: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
)

/**
 * Транспорт управления mpv. Реализация — JSON IPC через named pipe (модуль `:player-ipc`);
 * без неё плеер работает, но без паузы/перемотки из UI (только горячие клавиши mpv и OSC).
 */
interface MpvIpcBridge {
    fun connect(pipeName: String, timeoutMs: Long = 15_000): Boolean
    fun loadFile(url: String): Boolean
    fun setPaused(paused: Boolean): Boolean
    fun seekTo(seconds: Double): Boolean
    fun setSpeed(speed: Double): Boolean

    /** Громкость mpv в процентах (0–130). */
    fun setVolume(volume: Int): Boolean

    fun quit()
    val state: StateFlow<MpvPlaybackState>
}

/** Фабрика транспорта: подключается в `Main`/`App` после инициализации модуля `:player-ipc`. */
object MpvBridgeFactory {
    /** Аргумент — имя канала mpv (уникальное на каждый процесс). */
    @Volatile
    var factory: ((String) -> MpvIpcBridge)? = null

    fun create(pipeName: String): MpvIpcBridge? = factory?.invoke(pipeName)
}
