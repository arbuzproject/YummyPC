// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.player.ipc

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.charset.StandardCharsets

/**
 * Офлайн-тесты протокола mpv JSON IPC: разбор ответов и событий, кодирование команд, редьюсер
 * состояния и сборка строк из кусков pipe-потока. Сеть и JNA/native здесь не нужны — живые
 * прогоны делает CLI (`:player-ipc:run`), см. `pc/docs/player-ipc.md`.
 *
 * Строки-фикстуры взяты из реальных трасс mpv (`pc/docs/player.md` §6.2).
 */
class MpvProtocolTest {

    // --- разбор ответов mpv ---

    @Test
    fun `parses get_property success response`() {
        val message = assertIs<MpvMessage.Response>(
            MpvProtocol.parse("""{"data":1.373222,"request_id":21,"error":"success"}"""),
        )
        assertEquals(21, message.requestId)
        assertEquals("success", message.error)
        assertTrue(message.ok)
        assertEquals(1.373222, MpvProtocol.dataAsDouble(message.data))
    }

    @Test
    fun `parses loadfile response with object data`() {
        val message = assertIs<MpvMessage.Response>(
            MpvProtocol.parse("""{"data":{"playlist_entry_id":1},"request_id":4,"error":"success"}"""),
        )
        assertEquals(4, message.requestId)
        assertEquals(
            1,
            message.data!!.jsonObject["playlist_entry_id"]!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun `parses error response without data`() {
        val message = assertIs<MpvMessage.Response>(
            MpvProtocol.parse("""{"request_id":30,"error":"property not found"}"""),
        )
        assertEquals(30, message.requestId)
        assertEquals("property not found", message.error)
        assertEquals(false, message.ok)
        assertNull(message.data)
    }

    @Test
    fun `parses error response without request id`() {
        val message = assertIs<MpvMessage.Response>(MpvProtocol.parse("""{"error":"invalid parameter"}"""))
        assertNull(message.requestId)
        assertEquals("invalid parameter", message.error)
    }

    @Test
    fun `parses data null for unavailable property`() {
        val message = assertIs<MpvMessage.Response>(
            MpvProtocol.parse("""{"data":null,"request_id":5,"error":"success"}"""),
        )
        assertNull(MpvProtocol.dataAsDouble(message.data))
        assertNull(MpvProtocol.dataAsBoolean(message.data))
    }

    // --- разбор событий mpv ---

    @Test
    fun `parses property-change event`() {
        val message = assertIs<MpvMessage.Event>(
            MpvProtocol.parse(
                """{"event":"property-change","id":2,"name":"duration","data":634.584000}""",
            ),
        )
        assertEquals("property-change", message.name)
        assertEquals(2, message.observeId)
        assertEquals("duration", message.propertyName)
        assertEquals(634.584, MpvProtocol.dataAsDouble(message.data))
    }

    @Test
    fun `parses file-loaded and idle events`() {
        assertEquals(
            "file-loaded",
            assertIs<MpvMessage.Event>(MpvProtocol.parse("""{"event":"file-loaded"}""")).name,
        )
        assertEquals(
            "idle",
            assertIs<MpvMessage.Event>(MpvProtocol.parse("""{"event":"idle"}""")).name,
        )
    }

    @Test
    fun `parses end-file eof event`() {
        val message = assertIs<MpvMessage.Event>(
            MpvProtocol.parse("""{"event":"end-file","reason":"eof","playlist_entry_id":1}"""),
        )
        assertEquals("end-file", message.name)
        assertEquals("eof", message.reason)
        assertNull(message.errorText)
    }

    @Test
    fun `parses end-file error event with file_error`() {
        val message = assertIs<MpvMessage.Event>(
            MpvProtocol.parse("""{"event":"end-file","reason":"error","file_error":"loading failed"}"""),
        )
        assertEquals("error", message.reason)
        assertEquals("loading failed", message.errorText)
    }

    @Test
    fun `parses event with cyrillic data`() {
        val message = assertIs<MpvMessage.Event>(
            MpvProtocol.parse("""{"event":"property-change","id":9,"name":"media-title","data":"Серия 1"}"""),
        )
        assertEquals("Серия 1", MpvProtocol.dataAsString(message.data))
    }

    // --- некорректный вход ---

    @Test
    fun `invalid or empty input returns null`() {
        assertNull(MpvProtocol.parse(""))
        assertNull(MpvProtocol.parse("   "))
        assertNull(MpvProtocol.parse("not json"))
        assertNull(MpvProtocol.parse("""{"broken":"""))
    }

    @Test
    fun `object without error and event is unknown`() {
        val message = assertIs<MpvMessage.Unknown>(MpvProtocol.parse("""{"foo":"bar"}"""))
        assertTrue(message.raw.contains("foo"))
    }

    // --- кодирование команд ---

    @Test
    fun `encodes loadfile command with request id`() {
        assertEquals(
            """{"command":["loadfile","https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8","replace"],"request_id":4}""",
            MpvProtocol.command(
                listOf("loadfile", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", "replace"),
                4,
            ),
        )
    }

    @Test
    fun `encodes seek and pause commands`() {
        assertEquals(
            """{"command":["seek",30.0,"absolute"],"request_id":27}""",
            MpvProtocol.command(listOf("seek", 30.0, "absolute"), 27),
        )
        assertEquals(
            """{"command":["set_property","pause",true],"request_id":25}""",
            MpvProtocol.command(listOf("set_property", "pause", true), 25),
        )
        assertEquals(
            """{"command":["set_property","volume",70],"request_id":8}""",
            MpvProtocol.command(listOf("set_property", "volume", 70), 8),
        )
    }

    @Test
    fun `encodes nested string list for http-header-fields`() {
        assertEquals(
            """{"command":["set_property","http-header-fields",["User-Agent: YummyTV/1.0","Referer: https://yummyanime.tv/"]],"request_id":2}""",
            MpvProtocol.command(
                listOf(
                    "set_property",
                    "http-header-fields",
                    listOf("User-Agent: YummyTV/1.0", "Referer: https://yummyanime.tv/"),
                ),
                2,
            ),
        )
    }

    @Test
    fun `encodes command with quotes and backslashes so json stays valid`() {
        val trickyUrl = """https://cdn.example/a"b\c.m3u8?q=1"""
        val encoded = MpvProtocol.command(listOf("loadfile", trickyUrl, "replace"), 9)
        val commandArray = kotlinx.serialization.json.Json
            .parseToJsonElement(encoded)
            .jsonObject["command"]!!
            .let { it as kotlinx.serialization.json.JsonArray }
        assertEquals("loadfile", (commandArray[0] as JsonPrimitive).content)
        assertEquals(trickyUrl, (commandArray[1] as JsonPrimitive).content)
    }

    // --- редьюсер состояния ---

    @Test
    fun `file-loaded marks loaded`() {
        val next = MpvStateReducer.reduce(MpvState(), event("file-loaded"))
        assertEquals(true, next.loaded)
        assertEquals(false, next.ended)
        assertNull(next.error)
    }

    @Test
    fun `start-file resets previous playback state`() {
        val playing = MpvState(
            positionSec = 55.0,
            durationSec = 634.584,
            paused = false,
            loaded = true,
            ended = false,
            error = "старая ошибка",
        )
        val next = MpvStateReducer.reduce(playing, event("start-file"))
        assertEquals(
            MpvState(paused = false),
            next,
        )
    }

    @Test
    fun `property-change updates position duration and pause`() {
        var state = MpvState()
        state = MpvStateReducer.reduce(
            state,
            propertyChange("time-pos", JsonPrimitive(12.5)),
        )
        state = MpvStateReducer.reduce(
            state,
            propertyChange("duration", JsonPrimitive(634.584)),
        )
        state = MpvStateReducer.reduce(state, propertyChange("pause", JsonPrimitive(false)))
        assertEquals(12.5, state.positionSec)
        assertEquals(634.584, state.durationSec)
        assertEquals(false, state.paused)
    }

    @Test
    fun `unavailable time-pos does not reset position`() {
        val playing = MpvState(positionSec = 42.0)
        val next = MpvStateReducer.reduce(
            playing,
            propertyChange("time-pos", kotlinx.serialization.json.JsonNull),
        )
        assertEquals(42.0, next.positionSec)
    }

    @Test
    fun `eof-reached toggles ended`() {
        val ended = MpvStateReducer.reduce(
            MpvState(loaded = true),
            propertyChange("eof-reached", JsonPrimitive(true)),
        )
        assertEquals(true, ended.ended)

        val notEnded = MpvStateReducer.reduce(ended, propertyChange("eof-reached", JsonPrimitive(false)))
        assertEquals(false, notEnded.ended)
    }

    @Test
    fun `end-file eof marks loaded false and ended true`() {
        val next = MpvStateReducer.reduce(
            MpvState(loaded = true, durationSec = 634.584),
            event("end-file", reason = "eof"),
        )
        assertEquals(false, next.loaded)
        assertEquals(true, next.ended)
        assertNull(next.error)
    }

    @Test
    fun `end-file stop does not mark ended`() {
        val next = MpvStateReducer.reduce(
            MpvState(loaded = true, ended = true),
            event("end-file", reason = "stop"),
        )
        assertEquals(false, next.loaded)
        assertEquals(false, next.ended)
    }

    @Test
    fun `end-file error sets error text`() {
        val next = MpvStateReducer.reduce(
            MpvState(loaded = true),
            event("end-file", reason = "error", errorText = "loading failed"),
        )
        assertEquals("loading failed", next.error)
        assertEquals(false, next.ended)

        val unknown = MpvStateReducer.reduce(MpvState(loaded = true), event("end-file", reason = "unknown"))
        assertEquals("mpv: end-file reason=unknown", unknown.error)
    }

    @Test
    fun `idle event clears loaded but keeps ended`() {
        val next = MpvStateReducer.reduce(MpvState(loaded = true, ended = true), event("idle"))
        assertEquals(false, next.loaded)
        assertEquals(true, next.ended)
    }

    @Test
    fun `response and unknown messages do not change state`() {
        val state = MpvState(positionSec = 5.0, loaded = true)
        val response = MpvMessage.Response(requestId = 1, error = "success", data = JsonPrimitive(1))
        assertEquals(state, MpvStateReducer.reduce(state, response))
        assertEquals(state, MpvStateReducer.reduce(state, MpvMessage.Unknown("{}")))
        assertEquals(state, MpvStateReducer.reduce(state, event("playback-restart")))
    }

    // --- сборка строк из pipe-потока ---

    @Test
    fun `line assembler collects both lines from one chunk`() {
        val lines = mutableListOf<String>()
        val assembler = MpvLineAssembler { lines += it }
        assembler.feed("""{"a":1}
{"b":2}
""".toByteArray(StandardCharsets.UTF_8))
        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), lines)
    }

    @Test
    fun `line assembler collects complete lines`() {
        val lines = mutableListOf<String>()
        val assembler = MpvLineAssembler { lines += it }
        assembler.feed("line1\nline2\n\n".toByteArray(StandardCharsets.UTF_8))
        assertEquals(listOf("line1", "line2"), lines)
    }

    @Test
    fun `line assembler joins chunks split inside utf8 char`() {
        val lines = mutableListOf<String>()
        val assembler = MpvLineAssembler { lines += it }
        val payload = """{"event":"property-change","name":"media-title","data":"Привет"}""" + "\n"
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        // «Привет» — 12 байт UTF-8; режем заведомо внутри многобайтного символа.
        val splitAt = payload.indexOf("Привет").let { payload.substring(0, it).toByteArray(StandardCharsets.UTF_8).size } + 2
        assembler.feed(bytes.copyOfRange(0, splitAt), splitAt)
        assembler.feed(bytes.copyOfRange(splitAt, bytes.size), bytes.size - splitAt)
        assertEquals(listOf("""{"event":"property-change","name":"media-title","data":"Привет"}"""), lines)
    }

    @Test
    fun `line assembler handles crlf and incomplete tail`() {
        val lines = mutableListOf<String>()
        val assembler = MpvLineAssembler { lines += it }
        assembler.feed("first\r\nsecond-part".toByteArray(StandardCharsets.UTF_8))
        assertEquals(listOf("first"), lines)
        assembler.feed("-rest\n".toByteArray(StandardCharsets.UTF_8))
        assertEquals(listOf("first", "second-part-rest"), lines)
    }

    private fun event(
        name: String,
        reason: String? = null,
        errorText: String? = null,
    ): MpvMessage.Event = MpvMessage.Event(name = name, reason = reason, errorText = errorText)

    private fun propertyChange(
        name: String,
        data: kotlinx.serialization.json.JsonElement?,
    ): MpvMessage.Event = MpvMessage.Event(name = "property-change", propertyName = name, data = data)
}
