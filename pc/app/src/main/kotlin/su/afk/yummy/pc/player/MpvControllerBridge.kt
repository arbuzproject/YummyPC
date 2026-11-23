// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import su.afk.yummy.pc.player.ipc.MpvController
import su.afk.yummy.pc.player.ipc.MpvState

/**
 * Мост между плеером приложения и модулем `:player-ipc`.
 *
 * Модуль умеет говорить с mpv по JSON IPC через named pipe (JNA + overlapped I/O), что решает
 * проблему блокирующего `RandomAccessFile`. Здесь только переводим его `MpvState` в состояние UI.
 */
class MpvControllerBridge(pipeName: String) : MpvIpcBridge {

    private val controller = MpvController(pipeName = pipeName)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val uiState = MutableStateFlow(MpvPlaybackState())

    override val state: StateFlow<MpvPlaybackState> = uiState.asStateFlow()

    init {
        scope.launch {
            controller.state.collect { uiState.value = it.toUi() }
        }
    }

    override fun connect(pipeName: String, timeoutMs: Long): Boolean = controller.connect(timeoutMs)

    override fun loadFile(url: String): Boolean = controller.loadFile(url)

    override fun setPaused(paused: Boolean): Boolean =
        if (paused) controller.pause() else controller.play()

    override fun seekTo(seconds: Double): Boolean = controller.seekTo(seconds)

    override fun setSpeed(speed: Double): Boolean = controller.setSpeed(speed)

    override fun setVolume(volume: Int): Boolean = controller.setVolume(volume)

    override fun quit() {
        runCatching { controller.quit() }
        runCatching { controller.disconnect() }
    }
}

private fun MpvState.toUi(): MpvPlaybackState = MpvPlaybackState(
    positionSec = positionSec,
    durationSec = durationSec,
    paused = paused,
    loaded = loaded,
    ended = ended,
    error = error,
)
