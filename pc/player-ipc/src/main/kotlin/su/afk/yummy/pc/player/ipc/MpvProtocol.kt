// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.player.ipc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Одно сообщение, пришедшее от mpv по JSON IPC.
 *
 * mpv пишет в канал две разновидности строк: ответы на команды (есть поле `error`) и события
 * (есть поле `event`). Разбор — [MpvProtocol.parse], кодирование команд — [MpvProtocol.command].
 */
sealed interface MpvMessage {

    /**
     * Ответ на команду с нашим `request_id`.
     *
     * @property requestId идентификатор запроса; `null`, если mpv прислал ответ без него.
     * @property error код результата mpv: `"success"` или текст ошибки (`"property unavailable"`,
     *   `"property not found"`, `"invalid parameter"` и т.п.).
     * @property data payload ответа (`get_property`), может отсутствовать.
     */
    data class Response(
        val requestId: Int?,
        val error: String,
        val data: JsonElement? = null,
    ) : MpvMessage {
        /** Пришёл ли успешный ответ. */
        val ok: Boolean get() = error == "success"
    }

    /**
     * Асинхронное событие mpv.
     *
     * @property name имя события: `file-loaded`, `end-file`, `property-change`, `idle` и т.д.
     * @property observeId id подписки из `observe_property` (только для `property-change`).
     * @property propertyName имя свойства (только для `property-change`).
     * @property data новое значение свойства.
     * @property reason причина завершения файла (`eof`, `stop`, `quit`, `error`, `redirect`) —
     *   только для `end-file`.
     * @property errorText текст ошибки из `file_error`/`error` — только для `end-file`.
     */
    data class Event(
        val name: String,
        val observeId: Int? = null,
        val propertyName: String? = null,
        val data: JsonElement? = null,
        val reason: String? = null,
        val errorText: String? = null,
    ) : MpvMessage

    /** Строка, которую не удалось отнести ни к ответу, ни к событию. */
    data class Unknown(val raw: String) : MpvMessage
}

/**
 * Протокол mpv JSON IPC: разбор входящих строк, кодирование команд и чистый редьюсер состояния.
 *
 * Формат: один JSON-объект на строку, UTF-8, разделитель `\n`. Команда клиента всегда содержит
 * `request_id`, чтобы ответ можно было сопоставить с запросом (mpv параллельно шлёт события).
 * Примеры строк и ответов — `pc/docs/player.md` §6.
 */
object MpvProtocol {

    private val json = Json

    /** Разбирает одну строку IPC. Возвращает `null` для пустой строки или невалидного JSON. */
    fun parse(rawLine: String): MpvMessage? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        val obj = try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            return null
        }

        val eventName = stringField(obj, "event")
        if (eventName != null) {
            return MpvMessage.Event(
                name = eventName,
                observeId = intField(obj, "id"),
                propertyName = stringField(obj, "name"),
                data = obj["data"],
                reason = stringField(obj, "reason"),
                errorText = stringField(obj, "file_error") ?: stringField(obj, "error"),
            )
        }

        val error = stringField(obj, "error")
        if (error != null) {
            return MpvMessage.Response(
                requestId = intField(obj, "request_id"),
                error = error,
                data = obj["data"],
            )
        }

        return MpvMessage.Unknown(line)
    }

    /**
     * Кодирует команду mpv в JSON-строку без завершающего перевода строки.
     *
     * @param command массив команды, например `["loadfile", url, "replace"]`; элементы — строки,
     *   числа, булевы значения, `null` или вложенные списки (например, для `http-header-fields`).
     * @param requestId идентификатор запроса для сопоставления ответа.
     */
    fun command(command: List<Any?>, requestId: Int): String = buildJsonObject {
        put("command", buildJsonArray { command.forEach { add(toJson(it)) } })
        put("request_id", requestId)
    }.toString()

    /** Значение `data` как `Double` (для `time-pos`, `duration`, `speed`); `null`, если его нет. */
    fun dataAsDouble(data: JsonElement?): Double? = (data as? JsonPrimitive)?.doubleOrNull

    /** Значение `data` как `Boolean` (для `pause`, `eof-reached`, `idle-active`). */
    fun dataAsBoolean(data: JsonElement?): Boolean? = (data as? JsonPrimitive)?.booleanOrNull

    /** Значение `data` как строка (для `media-title`, `path`); `null`, если его нет. */
    fun dataAsString(data: JsonElement?): String? =
        (data as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is List<*> -> buildJsonArray { value.forEach { add(toJson(it)) } }
        else -> JsonPrimitive(value.toString())
    }

    private fun stringField(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun intField(obj: JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.intOrNull
}

/**
 * Чистое применение событий mpv к [MpvState]. Вынесено отдельно, чтобы покрыть тестами без
 * native-кода и без сети.
 *
 * Ответы на команды (`MpvMessage.Response`) состояние не меняют: их обрабатывает контроллер,
 * а изменения свойств приходят отдельными событиями `property-change`.
 */
object MpvStateReducer {

    /** Возвращает новое состояние после сообщения [message]; исходное состояние не мутируется. */
    fun reduce(state: MpvState, message: MpvMessage): MpvState = when (message) {
        is MpvMessage.Event -> reduceEvent(state, message)
        is MpvMessage.Response -> state
        is MpvMessage.Unknown -> state
    }

    private fun reduceEvent(state: MpvState, event: MpvMessage.Event): MpvState = when (event.name) {
        "start-file" -> state.copy(
            positionSec = 0.0,
            durationSec = 0.0,
            loaded = false,
            ended = false,
            error = null,
        )
        "file-loaded" -> state.copy(loaded = true, ended = false, error = null)
        "end-file" -> state.copy(
            loaded = false,
            ended = event.reason == "eof",
            error = endFileError(event),
        )
        "idle" -> state.copy(loaded = false)
        "property-change" -> reduceProperty(state, event)
        else -> state
    }

    private fun reduceProperty(state: MpvState, event: MpvMessage.Event): MpvState =
        when (event.propertyName) {
            "time-pos" -> MpvProtocol.dataAsDouble(event.data)
                ?.let { state.copy(positionSec = it) } ?: state
            "duration" -> MpvProtocol.dataAsDouble(event.data)
                ?.let { state.copy(durationSec = it) } ?: state
            "pause" -> MpvProtocol.dataAsBoolean(event.data)
                ?.let { state.copy(paused = it) } ?: state
            "eof-reached" -> when (MpvProtocol.dataAsBoolean(event.data)) {
                true -> state.copy(ended = true)
                false -> state.copy(ended = false)
                null -> state
            }
            else -> state
        }

    /** Ошибка загрузки приходит в `end-file` с причиной `error`/`unknown`. */
    private fun endFileError(event: MpvMessage.Event): String? = when {
        event.errorText != null -> event.errorText
        event.reason == "error" -> "mpv: end-file reason=error"
        event.reason == "unknown" -> "mpv: end-file reason=unknown"
        else -> null
    }
}

/**
 * Сборщик строк из кусков байтов, которые читаются из named pipe.
 *
 * Чтение из канала отдаёт произвольные куски, а протокол mpv построчный (UTF-8, разделитель `\n`).
 * Сборщик режет поток по `\n`, убирает лишний `\r` и склеивает многобайтные символы UTF-8,
 * разрезанные между чтениями.
 */
internal class MpvLineAssembler(private val onLine: (String) -> Unit) {

    private var pending = ByteArrayOutputStream()

    /** Добавляет прочитанные байты и отдаёт все полностью собранные строки. */
    fun feed(chunk: ByteArray, length: Int = chunk.size) {
        var start = 0
        for (i in 0 until length) {
            if (chunk[i] != NEWLINE) continue
            pending.write(chunk, start, i - start)
            val lineBytes = pending.toByteArray()
            pending = ByteArrayOutputStream()
            emit(lineBytes)
            start = i + 1
        }
        if (start < length) pending.write(chunk, start, length - start)
    }

    private fun emit(lineBytes: ByteArray) {
        var end = lineBytes.size
        if (end > 0 && lineBytes[end - 1] == CARRIAGE_RETURN) end--
        if (end == 0) return
        val line = String(lineBytes, 0, end, StandardCharsets.UTF_8)
        if (line.isNotBlank()) onLine(line)
    }

    private companion object {
        const val NEWLINE: Byte = 0x0A
        const val CARRIAGE_RETURN: Byte = 0x0D
    }
}
