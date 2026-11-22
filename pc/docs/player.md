# Встроенный видеоплеер на mpv для YummyPC (Windows) — спайк и инструкция

Дата: 2026-11-22. Область: `tools\mpv` (сам mpv + артефакты тестов) и этот документ.
Задача: заменить Android-плеер Media3/ExoPlayer на десктопе встроенным mpv, встроенным в окно Compose Desktop.

**Итог одной строкой:** portable-mpv скачан и проверен, HLS играет, HTTP-заголовки доходят до сегментов,
IPC через named pipe работает, `--wid`-встраивание в чужое окно работает (видео реально рисуется, окно
масштабируется вместе с родителем), рекомендованная командная строка прогнана end-to-end. Не проверено
только то, что требует установленного JDK/сборки Compose-приложения (в этой среде JDK нет).

---

## 1. Что скачано

| Параметр | Значение |
|---|---|
| Источник | GitHub-зеркало zhongfly/mpv-winbuild (официальные сборки mpv для Windows) |
| Релиз | `2026-11-21-7f505de186` |
| Архив | `mpv-x86_64-20261121-git-7f505de186.7z`, 34 062 150 байт |
| URL | `https://github.com/zhongfly/mpv-winbuild/releases/download/2026-11-21-7f505de186/mpv-x86_64-20261121-git-7f505de186.7z` |
| SHA-256 | `dd0fa0f2338e4b9211ec786a1f9d77ee58593aec00d0d1a0b3371512a5e12088` — **совпал** с `sha256.txt` из релиза |
| Версия | `mpv v0.42.0-200-g7f505de18`, сборка 21.11.2026 |
| FFmpeg | `N-129180-gb54992769` (libavcodec 63.28.100) |
| libplacebo | `v7.399.0` |
| Сборка | GPL, включены `d3d11`, `vulkan`, `libcurl`, `luajit`, `wasapi`, `win32-smtc`, `d3d-hwaccel` и т.д. |

Куда распаковано:

```
E:\neuiroslop\yummypc\tools\mpv\
  mpv.exe                 (123 540 480 байт, GUI-подсистема)
  mpv.com                 (3 584 байта, консольный launcher)
  mpv\fonts.conf
  doc\                    (manual.pdf из сборки + сюда же скачаны *.rst из mpv master)
  installer\, updater.bat, mpv-register.bat, mpv-unregister.bat
  mpv-x86_64-20261121-git-7f505de186.7z  (исходный архив, можно удалить после распаковки)
  sha256.txt
  version.txt             (вывод mpv.exe --version)
  test-hls.log            (лог headless-теста HLS, см. §3)
  test-hls-console.log
  http-test\  ipc-test\  wid-test\  smoke-test\   (скрипты и артефакты проверок, см. §10)
```

Распаковка (Windows `bsdtar`/`tar.exe` умеет 7z, 7-Zip тоже подойдёт):

```powershell
tar -xf mpv-x86_64-20261121-git-7f505de186.7z -C E:\neuiroslop\yummypc\tools\mpv
```

Резервный официальный источник (если GitHub недоступен): SourceForge-сборки shinchiro
`https://sourceforge.net/projects/mpv-player-windows/files/64bit/` — раскладка та же, файлы `.7z`.

### 1.1. `mpv.exe` vs `mpv.com` (важно для клиента)

- `mpv.exe` — **GUI-подсистема**: у процесса нет консоли. Поэтому:
  - `mpv.exe --version`, запущенный из консоли, **ничего не печатает** (вывод ловится только при
    перенаправлении: `Start-Process ... -RedirectStandardOutput`, `ProcessBuilder` с pipe; именно так
    получен `version.txt`);
  - Если у процесса не выставлены stdin/stdout/stderr (а именно так будет при запуске из `javaw`/Explorer),
    mpv.exe автоматически включает **pseudo-gui** профиль: `terminal=no`, `force-window=yes`, `idle=yes`
    (см. лог: `--player-operation-mode=pseudo-gui`, `Applying profile 'builtin-pseudo-gui'`).
    Наши явные флаги на командной строке имеют приоритет над этим профилем — проверено.
- `mpv.com` — 3,5 КБ консольный launcher, удобен для отладки/CLI: печатает `--version`, статус-строку,
  лог в консоль. Клиент должен запускать **`mpv.exe`** (иначе будет мигать консольное окно).

Версия (для протокола):

```
mpv v0.42.0-200-g7f505de18 Copyright © 2000-2026 mpv/MPlayer/mplayer2 projects
 built on Nov 21 2026 14:09:22
libplacebo version: v7.399.0
FFmpeg version: N-129180-gb54992769
```

---

## 2. Почему mpv-процесс, а не libmpv

Для спайка и первой версии клиента выбран **отдельный процесс `mpv.exe` + управление по JSON IPC**:
- не нужна JNI/нативная загрузка `libmpv-2.dll` и ручная отрисовка в текстуру Skia;
- падение плеера не роняет приложение;
- встроенный VO mpv (`--wid`) даёт аппаратное декодирование и D3D11-вывод «из коробки»;
- IPC-контракт стабилен и документирован.

libmpv (`mpv-dev-*.7z`, render API) остаётся вариантом на будущее, если понадобится рисовать видео
внутри Compose-поверхности и накладывать UI поверх кадра (§8).

---

## 3. Headless-проверка HLS (подтверждение работоспособности)

Команда (консольный launcher, чтобы видеть лог):

```powershell
tools\mpv\mpv.com --no-config --frames=30 --vo=null --ao=null `
  --log-file=tools\mpv\test-hls.log --msg-level=all=v `
  https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
```

Результат: **exit code 0**, время ~3,8 с. Ключевые строки `test-hls.log`:

```
[0.008][v][stream_callback] Opening https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
[0.255][v][lavf] Found 'hls' at score=100 size=752.
[2.772][v][demux] Detected file format: hls (libavformat)
[2.772][v][cplayer] Opening done: https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
[2.774][v][vd] Selected decoder: h264 - H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10
[2.786][v][vd] Decoder format: 1920x1080 yuv420p ... 
[2.786][v][vo/null] reconfig to 1920x1080 ...
[2.786][v][cplayer] first video frame after restart shown
[3.255][v][cplayer] finished playback, success (reason 0)
[3.272][i][cplayer] Exiting... (End of file)
```

То есть: HLS-мастер-плейлист открылся, выбран FHD-вариант (1920x1080 h264 + aac), 30 кадров
декодированы и отданы в `vo=null`, воспроизведение завершилось успешно. `--frames=30` ограничивает
число кадров (для «прогрева» и автотестов), в боевом режиме флаг не нужен.
Полный лог: `tools\mpv\test-hls.log`; консольный вывод: `tools\mpv\test-hls-console.log`.

---

## 4. Встраивание mpv в окно (`--wid`)

### 4.1. Как это работает

`--wid=<HWND в десятичном виде>` — mpv создаёт **своё дочернее окно** внутри указанного HWND,
масштабирует видео под размер родителя и сам перестраивает дочернее окно при ресайзе родителя.
Ограничения из manuals (локально: `tools\mpv\doc\mpv-options.rst`, `--wid`):

- на win32 ID трактуется как `HWND`, передавать как `uint32_t` (десятичное беззнаковое);
  **отрицательные значения mpv не принимает**;
- `--wid=0` — специальное значение (рисовать поверх обоев, под иконками) — нам не нужно;
- `--wid=-1` — отцепиться от окна (нам не нужно);
- HWND должен существовать к моменту запуска mpv; при закрытии родителя дочернее окно умирает.

В Kotlin передавать так (HWND из JNA `Pointer.peer` может быть со знаком):

```kotlin
val hwnd: Long = ...                     // 64-битный handle окна
val widArg = "--wid=" + (hwnd and 0xFFFFFFFFL).toString()   // как uint32
```

### 4.2. Как получить HWND в Compose Desktop

**Основной способ (Compose 1.12.2, которая уже используется в `pc`):**
внутри `Window { ... }` доступен `window: ComposeWindow` (это `FrameWindowScope.window`,
`ComposeWindow` наследует `JFrame`), у него есть публичное свойство:

```kotlin
// androidx.compose.ui.awt.ComposeWindow
val windowHandle: Long   // HWND on Windows, Window on X11, NSWindow on macOS
```

Проверено статически: в gradle-кэше проект резолвит `org.jetbrains.compose.ui:ui-desktop:1.12.2`,
и в jar `androidx/compose/ui/awt/ComposeWindow.class` есть `windowHandle`/`getWindowHandle`.

Свойство валидно только после создания native-peer'а окна: ждать, пока окно показано.

```kotlin
@Composable
fun PlayerWindowHost() {
    Window(onCloseRequest = ::exitApplication, title = "YummyPC") {
        val win = window                       // ComposeWindow
        LaunchedEffect(Unit) {
            while (!win.isShowing || win.windowHandle == 0L) kotlinx.coroutines.delay(16)
            val hwnd = win.windowHandle        // <— HWND для --wid
            // startMpv(wid = hwnd)
        }
    }
}
```

**Fallback через JNA** (`jna` и `jna-platform` уже подключены в `pc/app/build.gradle.kts`, версия 5.18.1):

```kotlin
import com.sun.jna.Native

val hwndJframe  = Native.getWindowPointer(win).peer        // HWND топлевела (Window)
val hwndCanvas  = Native.getComponentPointer(canvas).peer  // HWND AWT-компонента (Canvas)
```

Требования JNA к объекту (проверено по исходникам 5.18.1, `Native.AWT.getComponentID`):
компонент должен быть **heavyweight** и **displayable** — иначе `IllegalArgumentException` /
`IllegalStateException`. То есть сначала окно/канвас показываются, потом берём HWND.

**Если нужно видео не на всё окно, а в отдельной области экрана** (наш случай: снизу панель управления):
кладём heavyweight `java.awt.Canvas` через `SwingPanel`, ждём `canvas.isDisplayable`, берём его HWND
через `Native.getComponentPointer(canvas).peer` и передаём в `--wid`. mpv создаст дочернее окно
внутри Canvas и будет масштабировать видео в его client area; при изменении размеров Canvas
дочернее окно mpv перестроится (поведение «resize вместе с parent» проверено на тестовом хосте, §4.3).

```kotlin
val canvas = remember { java.awt.Canvas().apply { background = java.awt.Color.BLACK } }
SwingPanel(
    modifier = Modifier.fillMaxSize(),   // область видео 16:9
    factory = { canvas },
    update = {}
)
LaunchedEffect(canvas) {
    while (!canvas.isDisplayable || canvas.width == 0) delay(16)
    val hwnd = Native.getComponentPointer(canvas).peer
    // startMpv(wid = hwnd)
}
```

> Замечание: сам Compose рисует в окно JFrame (Skia), а Canvas/дочернее окно mpv — нативные
> дочерние окна, они всегда рисуются **поверх** Compose-канвы. См. ограничения в §9.

### 4.3. Что проверено на практике (`tools\mpv\wid-test\`)

Тестовый хост — короткоживущее WinForms-окно 800x450 (замена ComposeWindow, тот же принцип HWND):

```
host HWND: 0xA071A (657178)
mpv: --no-config --wid=657178 --vo=gpu --untimed --frames=90 --keep-open=no --idle=no --ao=null ...
```

- появилось дочернее окно `class=mpv title='seg0.ts - mpv' visible=True size=784x411`
  (ровно client area родителя);
- в логе: `first video frame after restart shown`, D3D11-контекст `NVIDIA GeForce RTX 5060 Ti`,
  `Using Direct3D 11 feature level 12_1`;
- скриншоты подтверждают реальный кадр (Big Buck Bunny), а не чёрное окно:
  `wid-test-screencopy.png`, `wid-test-printwindow.png`;
- после ресайза родителя на +160x+90 дочернее окно mpv стало `944x501` — **ресайз работает**;
- mpv завершился с кодом 0 (после 90 кадров), родитель закрыт.
- полный лог: `tools\mpv\wid-test\wid-test-summary.log`, `wid.mpv.log`.

---

## 5. HTTP-заголовки (`--http-header-fields`)

YummyTV-потоки часто требуют `Referer`, `User-Agent`, иногда `Authorization`/`Cookie`.
mpv поддерживает это опцией `--http-header-fields` (string list, значения через запятую).

### 5.1. Два рабочих варианта

**Вариант A — одним списком (проверено):**

```
--http-header-fields=User-Agent: YummyTV/1.0 (Windows NT 10.0),Referer: https://yummyanime.tv/,Authorization: Bearer <token>
```

**Вариант B — по одному заголовку на флаг (проверено, рекомендуется для значений с запятыми):**

```
--http-header-fields-append=User-Agent: YummyTV/1.0 (Windows NT 10.0)
--http-header-fields-append=Referer: https://yummyanime.tv/
--http-header-fields-append=Authorization: Bearer <token>
--http-header-fields-append=Cookie: a=b; c=d
```

Почему B: в варианте A запятая — разделитель списка; запятая внутри значения требует экранирования
(`\,`). Форма `-append` берёт одно значение как есть (см. manuals, «List Options»), поэтому для
произвольных `Cookie`/`Authorization` она безопаснее. Обе формы прогнаны на локальном сервере — обе
работают.

При запуске из Kotlin `ProcessBuilder` каждый флаг — **отдельный элемент списка аргументов**,
кавычки не нужны (кавычки нужны только в cmd/PowerShell, чтобы пробелы не разбились на аргументы).

### 5.2. Практическая проверка (`tools\mpv\http-test\`)

Поднимался локальный HTTP-сервер (сырой TCP), который отдавал HLS-плейлист и два `.ts`-сегмента
и **требовал** заголовок `X-Client-Token: yummytv-secret`, логируя все заголовки каждого запроса.

Результат:

| Прогон | Заголовки | Результат |
|---|---|---|
| positive | `User-Agent`, `Referer`, `Authorization`, `X-Client-Token` (вариант A) | **exit 0**, `Detected file format: hls`, `first video frame after restart shown` |
| positive-append | те же, но через отдельные `--http-header-fields-append` | **exit 0** |
| control | те же, но `X-Client-Token: wrong-token` | сервер отдал **403**, mpv: `HTTP error 403`, `finished playback, loading failed (reason 4)`, **exit 2** |

Из `tools\mpv\http-test\requests.log` видно, что кастомные заголовки пришли **и на плейлист, и на
оба `.ts`-сегмента** (т.е. заголовки пробрасываются и в ffmpeg HLS-demuxer, который тянет сегменты):

```
GET /local.m3u8  → User-Agent: YummyTV/1.0 (Windows NT 10.0)
                   Referer: https://yummyanime.tv/
                   Authorization: Bearer test-token-123
                   X-Client-Token: yummytv-secret
GET /seg0.ts     → (те же заголовки)
GET /seg1.ts     → (те же заголовки)
```

Замечания:
- `Authorization`/`Cookie`/`Referer`/`User-Agent` — всё, что нужно текущим экстракторам YummyTV
  (`streamHeaders`), покрывается этой опцией;
- mpv сам добавляет заголовки вроде `Range: bytes=0-`, `Accept: */*`, `Icy-MetaData: 1` — сервер их
  должен игнорировать;
- при смене заголовков между сериями проще перезапустить mpv-процесс (надёжно) либо задать их через
  IPC `set_property http-header-fields` перед `loadfile` (запись-чтение свойства проверено, влияние
  на HTTP проверялось для CLI-формы; в интеграции проверить при смене источника без рестарта);
- альтернативы: `--user-agent`, `--referrer` — но `--http-header-fields` покрывает всё сразу.

---

## 6. Управление через JSON IPC (named pipe)

### 6.1. Подключение

Флаг: `--input-ipc-server=\\.\pipe\yummypc`
(по manuals префикс `\\.\pipe\` можно не писать — mpv добавит сам; лучше писать явно).
На Windows mpv создаёт named pipe; клиент открывает его как файл/поток и обменивается
**JSON-объектами по одному в строке** (разделитель `\n`, UTF-8).

```jsonc
// команда клиента:
{"command":["loadfile","https://...m3u8","replace"],"request_id":1}
// ответ:
{"data":{"playlist_entry_id":1},"request_id":1,"error":"success"}
```

- `request_id` необязателен, но **крайне рекомендуется**: mpv параллельно шлёт асинхронные события,
  и ответы надо сопоставлять по `request_id`.
- Ответ об ошибке: `{"request_id":N,"error":"property not found"}` (наблюдали также
  `"invalid parameter"` и `"property unavailable"`).
- mpv **сам** шлёт события в тот же поток (`{"event":"..."}`), без запроса `observe_property`:
  `idle`, `start-file`, `file-loaded`, `audio-reconfig`, `video-reconfig`, `playback-restart`,
  `seek`, `end-file`. Изменения свойств (`property-change`) — только после `observe_property`.
  Клиент обязан читать поток построчно и разбирать строки с полем `event`, не считая их ответами.
  Это проверено: в `ipc-test\ipc-test.log` события приходят между командами и ответами.
- При `quit` mpv может закрыть pipe раньше, чем отдать ответ — не ждите ответа, ждите завершения процесса.
- mpv создаёт pipe при старте; клиент подключается с ретраями (~10–15 с, в тестах хватало 1–2 попыток).

### 6.2. Проверенные команды (фактические ответы из логов)

| Действие | Команда | Ответ (пример) |
|---|---|---|
| Готовность/idle | `{"command":["get_property","idle-active"]}` | `{"data":true,"request_id":1,"error":"success"}` |
| Загрузить поток | `{"command":["loadfile","https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8","replace"]}` | `{"data":{"playlist_entry_id":1},"request_id":4,"error":"success"}` |
| Позиция | `{"command":["get_property","time-pos"]}` | `{"data":1.373222,"request_id":21,"error":"success"}` (до загрузки — `error: property unavailable`) |
| Длительность | `{"command":["get_property","duration"]}` | `{"data":634.584000,"request_id":22,"error":"success"}` |
| Заголовок/название | `{"command":["get_property","media-title"]}` | `{"data":"x36xhzz.m3u8","request_id":23,"error":"success"}` |
| Буферизация | `{"command":["get_property","paused-for-cache"]}` | `{"data":false,"request_id":24,"error":"success"}` |
| Пауза | `{"command":["set_property","pause",true]}` | `{"request_id":25,"error":"success"}` |
| Снять паузу | `{"command":["set_property","pause",false]}` | `{"request_id":29,"error":"success"}` |
| Прочитать паузу | `{"command":["get_property","pause"]}` | `{"data":true,"request_id":26,"error":"success"}` |
| Перемотка (seek, absolute) | `{"command":["seek",30,"absolute"]}` | `{"data":null,"request_id":27,"error":"success"}` → `time-pos = 30.000000` |
| Подписка на позицию | `{"command":["observe_property",1,"time-pos"]}` | `{"request_id":1,"error":"success"}` + события `{"event":"property-change","id":1,"name":"time-pos","data":...}` |
| Подписка на конец | `{"command":["observe_property",2,"eof-reached"]}` | событие `property-change` с `data:true` перед концом |
| Заголовки через IPC | `{"command":["set_property","http-header-fields",["User-Agent: YummyTV-IPC/2.0","Referer: https://yummyanime.tv/"]]}` | `success`; чтение возвращает массив |
| Ошибка свойства | `{"command":["get_property","no-such-property"]}` | `{"request_id":30,"error":"property not found"}` |
| Ошибка параметра | `{"command":["seek","abc","absolute"]}` | `{"request_id":31,"error":"invalid parameter"}` |
| Выход | `{"command":["quit"]}` | mpv завершается, exit code 0 |

Конец потока (проверено на локальном TS, `--untimed`):

```
EVENT {"event":"property-change","id":1,"name":"eof-reached","data":false}
EVENT {"event":"playback-restart"}
EVENT {"event":"property-change","id":1,"name":"eof-reached","data":true}
EVENT {"event":"end-file","reason":"eof","playlist_entry_id":1}
```

Полные трассы: `tools\mpv\ipc-test\ipc-test.log`, `tools\mpv\ipc-test\eof-test.ipc.log`,
`tools\mpv\smoke-test\player-smoke.ipc.log`.

### 6.3. Рекомендуемый сценарий воспроизведения (проверен end-to-end)

1. Запустить mpv в режиме `--idle=yes --pause=yes --wid=<HWND> --input-ipc-server=...`.
2. Подключиться к pipe (ретраи).
3. `observe_property` на `time-pos` (id=1), при необходимости `eof-reached`, `paused-for-cache`,
   `cache-buffering-state`.
4. `loadfile <url> replace` (заголовки уже заданы флагами при старте mpv).
5. Дождаться события `file-loaded` (или первого доступного `time-pos`).
6. Для «продолжить просмотр»: `seek <секунды> absolute` — **работает и на паузе** (проверено:
   `time-pos` ровно 30.0 после `seek 30 absolute` при `pause=true`), затем `set_property pause false`.
7. Слушать `property-change`/`end-file`; на `end-file` — показать «следующая серия» и т.п.
8. При завершении: `{"command":["quit"]}` → дождаться exit code 0 (fallback: `Process.destroyForcibly()`).

В e2e-тесте (`tools\mpv\smoke-test\`) этот сценарий отработал на реальном HLS:
`mpv exit code: 0`, `file-loaded: True`, `video-reconfig: 2`, `max time-pos: 1.0899`,
скриншот с кадром `player-smoke-screenshot.png`.

### 6.4. JVM-клиент: набросок

В этой среде нет JDK, поэтому JVM-сторона не запускалась (Windows-сторона проверена .NET-клиентом).
Для интеграции — два стандартных способа; оба не требуют нативных библиотек, кроме уже подключённой JNA:

```kotlin
// Вариант 1: java.io.RandomAccessFile открывает named pipe как файл (просто, но требует проверки:
// jdk использует CreateFile с share read/write; для mpv-пайпов по сообщениям обычно работает).
val pipe = RandomAccessFile("\\\\.\\pipe\\yummypc", "rw")
// писать: pipe.write((json + "\n").toByteArray(Charsets.UTF_8)) — RandomAccessFile пишет без буфера
// читать: построчно (pipe.readLine() — deprecated, но для pipe работает), либо обернуть в InputStream

// Вариант 2 (надёжнее): JNA kernel32 CreateFile/ReadFile/WriteFile (jna-platform уже в зависимостях)
Kernel32.INSTANCE.CreateFile(...)  // GENERIC_READ|GENERIC_WRITE, OPEN_EXISTING
```

Практические требования к читателю:
- один выделенный поток/корутина на чтение pipe, разбор строк и диспетчеризация по `event`/`request_id`;
- отмена/закрытие при выходе из экрана плеера; `quit` + ожидание процесса 2 с, затем kill;
- уникальное имя pipe на процесс/плеер (`yummypc-<pid>`), чтобы старый зависший mpv не блокировал новый;
- таймауты подключения и на команды; при EOF pipe — считать, что mpv умер, и перезапустить.

---

## 7. Готовая командная строка для клиента

### 7.1. Production-вариант (встроенный плеер на всё окно / на Canvas-область)

Флаги (проверены в e2e-тесте; **для боевого запуска убрать `--ao=null` и `--log-file`**):

```
mpv.exe
  --no-config
  --terminal=no
  --input-ipc-server=\\.\pipe\yummypc
  --wid=<HWND>
  --vo=gpu
  --gpu-api=d3d11
  --hwdec=auto-safe
  --force-window=no
  --idle=yes
  --pause=yes
  --keep-open=no
  --no-osc
  --no-osd-bar
  --osd-level=0
  --no-input-default-bindings
  --input-vo-keyboard=no
  --audio-client-name=YummyPC
  --title=YummyPC
  --http-header-fields-append=<Header1: value>
  --http-header-fields-append=<Header2: value>
```

Одной строкой (как строка для лога; в Kotlin — список аргументов, без кавычек):

```
mpv.exe --no-config --terminal=no --input-ipc-server=\\.\pipe\yummypc --wid=123456 --vo=gpu --gpu-api=d3d11 --hwdec=auto-safe --force-window=no --idle=yes --pause=yes --keep-open=no --no-osc --no-osd-bar --osd-level=0 --no-input-default-bindings --input-vo-keyboard=no --audio-client-name=YummyPC --title=YummyPC
```

| Флаг | Зачем |
|---|---|
| `--no-config` | не читать пользовательский `mpv.conf`/скрипты — детерминированное поведение |
| `--terminal=no` | без консоли/статус-строки (у `mpv.exe` консоли и так нет, флаг для явности) |
| `--input-ipc-server=\\.\pipe\yummypc` | JSON IPC для управления |
| `--wid=<HWND>` | встроить видео в окно/Canvas (см. §4) |
| `--vo=gpu`, `--gpu-api=d3d11` | D3D11-вывод (проверено; `gpu-next`/`vulkan` — альтернативы) |
| `--hwdec=auto-safe` | аппаратное декодирование (в тесте включился `d3d11va` на RTX 5060 Ti) |
| `--force-window=no` | чтобы mpv не открыл **своё** окно, если поток не открылся (pseudo-gui ставит yes) |
| `--idle=yes` | процесс живёт после EOF и умеет ждать следующую серию |
| `--pause=yes` | старт на паузе: сначала `loadfile`, затем (опционально) `seek`, затем unpause |
| `--keep-open=no` | по EOF не «зависать» на последнем кадре; UI ловит `end-file` (если нужен последний кадр — `yes`) |
| `--no-osc`, `--no-osd-bar`, `--osd-level=0` | без встроенного OSD/OSC — UI рисуем сами |
| `--no-input-default-bindings`, `--input-vo-keyboard=no` | mpv не перехватывает клавиши/мышь |
| `--audio-client-name=YummyPC` | имя приложения в микшере Windows |
| `--title=YummyPC` | заголовок дочернего окна (для диагностики) |
| `--http-header-fields-append=...` | заголовки источника (см. §5) |

Опционально:
- `--load-scripts=no` — не грузить встроенные Lua-скрипты (`osc`, `ytdl_hook`, `stats`, `console`,
  `auto_profiles`, `select`, `positioning`, `commands` — они грузятся даже с `--no-config`).
  Ускоряет старт; `ytdl_hook` для прямых URL не нужен, но проверить, что не отвалилось нужное;
- `--cache=yes --demuxer-max-bytes=...` — тюнинг буфера для медленных CDN (по умолчанию
  `demuxer-max-bytes` = 150 MiB);
- `--log-file=... --msg-level=all=v` — только для отладки (в проде не включать: лишний I/O).

Тестовый вариант (как в e2e) дополнительно содержал `--ao=null` (чтобы не было звука во время
автотеста) и `--log-file=...`.

### 7.2. Порядок запуска (важно)

1. Показать окно/положить Canvas в дерево Compose, дождаться `isShowing`/`isDisplayable`.
2. Взять HWND (§4.2).
3. Запустить `mpv.exe` с флагами §7.1 (рабочий каталог — папка `tools\mpv`, либо абсолютные пути).
4. Подключиться к pipe с ретраями.
5. `loadfile`, наблюдение свойств/событий (§6.3).
6. При закрытии экрана/приложения: `quit` → wait → kill; окно закрывать **после** выхода mpv
   (или убить mpv до закрытия окна).

---

## 8. Набросок интеграции в Kotlin/Compose

```
pc/app/src/main/kotlin/su/afk/yummy/pc/player/
  MpvProcess.kt        // ProcessBuilder, подбор HWND/аргументов, жизненный цикл
  MpvIpcClient.kt      // named pipe, JSON-строки, request_id, события
  MpvPlayer.kt         // фасад: load(url, headers, startPosition), pause(), seek(), state flow
  PlayerScreen.kt      // Compose: Canvas (видео) + панель управления под ним
```

Минимальный запуск процесса:

```kotlin
val pb = ProcessBuilder(
    mpvExe.absolutePath,
    "--no-config", "--terminal=no",
    "--input-ipc-server=\\\\.\\pipe\\yummypc",
    "--wid=${windowHandle and 0xFFFFFFFFL}",
    "--vo=gpu", "--gpu-api=d3d11", "--hwdec=auto-safe",
    "--force-window=no", "--idle=yes", "--pause=yes", "--keep-open=no",
    "--no-osc", "--no-osd-bar", "--osd-level=0",
    "--no-input-default-bindings", "--input-vo-keyboard=no",
    "--audio-client-name=YummyPC", "--title=YummyPC",
    "--http-header-fields-append=User-Agent: YummyTV/1.0",
    "--http-header-fields-append=Referer: $referer",
    // ... Authorization, если нужен ...
).redirectErrorStream(true)
val process = pb.start()
```

Состояние плеера (черновик):

```kotlin
data class MpvState(
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val paused: Boolean = true,
    val buffering: Boolean = false,
    val title: String? = null,
    val ended: Boolean = false,
)
```

События IPC → StateFlow: `property-change` (time-pos/duration/pause/paused-for-cache/media-title),
`end-file` → `ended=true`; ошибки загрузки — по `end-file.reason`/лог mpv (в тестах при 403:
`finished playback, loading failed (reason 4)`).

---

## 9. Известные ограничения и риски

1. **Compose UI не рисуется поверх видео.** Дочернее окно mpv — нативное и всегда выше Skia-канвы
   JFrame. Варианты:
   - панель управления/субтитры/оверлеи — **вне** области видео (снизу/сбоку) — самый простой и
     предсказуемый путь для первой версии;
   - встроенные OSC/OSD mpv (не подходит, если нужен кастомный Compose-дизайн);
   - отдельное `always-on-top` прозрачное окно-оверлей, синхронизированное с геометрией видео
     (JWindow/второй Compose-окно) — сложнее, требует аккуратной синхронизации перемещения/фокуса;
   - libmpv render API + собственная отрисовка в текстуру Skia — дорого, отдельный этап.
2. **Ввод внутри области видео уходит в окно mpv**, а не в Compose. Кнопки/клики в этой области
   не работают как Compose-элементы. Мы отключаем встроенные биндинги mpv
   (`--no-input-default-bindings --input-vo-keyboard=no`), но события туда всё равно приходят в
   дочернее окно. Клик-по-видео (пауза по клику) в первой версии делать кнопкой на панели под видео.
3. **DRM/EME не поддерживается** сборками mpv (нет Widevine). Для текущих источников YummyTV это не
   нужно (прямые HLS/MP4), но если появится DRM-балансер — этот путь не подойдёт.
4. **Лицензия.** Сборка zhongfly — GPL (в списке features есть `gpl`). Мы запускаем mpv отдельным
   процессом и распространяем его бинарник вместе с клиентом — нужно соблюсти GPL (положить текст
   лицензии и предложение исходников; исходники mpv/ffmpeg доступны публично). Проверить с юристом
   проекта при упаковке инсталлятора.
5. **Размер.** `mpv.exe` ~123 МБ (+doc). Это заметно увеличит установщик; вариант «докачивать при
   первом запуске» надо продумать отдельно (лицензия/офлайн-режим).
6. **mpv.exe не в git.** Бинарник и тестовый мусор не коммитить; в CI/сборке — шаг скачивания
   архива по URL+sha256 (см. §1). Решить, как поставлять: в MSI рядом с приложением или отдельным
   каталогом `tools\mpv`.
7. **Pseudo-gui профиль.** При запуске из GUI-приложения mpv автоматически включает `force-window=yes`,
   `idle=yes`, `terminal=no`. Мы явно задаём нужные значения, но при добавлении новых флагов помнить
   о приоритетах (CLI > профиль).
8. **Смена HWND/окна.** `--wid` нельзя поменять на лету — при переносе плеера в другое окно/пересоздании
   ComposeWindow mpv надо перезапускать.
9. **Несколько инстансов mpv.** Имя pipe должно быть уникальным, иначе второй mpv не создаст сервер;
   клиент должен гарантировать единственный экземпляр плеера или уникальные имена.
10. **Аудио-фокус/громкость.** На Windows нет аналога Android audio focus: громкость — системная на
    приложение (`--audio-client-name`), пауза при звонках/других плеерах — не автоматическая (можно
    добавить позже через WASAPI/SMTC).
11. **Live-HLS** не проверялся на seek/буфер (в тестах VOD mux). Для live seek может игнорироваться —
    UI должен скрывать перемотку, если `duration` не приходит.
12. **HLS-заголовки при редиректах** отдельно не проверялись (в нашем тесте редиректов не было).
13. **Начальная задержка** до первого кадра зависит от CDN: в тестах от ~0.1–3 с (headless, локальная
    сеть) до ~12 с в одном e2e-прогоне (сеть/CDN). UI нужен индикатор загрузки; используйте
    `paused-for-cache`/`cache-buffering-state`/события `file-loaded`.
14. **DPI/мультимонитор.** Дочернее окно масштабируется по client area родителя; при переносе окна на
    монитор с другим DPI Compose и mpv могут кратковременно разойтись по пикселям — проверить на
    реальной интеграции (в тесте DPI был 96).
15. **JVM-реализация IPC не проверена в этой среде** (нет JDK) — см. §10.

---

## 10. Что проверено, а что не проверено

### Проверено на практике (логи/скриншоты в `tools\mpv\`)
- [x] скачивание, распаковка, checksum, `mpv.exe --version` (§1);
- [x] HLS headless: открытие, декодирование 30 кадров, exit 0 (`test-hls.log`, §3);
- [x] HTTP-заголовки: плейлист + сегменты, 2 формы записи, негативный контроль 403/exit 2 (`http-test\`, §5);
- [x] IPC named pipe: idle/loadfile/pause/seek/time-pos/duration/observe/errors/quit; события `idle`,
      `start-file`, `file-loaded`, `audio-reconfig`, `video-reconfig`, `playback-restart`, `seek`,
      `property-change`, `end-file` (`ipc-test\`, §6);
- [x] `--wid`: дочернее окно внутри чужого HWND, реальный кадр (скриншоты), D3D11 + `d3d11va`,
      авто-ресайз дочернего окна, корректный выход (`wid-test\`, §4);
- [x] e2e production-командная строка: `--wid` + IPC + заголовки + реальный HLS + `pause`/`loadfile`,
      exit 0, кадр на скриншоте (`smoke-test\`, §6.3);
- [x] наличие `ComposeWindow.windowHandle` в используемой Compose 1.12.2 (проверка class-файлов
      `ui-desktop-1.12.2.jar` в gradle-кэше);
- [x] требования JNA `Native.getWindowPointer/getComponentPointer` (по исходникам jna 5.18.1).

### Не проверено (сделать на первом шаге интеграции)
- [ ] запуск mpv с HWND, полученным **из Compose/JNA** (нужен JDK + сборка приложения; в этой сессии
      JDK отсутствует — `java` не найден, тестовый хост был WinForms);
- [ ] JVM-клиент IPC (`RandomAccessFile` или JNA kernel32) — протокол проверен .NET-клиентом;
- [ ] живой звук через WASAPI (все тесты шли с `--ao=null`, чтобы не шуметь);
- [ ] поведение Canvas внутри `SwingPanel` при ресайзах окна/переходе в fullscreen;
- [ ] end-file и переключение серий на живом YummyTV-источнике (не только на mux/VOD);
- [ ] live-HLS (буферизация, отсутствие duration, seek);
- [ ] DPI ≠ 96 и мультимонитор;
- [ ] упаковка в MSI/EXE (`nativeDistributions`) вместе с `mpv.exe` и лицензионные тексты.

### Чек-лист интеграции (минимальный)
1. `MpvLocator`: найти `tools\mpv\mpv.exe` (dev) / ресурсы инсталлятора (prod), проверить версию.
2. Взять HWND из Compose (полноэкранный вариант) или из Canvas (вариант с панелью).
3. Запустить процесс с флагами §7.1, уникальный pipe, ретраи подключения.
4. `loadfile` + `seek` (resume) + `pause=false`; стримить `time-pos`/`duration`/`paused-for-cache` в state.
5. Обработать `end-file`, ошибки открытия (mpv exit 2 / `loading failed`), «следующая серия» = новый
   `loadfile` (заголовки — либо те же, либо `set_property`, либо рестарт процесса).
6. Закрытие экрана: `quit` → wait 2s → `destroyForcibly`; закрывать окно после mpv.
7. Прогнать smoke-test на dev-машине с JDK и зафиксировать результат в этом документе.

---

## 11. Как воспроизвести тесты

Скрипты лежат в `tools\mpv\*\run-*.ps1` (PowerShell). В этой среде execution policy блокирует
`.ps1`, поэтому запуск:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\mpv\http-test\run-header-test.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\mpv\ipc-test\run-ipc-test.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\mpv\ipc-test\run-eof-test.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\mpv\wid-test\run-wid-test.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\mpv\smoke-test\run-player-smoke.ps1
```

- `http-test\run-header-test.ps1` поднимает локальный HTTP-сервер (HLS + 2 сегмента, требуется
  заголовок), делает 3 прогона mpv и пишет `requests.log`/`header-test-summary.log`.
- `ipc-test\run-ipc-test.ps1` — IPC-команды; `run-eof-test.ps1` — `end-file`/`eof-reached`.
- `wid-test\run-wid-test.ps1` — host-окно, `--wid`, скриншоты, ресайз (окно живёт несколько секунд).
- `smoke-test\run-player-smoke.ps1` — end-to-end production-командная строка + IPC + HLS + скриншот.

Все тесты завершаются сами (или убивают mpv по таймауту) и оставляют `*-summary.log` и `*.mpv.log`.

---

## 12. Источники

- Сборки mpv для Windows (использованные): https://github.com/zhongfly/mpv-winbuild/releases
- Официальные сборки shinchiro: https://sourceforge.net/projects/mpv-player-windows/files/64bit/
- mpv manual (options):
  https://mpv.io/manual/master/#options-wid ,
  https://mpv.io/manual/master/#options-http-header-fields ,
  https://mpv.io/manual/master/#options-input-ipc-server
- mpv JSON IPC: https://mpv.io/manual/master/#json-ipc
- Pseudo-GUI mode: https://mpv.io/manual/master/#pseudo-gui-mode
- Compose Desktop `ComposeWindow.windowHandle`:
  https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/compose/ui/ui/src/desktopMain/kotlin/androidx/compose/ui/awt/ComposeWindow.desktop.kt
- JNA `Native.getWindowPointer/getComponentPointer`:
  https://github.com/java-native-access/jna/blob/master/src/com/sun/jna/Native.java

Локальные копии man-страниц (свежий master на 2026-11-22): `tools\mpv\doc\mpv-options.rst`,
`mpv-mpv.rst`, `mpv-ipc.rst`, `mpv-input.rst`; исходники JNA 5.18.1 для проверки AWT-HWND:
`tools\mpv\doc\jna-src\`.
