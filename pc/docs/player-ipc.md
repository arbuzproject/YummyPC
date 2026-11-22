# player-ipc — управление mpv по JSON IPC на JVM (Windows named pipe + JNA overlapped I/O)

Дата: 2026-11-22. Модуль: `pc/player-ipc`. Задача: заменить «управление через встроенный OSC mpv»
на полноценный IPC-канал, чтобы панель управления на Compose могла делать `loadfile`/`pause`/`seek`/
`volume`/`speed` и получать состояние плеера.

**Итог одной строкой:** модуль собран (`:player-ipc:build` — PASSED, 30 офлайн-тестов), живой прогон
на `tools/mpv/mpv.exe` + реальном HLS прошёл end-to-end: подключение, `loadfile`, `file-loaded`,
`duration=634.584`, `seek 30 absolute` (работает на паузе), пауза/играть/пауза, `speed`, `volume`,
`quit` — все ответы mpv `error=success`, код возврата CLI 0.

---

## 1. Зачем нативный путь (а не RandomAccessFile)

В `pc/docs/port-status.md` («Ограничение IPC») зафиксировано: `java.io.RandomAccessFile` на named
pipe вешает запись при блокирующем чтении в другом потоке (дедлок проверен тремя пробами), два
дескриптора теряют ответы. Поэтому модуль открывает канал через `kernel32`:

- `CreateFileW(..., GENERIC_READ | GENERIC_WRITE, OPEN_EXISTING, FILE_FLAG_OVERLAPPED, ...)`;
- `ReadFile`/`WriteFile` с `OVERLAPPED` + `CreateEventW` + `WaitForSingleObject` +
  `GetOverlappedResult`;
- один дескриптор на канал, **отдельные потоки чтения и записи**: overlapped-операции не блокируют
  друг друга, дедлока нет.

Чтение и запись проверены одновременно: `loadfile` + подписки на свойства уходят, пока из канала уже
текут события `property-change`, `audio-reconfig`, `start-file` (см. лог §7).

## 2. Структура модуля

```
pc/player-ipc/
  build.gradle.kts                  // kotlin-jvm + java-library + application
  src/main/kotlin/su/afk/yummy/pc/player/ipc/
    MpvState.kt                     // data class MpvState
    MpvProtocol.kt                  // MpvMessage (Response/Event/Unknown), парсер/энкодер JSON,
                                    // MpvStateReducer, MpvLineAssembler (сборка строк из pipe)
    NamedPipeClient.kt              // JNA kernel32: overlapped named-pipe клиент, потоки чтения/записи
    MpvController.kt                // публичный фасад: connect/loadFile/play/pause/.../state
    MpvIpcCli.kt                    // CLI живого сценария (:player-ipc:run)
  src/test/kotlin/su/afk/yummy/pc/player/ipc/
    MpvProtocolTest.kt              // 30 офлайн-тестов: JSON, reducer, сборка строк
  test-artifacts/                   // логи живых прогонов (см. §7)
```

`settings.gradle.kts` уже содержит `include(":player-ipc")`.

## 3. Публичный API

Пакет `su.afk.yummy.pc.player.ipc`.

```kotlin
data class MpvState(
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val paused: Boolean = true,
    val loaded: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
)

data class MpvCommandResult(
    val requestId: Int,
    val ok: Boolean,
    val error: String? = null,
    val data: JsonElement? = null,
)

class MpvController(
    val pipeName: String = "yummypc-mpv",              // или полный путь \\.\pipe\...
    val httpHeaders: Map<String, String> = emptyMap(), // применяются через set_property, не в args
    private val log: ((String) -> Unit)? = null,       // трассировка IPC (для логов/CLI)
) {
    val state: StateFlow<MpvState>              // живое состояние, потокобезопасно
    val isConnected: Boolean

    fun connect(timeoutMs: Long = 15000): Boolean
    fun loadFile(url: String): Boolean
    fun play(): Boolean
    fun pause(): Boolean
    fun togglePause(): Boolean
    fun seekTo(seconds: Double): Boolean
    fun setSpeed(speed: Double): Boolean
    fun setVolume(volume: Int): Boolean

    fun sendCommand(command: List<Any?>, timeoutMs: Long = 5000): MpvCommandResult
    fun quit(): Boolean                          // fire-and-forget: mpv может закрыть канал до ответа
    fun disconnect()                             // закрыть канал; процесс mpv не убивает
}
```

Отличия от черновика из задания (и почему):

1. `httpHeaders` **не попадают в аргументы процесса** — процесс mpv запускает клиент (`:app`), у
   модуля нет прав на его командную строку. Вместо этого контроллер перед первым `loadfile` шлёт
   `set_property http-header-fields ["Имя: значение", ...]` (возможность задокументирована и
   проверялась в `pc/docs/player.md` §6.2). Живой прогон с заголовком — §7.3.
2. Добавлены `isConnected`, `sendCommand`, `quit` и тип `MpvCommandResult`: клиенту нужен `quit`
   (закрытие экрана) и общий путь для остальных команд mpv, которые не вошли в типизированный API.
3. `disconnect()` не завершает mpv и не ждёт exit code: жизненным циклом процесса владеет клиент
   (рекомендуемый порядок — `quit()` → дождаться завершения процесса → `disconnect()`).

Зависимости модуля: `api(kotlinx-coroutines-core)`, `api(kotlinx-serialization-json)` (типы в
публичном API), `implementation(jna 5.18.1)`.

## 4. Как работает транспорт

`NamedPipeClient` (внутренний класс):

| Шаг | Win32 | Детали |
|---|---|---|
| открытие | `CreateFileW(path, GENERIC_READ|GENERIC_WRITE, share=0, OPEN_EXISTING, FILE_FLAG_OVERLAPPED)` | ретраи до `timeoutMs` каждые 100 мс; ожидаемые ошибки `ERROR_FILE_NOT_FOUND` (mpv ещё не создал pipe), `ERROR_PATH_NOT_FOUND`, `ERROR_PIPE_BUSY`; прочие — сразу `false` |
| чтение | `ReadFile` + `OVERLAPPED` | свой `OVERLAPPED` и auto-reset событие у потока чтения; ожидание в `WaitForSingleObject(INFINITE)` (mpv может молчать секундами, пока грузит HLS); при закрытии канала `CancelIoEx` будит поток |
| запись | `WriteFile` + `OVERLAPPED` | отдельный поток-писатель с очередью; свой `OVERLAPPED` на каждую запись, таймаут 5 с; частичные записи докручиваются циклом |
| завершение операции | `GetOverlappedResult` | `ERROR_OPERATION_ABORTED`, `ERROR_BROKEN_PIPE`, `ERROR_PIPE_NOT_CONNECTED`, `ERROR_HANDLE_EOF` трактуются как закрытие канала, остальные — `IOException` с кодом |
| закрытие | `CancelIoEx` → join потоков → `CloseHandle` | без `CancelIoEx` читающий поток остался бы в бесконечном ожидании |

`OVERLAPPED` — 32-байтная структура x64 (`Internal` 0, `InternalHigh` 8, `Offset` 16,
`OffsetHigh` 20, `hEvent` 24). Память и смещения задаются вручную через JNA `Memory`, без
`Structure`-рефлексии; на входе есть проверка `Native.POINTER_SIZE == 8`, поэтому на 32-битной JVM
модуль осознанно не заведётся (в проекте JVM 21 x64).

Строки протокола собирает `MpvLineAssembler`: режет поток по `\n`, убирает `\r`, склеивает
многобайтные символы UTF-8, разрезанные между чтениями (есть отдельный тест с «Привет»).

`MpvController` шлёт команды с `request_id` (атомарный счётчик), ответы сопоставляются через
`CompletableFuture`; события разбираются отдельно и проходят через `MpvStateReducer`. Команды
потокобезопасны, `state` — `StateFlow`, обновляется из потока чтения.

## 5. Соответствие сообщений mpv и `MpvState`

| mpv | Действие в состоянии |
|---|---|
| `observe_property` на `time-pos`, `duration`, `pause`, `eof-reached` (id 1–4) | подписки при `connect`; ответы — `error=success` |
| `property-change name=time-pos` | `positionSec` (`data=null` не сбрасывает позицию) |
| `property-change name=duration` | `durationSec` (для live может не прийти — остаётся 0) |
| `property-change name=pause` | `paused` |
| `property-change name=eof-reached` | `ended` |
| событие `start-file` | сброс: `positionSec=0`, `durationSec=0`, `loaded=false`, `ended=false`, `error=null` |
| событие `file-loaded` | `loaded=true`, `ended=false`, `error=null` |
| событие `end-file`, `reason=eof` | `loaded=false`, `ended=true` |
| событие `end-file`, `reason=error`/`unknown` | `loaded=false`, `error` = `file_error` или `mpv: end-file reason=error` |
| событие `end-file`, `reason=stop`/`quit`/`redirect` | только `loaded=false` (замена файла/выход — это не «досмотрели») |
| событие `idle` | `loaded=false` (`ended` не трогаем: UI должен увидеть конец) |
| ответы на команды | состояние не меняют |

Пути ошибок (`property unavailable`, `invalid parameter`, `timeout`, закрытый канал) возвращаются
через `MpvCommandResult`/`Boolean`, а не через исключения.

## 6. CLI модуля

```
gradlew :player-ipc:run --args="[--pipe=ИМЯ] [--url=URL] [--seek=СЕК] [--header=Имя:знач] [--no-quit]"
```

Сценарий: подключиться → `loadfile` → дождаться `file-loaded` и `duration` → `seekTo` → снять/поставить
паузу (с ожиданием, что позиция сдвинулась) → `setSpeed`/`setVolume` → слушать обновления
`MpvState` → `quit`.

Коды возврата: 0 — ок; 1 — не подключились; 2 — аргументы; 3 — загрузка; 4 — нет `duration`;
5 — seek; 6 — пауза. `--header` формирует `http-header-fields` (валидируется `set_property`).

CLI печатает:
- `[ipc] -> #N {...}` — отправленная команда с `request_id`;
- `[ipc] <- #N error=...` — код ответа mpv (`success`/текст ошибки);
- `[ipc] <- event ...` — события (кроме частых `time-pos`, чтобы лог не забивался);
- `[state] pos=... dur=... paused=... loaded=... ended=... error=...` — обновления `MpvState`
  (печать throttled до 2 раз/с).

Вывод CLI принудительно в UTF-8 (иначе `gradlew :player-ipc:run` на Windows печатает русский текст
в кодировке консоли и он превращается в ромбики).

## 7. Живая проверка

Окружение: Windows x64, JDK 21.0.13.1 (Temurin), Gradle 9.9.2, mpv `v0.42.0-200-g7f505de18`.

### 7.1. Запуск mpv

```powershell
Start-Process tools\mpv\mpv.exe -ArgumentList `
  '--no-config','--idle=yes','--pause=yes','--vo=null','--ao=null',`
  '--input-ipc-server=\\.\pipe\yummypc-test'
```

### 7.2. Основной прогон

```powershell
cd E:\neuiroslop\yummypc\pc
.\gradlew.bat --console=plain :player-ipc:run --args="--url=https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8 --seek=30 --pipe=yummypc-test"
```

Полный лог: `pc/player-ipc/test-artifacts/live-run.log`. Выдержка (значения и коды ответов):

```
[cli] player-ipc: mpv JSON IPC через JNA overlapped named pipe
[cli] pipe=yummypc-test, url=https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
[cli] seek=30.00 c, connect-timeout=15000 ms
[state] pos=0.00 dur=0.00 paused=true loaded=false ended=false error=none
[pipe] connected: \\.\pipe\yummypc-test
[ipc] -> #1 {"command":["get_property","idle-active"],"request_id":1}
[ipc] <- #1 error=success data=true
[ipc] -> #2 {"command":["observe_property",1,"time-pos"],"request_id":2}
[ipc] <- #2 error=success
[ipc] -> #3 {"command":["observe_property",2,"duration"],"request_id":3}
[ipc] <- #3 error=success
[ipc] <- event property-change name=duration id=2
[ipc] -> #4 {"command":["observe_property",3,"pause"],"request_id":4}
[ipc] <- #4 error=success
[ipc] -> #5 {"command":["observe_property",4,"eof-reached"],"request_id":5}
[ipc] <- event property-change name=pause id=3 data=true
[ipc] <- #5 error=success
[ipc] <- event property-change name=eof-reached id=4
[ipc] connected: \\.\pipe\yummypc-test
[cli] connect -> true
[ipc] -> #6 {"command":["loadfile","https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8","replace"],"request_id":6}
[ipc] <- #6 error=success data={"playlist_entry_id":1}
[ipc] <- event start-file
[cli] loadfile -> true
[ipc] <- event audio-reconfig
[ipc] <- event audio-reconfig
[ipc] <- event file-loaded
[state] pos=0.00 dur=0.00 paused=true loaded=true ended=false error=none
[cli] file-loaded за 1735 ms: pos=0.00 dur=0.00 paused=true loaded=true ended=false error=none
[ipc] <- event property-change name=duration id=2 data=634.584000
[ipc] <- event property-change name=eof-reached id=4 data=false
[ipc] <- event audio-reconfig
[ipc] <- event video-reconfig
[ipc] <- event video-reconfig
[cli] duration=634.58 c
[ipc] <- event playback-restart
[cli] до перемотки: pos=0.02 dur=634.58 paused=true loaded=true ended=false error=none
[ipc] -> #7 {"command":["seek",30.0,"absolute"],"request_id":7}
[ipc] <- #7 error=success data=null
[state] pos=30.00 dur=634.58 paused=true loaded=true ended=false error=none
[ipc] <- event seek
[cli] seekTo(30.00) -> true, pos=30.00 dur=634.58 paused=true loaded=true ended=false error=none
[ipc] -> #8 {"command":["get_property","pause"],"request_id":8}
[ipc] <- #8 error=success data=true
[ipc] togglePause: pause было true
[ipc] -> #9 {"command":["set_property","pause",false],"request_id":9}
[ipc] <- #9 error=success
[ipc] <- event property-change name=pause id=3 data=false
[state] pos=30.00 dur=634.58 paused=false loaded=true ended=false error=none
[ipc] <- event playback-restart
[cli] togglePause -> true (сняли паузу); pos=30.06 dur=634.58 paused=false loaded=true ended=false error=none
[ipc] -> #10 {"command":["get_property","pause"],"request_id":10}
[ipc] <- #10 error=success data=false
[ipc] togglePause: pause было false
[ipc] -> #11 {"command":["set_property","pause",true],"request_id":11}
[ipc] <- #11 error=success
[ipc] <- event property-change name=pause id=3 data=true
[cli] togglePause -> true (ожидаем паузу), pos=30.06 dur=634.58 paused=true loaded=true ended=false error=none
[ipc] -> #12 {"command":["set_property","speed",1.25],"request_id":12}
[ipc] <- #12 error=success
[cli] setSpeed(1.25) -> true
[ipc] -> #13 {"command":["set_property","volume",70],"request_id":13}
[ipc] <- #13 error=success
[cli] setVolume(70) -> true
[cli] слушаем обновления MpvState 4000 ms
[cli] итоговое состояние: pos=30.06 dur=634.58 paused=true loaded=true ended=false error=none
[cli] итог: connect=true, loadfile=true, duration=634.58 c, seek=true, play=true, pause=true, position=30.06 c, paused=true
[ipc] -> #14 {"command":["quit"],"request_id":14} (ответ не ждём)
[cli] quit -> true, ждём закрытия канала
[ipc] <- ответ без ожидающего: #14 error=success
[ipc] <- event audio-reconfig
[ipc] <- event video-reconfig
[ipc] <- event end-file reason=quit
[state] pos=30.06 dur=634.58 paused=true loaded=false ended=false error=none
[ipc] <- event property-change name=duration id=2
[ipc] <- event property-change name=eof-reached id=4
[pipe] connection lost: pipe closed by mpv (EOF)
[cli] отключено; isConnected=false

BUILD SUCCESSFUL in 11s
```

Ключевое: `connect=true`, все ответы `error=success`, `duration=634.58`, `seek 30 absolute`
сработал **на паузе** (`pos=30.00, paused=true`), после снятия паузы позиция поехала
(`pos=30.06`), `quit` принят, канал закрыт штатно (EOF, без исключений), код Gradle 0.

### 7.3. HTTP-заголовки через IPC (проверка `httpHeaders`)

```powershell
.\gradlew.bat --console=plain :player-ipc:run --args="--url=https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8 --header=X-Test-Header:yummypc --seek=10 --no-quit --pipe=yummypc-test"
```

```
[cli] connect -> true
[ipc] -> #6 {"command":["set_property","http-header-fields",["X-Test-Header: yummypc"]],"request_id":6}
[ipc] http-header-fields: применено 1 заголовков (X-Test-Header: yummypc)
[cli] loadfile -> true
[cli] duration=634.58 c
[cli] seekTo(10.00) -> true, pos=10.01 dur=634.58 paused=true loaded=true ended=false error=none
[cli] togglePause -> true (сняли паузу); pos=10.06 dur=634.58 paused=false loaded=true ended=false error=none
[cli] togglePause -> true (ожидаем паузу), pos=10.06 dur=634.58 paused=true loaded=true ended=false error=none
```

То есть mpv принимает заголовки свойством, загрузка после этого работает. **Что не проверено:**
реальная передача этих заголовков в HTTP-запросы (для этого нужен сервер, требующий заголовок —
проверка из `pc/docs/player.md` §5.2 делалась для флагов командной строки, не для IPC). Надёжный
путь для боевого клиента прежний: клиент запускает mpv с `--http-header-fields-append` при старте
процесса; IPC-путь — запасной для смены заголовков без рестарта.

Полный лог: `pc/player-ipc/test-artifacts/live-run-headers.log`.

### 7.4. Негативный прогон: mpv не запущен

```powershell
.\gradlew.bat --console=plain :player-ipc:run --args="--pipe=yummypc-missing --timeout=1200"
```

```
[pipe] pipe not available after 1200 ms: \\.\pipe\yummypc-missing (last error=2)
[cli] connect -> false
[cli] отключено; isConnected=false
```

CLI вернул 1 (Gradle BUILD FAILED — ожидаемо), ретраи не съели больше таймаута.
Лог: `pc/player-ipc/test-artifacts/live-run-connect-fail.log`.

## 8. Тесты

```powershell
$env:JAVA_HOME='E:\neuiroslop\yummypc\tools\jdk\jdk-21.0.13.1+2'
cd E:\neuiroslop\yummypc\pc
.\gradlew.bat --console=plain :player-ipc:build      # включает :player-ipc:test
```

30 тестов, 0 падений, без сети и без JNA/native:
- разбор ответов mpv: `get_property` success, `loadfile` с `{playlist_entry_id}`, `error` без data,
  ответ без `request_id`, `data:null`;
- разбор событий: `property-change`, `file-loaded`, `idle`, `end-file eof/error` с `file_error`,
  кириллица в `data`;
- мусор на входе: пустая строка, невалидный JSON, объект без `error`/`event`;
- кодирование команд: точные строки `loadfile`/`seek`/`set_property pause`/`volume`, вложенный список
  для `http-header-fields`, экранирование кавычек и обратных слэшей в URL;
- редьюсер состояния: `file-loaded`, `start-file` (сброс), `time-pos/duration/pause`, `data:null`
  не сбрасывает позицию, `eof-reached`, `end-file` (eof/stop/error/unknown), `idle`, игнор ответов;
- `MpvLineAssembler`: несколько строк в одном куске, `\r\n`, неполный хвост, UTF-8-символ,
  разрезанный между чтениями.

## 9. Ограничения (что не проверено / осознанные решения)

1. **Только Windows x64.** JNA `kernel32` + жёсткая 32-байтная раскладка `OVERLAPPED`; на 32-битной
   JVM будет `IllegalArgumentException` при создании клиента (проверка в конструкторе). Linux/macOS
   не поддерживаются (там mpv использует unix-socket).
2. **mpv запускает клиент, а не модуль.** Модуль не знает про `--wid`, `--vo`, заголовки процесса.
   В `:app` нужно добавить в аргументы `--input-ipc-server=\\.\pipe\<уникальное имя>` (сейчас
   `EmbeddedMpv` его не передаёт — это зона `:app`).
3. **Реальная HTTP-передача `httpHeaders` через IPC не проверена** (§7.3): проверено только, что mpv
   принимает свойство и загрузка после этого работает. Для боевого пути надёжнее флаги при старте.
4. **Проверялось на `--vo=null --ao=null`** (headless, без звука), встраивание через `--wid` в живом
   прогоне модуля не участвовало — это интеграция следующего шага с `:app`.
5. **Live-HLS не проверялся**: тестовый поток — VOD mux (есть `duration`, seek работает). Для live
   `duration` может не прийти (останется 0), а seek может игнорироваться — UI должен это учитывать.
6. **Путь ошибки загрузки (`end-file reason=error`, например HTTP 403) покрыт только юнит-тестами**
   редьюсера, живой негативный поток не гоняли.
7. **Переподключение к новому процессу mpv** не реализовано: после закрытия канала нужен новый
   `MpvController.connect()` (сервер с тем же именем pipe при этом может быть занят старым
   процессом — имя канала должно быть уникальным на инстанс плеера).
8. **`quit()` не ждёт ответа и не убивает процесс**: контракт — `quit()` → клиент ждёт завершения
   процесса (2 с) → `destroyForcibly()` → `disconnect()`.
9. **Один клиент на канал**: `SHARE=0` при открытии; два контроллера к одному pipe работают
   не гарантированно.
10. **`MpvState` без `buffering`/`title`**: в состояние вынесен минимум из задания; `paused-for-cache`
    и `media-title` можно добавить теми же подписками, если понадобится индикатор буферизации.
11. **`time-pos` обновляется с частотой событий mpv** (десятки в секунду): контроллер не троттлит
    `StateFlow` (это делает UI/CLI на своей стороне), чтобы не терять точность для прогресс-бара.
12. **Размер сообщений**: буфер чтения 64 КиБ; сообщения mpv (события/ответы) на порядки меньше,
    но гигантские `data` (например, огромный ответ `get_property`) не проверялись.

## 10. Подключение модуля к `:app`

1. `pc/settings.gradle.kts` — уже добавлено:

```kotlin
include(":player-ipc")
```

2. `pc/app/build.gradle.kts`, блок `dependencies` — добавить строку:

```kotlin
implementation(project(":player-ipc"))
```

3. В `:app` (файл `pc/app/.../player/EmbeddedMpv.kt`) добавить в аргументы mpv **уникальное** имя
   канала и создать контроллер:

```kotlin
import su.afk.yummy.pc.player.ipc.MpvController

val pipeName = "yummypc-mpv-${ProcessHandle.current().pid()}-${System.nanoTime()}"

val args = mutableListOf(
    "--no-config", "--terminal=no",
    "--input-ipc-server=\\\\.\\pipe\\$pipeName",   // в Kotlin-строке: \\\\.\\pipe\\
    "--wid=$hwnd", "--vo=gpu", "--gpu-api=d3d11", "--hwdec=auto-safe",
    "--force-window=no", "--idle=yes", "--pause=yes", "--keep-open=no",
    "--no-osc", "--no-osd-bar", "--osd-level=0",
    "--no-input-default-bindings", "--input-vo-keyboard=no",
    "--audio-client-name=YummyPC", "--title=YummyPC",
)
headers.forEach { (k, v) -> args += "--http-header-fields-append=$k: $v" }
// процесс запускается как раньше: ProcessBuilder(...)

val controller = MpvController(pipeName = pipeName, httpHeaders = headers, log = { println(it) })
if (controller.connect(15000)) {
    controller.loadFile(url)          // заголовки уже в аргументах процесса; IPC-путь — запасной
    controller.seekTo(resumeSec)
    controller.play()
}
// UI: collect(controller.state) -> панель управления/прогресс-бар
// при закрытии экрана:
controller.quit()
process.waitFor(2, TimeUnit.SECONDS); if (process.isAlive) process.destroyForcibly()
controller.disconnect()
```

4. Панель управления в `PlayerScreen.kt` (сейчас там кнопок нет — управление было отдано OSC mpv)
   подписывается на `controller.state`: позиция/длительность — прогресс-бар, `paused` — кнопка
   play/pause, `ended`/`error` — «следующая серия»/сообщение об ошибке. Для click-to-seek:
   `controller.seekTo(positionSec)`; громкость/скорость — `setVolume`/`setSpeed`.

5. После интеграции снять скриншот панели и обновить `pc/docs/port-status.md`
   (там ограничение «JSON IPC не подключён» станет неактуальным) — это зона ведущего агента.

## 11. Как воспроизвести проверку целиком

```powershell
$env:JAVA_HOME='E:\neuiroslop\yummypc\tools\jdk\jdk-21.0.13.1+2'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
cd E:\neuiroslop\yummypc\pc
.\gradlew.bat --console=plain :player-ipc:build

# окно 1: mpv
Start-Process E:\neuiroslop\yummypc\tools\mpv\mpv.exe -ArgumentList `
  '--no-config','--idle=yes','--pause=yes','--vo=null','--ao=null',`
  '--input-ipc-server=\\.\pipe\yummypc-test'

# окно 2: сценарий
.\gradlew.bat --console=plain :player-ipc:run --args="--pipe=yummypc-test --seek=30"
```
