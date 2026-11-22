# Экстрактор Alloha для ПК (модуль `:extractors`)

Порт `PlayerStreamExtractor`/`AllohaExtractor` из Android-клиента YummyTV на Kotlin/JVM: получает по
iframe-ссылке балансера Alloha прямую HLS-ссылку для внешнего плеера (mpv), вместе с заголовками CDN,
которые этот плеер должен отдавать. Живая проверка 22.11.2026: ссылка получена, HLS-манифест отдаёт
`HTTP 200` и `#EXTM3U`, mpv открыл поток и декодировал 5 кадров (код 0).

**Based on YummyTV — https://github.com/Helandy/YummyTV (CC BY-NC 4.0).** Логика перенесена из
`src/YummyTV-main/source/alloha/data/src/main/kotlin/su/afk/yummy/tv/source/alloha/data/extractor/`
(13 файлов); лицензия проекта и NOTICE — в корне `pc/`, коммерческое использование запрещено.

## 0. Коротко

- **Модуль:** `pc/extractors`, Gradle-проект `:extractors`, Kotlin JVM, toolchain 21.
- **Пакет:** `su.afk.yummy.pc.extractors.alloha`.
- **Вход:** iframe-URL вида
  `https://alloha.yani.tv/?token_movie=…&translation=213&season=2&episode=1&token=…&hidden=…`.
- **Выход:** `ResolvedStream(url, headers, qualities)`, где `url` — прямой HLS-манифест CDN, `headers` —
  заголовки сессии для mpv (`origin`, `referer`, `user-agent`, `accept-language`, `sec-fetch-*`,
  `sec-ch-ua*`, `authorizations`, при удаче — `accepts-controls`, `x-requested-with`), `qualities` —
  лесенка выбранной озвучки по возрастанию.
- **Зависимости:** OkHttp 5.5.1 (WebSocket и HTTP), kotlinx-coroutines, `org.json:json:20250517`
  (замена Android `org.json`). Новых зависимостей не добавлено, `build.gradle.kts` не менялся.
- **Тесты:** `:extractors:test --tests "*alloha*"` — 52 офлайн-теста (перестановка `Borth`,
  fingerprint, разбор `bnsi`, страницы, зеркал, заголовков, профиля, контракт) + 1 живой тест под
  переменной `ALLOHA_LIVE=1`.
- **Регистрация:** `alloha/AllohaRegistration.kt`, `internal val allohaResolver: StreamResolver` —
  ведущий агент добавляет в `ExtractorRegistry` (общий файл порт не трогает).

## 1. Контракт API

```kotlin
package su.afk.yummy.pc.extractors.alloha

class AllohaResolver(
    val userAgent: String = allohaDefaultUserAgent(), // Chrome под текущую ОС
    val onStage: (String) -> Unit = {},               // прогресс для CLI/логов
) : StreamResolver {
    override fun supports(iframeUrl: String): Boolean  // iframeUrl.contains("alloha", ignoreCase = true)
    override suspend fun resolve(iframeUrl: String): Result<ResolvedStream>

    companion object {
        fun releaseActiveSession()   // закрыть живой WebSocket/отчёты, например когда mpv закрылся
    }
}

internal val allohaResolver: StreamResolver = AllohaResolver()   // AllohaRegistration.kt
```

- Ошибки наружу не бросаются: `resolve` возвращает `Result`.
- `AllohaSourceUnavailableException` — Alloha открылась, но источника нет (404-страница «Ошибка!»,
  пустой `hlsSource`, ни одной лесенки). Не повторяется: той же ссылкой лечится только смена озвучки
  или серии.
- `AllohaResolveException` — технический отказ (страница не открылась, нет `viewporti`/`fileList`,
  `bnsi` не JSON или пуст, HTTP-код и т.п.); в тексте — причина до 80 символов.
- `userAgent` лучше не менять: из него строятся client hints CDN и fingerprint установки; менять его
  между запусками — выглядеть для CDN как ферма ботов (в Android-версии UA выбирается один раз на
  процесс, здесь он фиксируется в конструкторе).

## 2. Алгоритм (1:1 с Android-версией)

1. **Нормализация** iframe-URL: `//host/…` → `https://host/…`.
2. **`GET` страница плеера** с `Referer: https://ru.yummyani.me/`, браузерным UA и `Accept-Language`.
   Из HTML вынимаются:
   - `<meta name="viewporti" content="…">` — зерно текущей загрузки страницы;
   - `const fileList = JSON.parse('…')` → `active.id` — числовой id файла для пути `bnsi`;
   - `<script src="…/app.<хеш>.js">` — скрипт сайта, из него `GET`-ом берётся ключ `Authorizations`
     (длинная константа 340–420 символов, имя скрипта несёт хеш содержимого, поэтому ключ кэшируется
     по URL);
   - `userParam = { token: '…', domain: '…' }` — токен и домен для HTTP-отчётов;
   - `token` для `bnsi` берётся из query самой iframe-ссылки.
   Ошибка страницы: `404` — это ответ Alloha «такой серии/озвучки/сезона нет» →
   `AllohaSourceUnavailableException` с текстом заглушки; остальные неуспешные коды — `IOException`.
3. **`POST https://alloha.yani.tv/bnsi/movies/{activeId}`** с формой `token`, `av1=false`,
   `autoplay=0`, `audio=`, `subtitle=` и заголовком `Borth` (см. §3). Пустой `audio` = «все озвучки».
4. **Разбор `bnsi`** (`AllohaResponseParsing`): `hlsSource[]` — озвучки, у каждой лесенка
   `quality{ "1080": "url1 or url2", … }`; ссылки `//host/…` получают `https:`; метки качеств
   нормализуются (`1080` → `1080p`) и сортируются по числу; `audioId` без значения заменяется индексом,
   метка — `#N`; `tracks[]` с `kind == "captions"` — субтитры. Пустой `hlsSource`/нет качеств →
   `AllohaSourceUnavailableException`.
5. **Зеркала** (`AllohaMirrorPicker`): каждое `"url1 or url2 or url3"` пробуется GET-ом с заголовками
   CDN, таймаут 4 с; берётся первое ответившее, вердикт кэшируется по хосту (одна проба на узел за
   сессию). См. отличие от Android в §5.
6. **`Borth`** = `<fingerprint>|<viewporti после трёх перестановок>` (порт `AllohaBorth.kt` один в один:
   группировка по длине двоичной записи индекса → по числу нулей в конце → шаг 2 по модулю первого
   простого больше длины). Перестановка сверена с эталонными векторами Android-тестов.
7. **WebSocket сессии** из `pnr`/`pnk` ответа: открывается, ждёт первый `config_update.edge_hash` до
   4 с; `edge_hash` кладётся в заголовки как `Accepts-Controls`. Сокет живёт после возврата ссылки,
   отправляет `playback_start`/`init`, состояние из позиции, `playing` раз в 30 с, переподключается с
   задержкой `1.5^n` (до 30 попыток, максимум 15 с); пинг 20 с.
8. **HTTP-отчёты** (`/events`, `/stat`) идут рядом с сокетом, если страница несла `userParam`: стартовая
   пачка событий, heartbeat раз в 10 с с отправкой раз в 30 с, вехи `/stat`; `403`/`429` выключают
   репортёр.
9. **Сборка потока**: берётся озвучка `default: true` (или первая), её лучшая ссылка становится `url`,
   вся её лесенка — `qualities`; заголовки CDN + `x-requested-with` (в Android — имя пакета WebView).

**Лестница отказов** (как в Android): сбой сети/HTTP при открытии повторяется один раз
(`MAX_ATTEMPTS = 2`, пауза 500 мс), отсутствие источника не повторяется; сокет, не ответивший за 4 с,
сессию не ломает — сырые ссылки играли и без `edge_hash`. Высокоуровневое восстановление
(`AllohaSourceBehavior`, `PlayerAllohaRecoveryHandler` в Android) — вне экстрактора: в ПК-порте это дело
UI-слоя, экстрактор по требованию может сделать свежий resolve.

## 3. HTTP-заголовки

| Запрос | Заголовки |
|---|---|
| `GET` iframe-страница | `Referer: https://ru.yummyani.me/`, `User-Agent`, `Accept-Language: ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7` |
| `GET` скрипт сайта (`app.<хеш>.js`) | `User-Agent`, `Referer: <iframe-url>`, `Accept-Language` |
| `POST /bnsi/movies/{id}` | `User-Agent`, `Borth: <64-hex>|<перестановка>`, `Referer: <iframe-url>`, `Origin: https://alloha.yani.tv`, `X-Requested-With: XMLHttpRequest`, `Accept-Language`, form-urlencoded |
| CDN (заголовки в `ResolvedStream.headers`) | `origin`, `referer: <origin>/`, `user-agent`, `accept-language`, `sec-fetch-dest: empty`, `sec-fetch-mode: cors`, `sec-fetch-site: cross-site`, `sec-ch-ua`, `sec-ch-ua-mobile`, `sec-ch-ua-platform`, `authorizations: Bearer <ключ из app.js>`, `accepts-controls: <edge_hash>`, `x-requested-with: su.afk.yummy.pc` |
| WS | `User-Agent`, `Origin: <origin>`, `Accept-Language`; URL `{pnr}?sid={pnk}&v=2.1&t={ms}`, пинг 20 с |
| `/events`, `/stat` | `User-Agent`, `Origin: <origin>`, `Referer: <iframe-url>`, `Accept-Language` |

Без `Origin` CDN даёт 403; Chrome-UA без `Accept-Language` и client hints — 403 `client_blocked`.
Признаки блока в ответе CDN — заголовок `X-VD: session_blocked`/`client_blocked`.

## 4. WebSocket, `edge_hash` и отчёты

Так же, как в Android-версии (`AllohaPlaybackSocket`, `AllohaViewReporter`, `AllohaEdgeHash`):

- сокет открывается на время воспроизведения, `edge_hash` из `config_update` — это значение заголовка
  `Accepts-Controls` для каждого медиазапроса; сервер меняет его каждые 2–6 минут, устаревшее CDN
  отклоняет `403 session_blocked`;
- состояние плеера передаётся сообщениями: `playback_start`, `init`, затем `resumed`/`paused` при смене
  движения позиции, `seeked` при скачке > 3 с, `playing` раз в 30 с пока движется; `current_time` —
  реальная позиция в секундах;
- отчёты `/events`/`/stat` — стартовая пачка, heartbeat раз в 10 с (`bytesLoadedTotal`,
  `playedSecondsDelta`, `isBuffering`, `playbackRate`), вехи `view_percent` при 10/30/60/97 %;
- сессия одна на процесс: следующий resolve закрывает предыдущую; на выходе JVM — shutdown-hook;
  страховочный TTL — 45 минут; хост может закрыть раньше `AllohaResolver.releaseActiveSession()`.

## 5. Отличия от Android-версии (осознанные упрощения)

Портировано: HTTP-сессия `Borth`/`bnsi`, разбор ответа и зеркал, WebSocket с `edge_hash`, отчёты
`/events` и `/stat`, ротация переподключений, «лестница отказов» открытия и профиль установки
(fingerprint из признаков, как у сайта). Не перенесено:

1. **Нет WebView и его bootstrap** — на JVM не нужен: fingerprint считается по алгоритму сайта из
   правдоподобных признаков (Android-версия делала так же, H14/H15 прошли 840+ с без блока).
2. **Нет loopback-прокси и интерцептора заголовков.** В Android прокси подставлял свежий `edge_hash` в
   каждый запрос к CDN и незаметно менял ссылки при ротации. В ПК-порте плеер внешний (mpv), заголовки
   передаются ему один раз при запуске: в `ResolvedStream.headers` уходит первый `edge_hash`. Если
   серия играет дольше нескольких минут и CDN начнёт отдавать `session_blocked`, нужен свежий resolve
   (так же ведёт себя Android-версия после блока). Живой `AllohaEdgeHash.current` уже обновляется
   сокетом — слой с прокси можно добавить позже без переделки экстрактора, если понадобится ротация
   «на лету».
3. **Позиция для сокета/отчётов считается по настенным часам** (`AllohaPlaybackClock`), а не берётся у
   плеера: mpv не сообщает позицию экстрактору. Пауза/перемотка внешнего плеера даёт расхождение
   состояния сокета с реальным скачиванием; для коротких проверок это не мешает, для длинного профиля
   Android это был бы минус.
4. **`bytesLoadedTotal` всегда 0**: в Android байты считал интерцептор медиазапросов; в ПК-порте внешний
   плеер не отдаёт счётчик.
5. **В `qualities` только одна озвучка** (выбранная по умолчанию): контракт `ResolvedStream` плоский,
   а `AllohaAudioTrack` всех озвучек в Java-модель не влезает. При желании хост может вызывать
   экстрактор повторно, если нужна другая озвучка.
6. **Зеркала пробуются по порядку, а не «первое или последнее».** На живом `bnsi` (22.11.2026) зеркал
   три, и в прогоне первый хост не отвечал, второй отвечал, третий не отвечал — правило Android
   «взять последнее при отказе первого» давало нерабочую ссылку. Теперь возвращается первое ответившее
   зеркало, при полном отказе — первое (основное) зеркало сайта. См. `AllohaMirrorPicker`.

## 6. Живая проверка (22.11.2026, `E:\neuiroslop\yummypc`)

URL из `pc/samples/balancer-samples.json` (yani API, тайтл 21747, сезон 2, серия 1, озвучка AnimeVost):
`https://alloha.yani.tv/?token_movie=bfbe2595353b6e21c502eeb82ffae3&translation=213&season=2&episode=1&token=8b5512267a2a52e9de06d67d342e0c&hidden=translation,season,episode`.
Токены на момент прогона ещё живые (страница отдавала `200`).

### Прогон 1 — WS и `edge_hash` (CLI, `:extractors` классы напрямую)

```
[alloha] GET player page
[alloha] authorization key present=true
[alloha] POST bnsi/movies/1747941
[alloha] mirror is not reachable, using the next one host=72-66-2a-r506.userplay.live
[alloha] mirrors=[72-66-2a-r506.userplay.live, 324-63c-506gv.play-revers.live, a2-86-20-r100.reserve-stream.com] picked=a2-86-20-r100.reserve-stream.com
[alloha] bnsi audioTracks=[(Undetermined) 1] subtitles=0
[alloha] view report /events http=200
[alloha] ws open at 0s
[alloha] view report /stat http=200
[alloha] ws config_update at 0s pos=0s hash=5528878c
[alloha] ws first edge_hash=true

stream: https://a2-86-20-r100.reserve-stream.com/1/l_Rvs2…/master.m3u8
header: origin: https://alloha.yani.tv
header: referer: https://alloha.yani.tv/
header: user-agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) … Chrome/149.0.0.0 Safari/537.36
header: accept-language: ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7
header: sec-ch-ua: "Chromium";v="149", "Google Chrome";v="149", "Not_A Brand";v="24"
header: sec-ch-ua-platform: "Windows"
header: authorizations: Bearer pXzvbyDGLYyB6VkwsWZD…
header: accepts-controls: 5528878c1baf23bcf49dc86e5db3c270
header: x-requested-with: su.afk.yummy.pc
qualities:
  360p -> https://a2-86-20-r100.reserve-stream.com/1/…
  480p -> …
  720p -> https://a2-86-20-r100.reserve-stream.com/1/l_Rvs2…/master.m3u8
```

`authorization key present=true` — ключ `Authorizations` (380 символов) вынут из живого
`/build/app.03ac6d63.js` тому же регэкспу, что в Android-версии.

### Прогоны 2–3 — mpv играет разрешённую ссылку

После правки выбора зеркал (`play-revers.live` ответил, первый хост — нет) ссылка получена и
проверена внешним плеером. Команда, которую запускает `AllohaCli --mpv=…`:
`mpv --no-config --vo=null --ao=null --frames=5 --http-header-fields="…" <url>`.

```
[mpv] Video  --vid=1 --vlang=eng (h264 [High] 1280x720 30 fps 3140 kbps) [default]
[mpv] Audio  --aid=1 --alang=eng (aac [LC] 2ch 48000 Hz 279 kbps) [default]
[mpv] AO: [null] 48000Hz stereo 2ch floatp
[mpv] VO: [null] 1280x720 => 1280x960 yuv420p
[mpv] AV: 00:00:00 / 00:20:23 (0%) A-V: -0.000 Cache: 11s/4MB
[mpv] Exiting... (End of file)
mpv: exit=0
```

Ссылка (прогон 2): `https://324-63c-506gv.play-revers.live/1/…/master.m3u8`; в прогоне 3 — тот же хост,
результат тот же (`mpv: exit=0`). Независимая проверка `curl` по манифесту прогона 2 (заголовки из
`ResolvedStream.headers`, как их получил бы плеер):

```
HTTP/1.1 200 OK
Content-Type: application/vnd.apple.mpegurl
Access-Control-Allow-Headers: User-Agent,X-Requested-With,If-Modified-Since,Cache-Control,Content-Type,Range,Accepts-Controls,Authorizations

#EXTM3U
#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=3039008,RESOLUTION=1280x720,FRAME-RATE=30.000,CODECS="avc1.64001f,mp4a.40.2"
index-f1-v1-a1.m3u8
```

### Офлайн- и живой тесты

- `:extractors:test --tests "*alloha*"` — 53 теста (52 офлайн + живой): все офлайн-тесты зелёные, живой
  тест `SKIPPED`.
- `$env:ALLOHA_LIVE='1'; :extractors:test --tests "*AllohaLiveTest*" --no-daemon` — тест
  `live iframe resolves to a playable hls playlist PASSED` (resolve + `PlaylistVerifier`: HTTP 200,
  `#EXTM3U`).

### Наблюдения прогонов (честно)

- **Живой `edge_hash` пришёл в первом прогоне и не пришёл в двух следующих**: WS не открылся и не упал
  за 4 с (`ws first edge_hash=false`), хотя `bnsi` отдавал `pnr`/`pnk`. Поток при этом всё равно
  получался и играл в mpv. Похоже на ограничение WS-подключений с одного IP/после нескольких сессий
  (в Android-документации — «второй WS на sid получает 4005», здесь sid новые). При недоступном сокете
  экстрактор сознательно отдаёт ссылки без `accepts-controls` — как Android-версия на старте.
- **CDN блокирует TLS-отпечаток не-браузера на части хостов.** `a2-86-20-r100.reserve-stream.com` и
  `72-66-2a-r506.userplay.live` на этой машине сбрасывали TLS и для JVM, и для `curl`
  (`Recv failure: Connection was reset`), а `324-63c-506gv.play-revers.live` отвечал. Поэтому проба
  зеркал из JVM может врать в сторону «мёртвое»; выбор «первого ответившего» это учитывает.
- CDN-ссылки сессии короткоживущие; после закрытия сессии сегменты могут начать отдавать `403` — для
  воспроизведения ссылку нужно получать прямо перед стартом и не кэшировать надолго.
- **Одновременные Gradle-сборки нескольких агентов** на одном дереве иногда роняют `:extractors:test`
  с `java.nio.file.NoSuchFileException: …in-progress-results-generic.bin` (кто-то чистит
  `build/test-results`), при этом сами тесты проходят. Это гонка сборочных каталогов, не код
  экстрактора; повторный прогон проходит.

## 7. Запуск

```powershell
$env:JAVA_HOME='E:\neuiroslop\yummypc\tools\jdk\jdk-21.0.13.1+2'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
cd E:\neuiroslop\yummypc\pc

# офлайн-тесты
.\gradlew.bat --console=plain :extractors:test --tests "*alloha*"

# живой прогон (ссылка в тесте; --no-daemon — чтобы worker унаследовал переменную)
$env:ALLOHA_LIVE='1'
.\gradlew.bat --no-daemon --console=plain :extractors:test --tests "*AllohaLiveTest*"
```

CLI (`AllohaCli.kt`, `main`): требует переключения `application.mainClass` модуля на
`su.afk.yummy.pc.extractors.alloha.AllohaCliKt` (общий `build.gradle.kts` — зона ведущего агента) либо
отдельного `JavaExec`-таска:

```powershell
.\gradlew.bat --console=plain :extractors:run --args="<iframe-url> --mpv=E:\neuiroslop\yummypc\tools\mpv\mpv.exe"
.\gradlew.bat --console=plain :extractors:run --args="<iframe-url> --verify"
```

Коды возврата CLI: `0` — успех; `1` — экстрактор отказал; `2` — неверные аргументы; `3` — ссылка
получена, но плейлист не HLS / mpv не открыл поток.

Если токены в `balancer-samples.json` протухли, свежие можно взять так:
`GET https://api.yani.tv/anime/21747/videos` с заголовками `X-Application: ze645twqfeql6l1u`,
`Lang: ru`; искать запись с `data.player = "Плеер Alloha"`, поле `iframe_url`.

## 8. Структура модуля

```
pc/extractors/src/main/kotlin/su/afk/yummy/pc/extractors/alloha/
  AllohaRegistration.kt    — allohaResolver для ExtractorRegistry
  AllohaResolver.kt        — оркестратор: retry, WS, сборка ResolvedStream
  AllohaModels.kt          — модели (озвучки, субтитры, тикеты, профиль, сессия)
  AllohaExceptions.kt      — AllohaSourceUnavailableException / AllohaResolveException
  AllohaBorth.kt           — Borth: перестановка viewporti + fingerprint
  AllohaFingerprint.kt     — SHA-256 признаков, синтетические canvas/WebGL, jsNumber
  AllohaInstallProfile.kt  — профиль установки на JVM (AWT-экран, память, ядра, seed)
  AllohaCdnHeaders.kt      — заголовки CDN и client hints из UA
  AllohaPageParsing.kt     — разбор страницы (viewporti, fileList, token, userParam, app.js, key)
  AllohaResponseParsing.kt — разбор bnsi (озвучки, качества, зеркала, субтитры), текст 404-заглушки
  AllohaMirrorPicker.kt    — выбор рабочего зеркала с кэшем по хосту
  AllohaHttpSessionOpener.kt — один HTTP-цикл открытия сессии
  AllohaPlaybackSocket.kt  — WebSocket сессии, edge_hash, переподключения
  AllohaViewReporter.kt    — POST /events и /stat
  AllohaEdgeHash.kt        — живой edge_hash и счётчик байтов
  AllohaLiveSession.kt     — живая сессия, TTL, keeper на процесс
  AllohaCli.kt             — CLI (resolve, --verify, --mpv)
pc/extractors/src/test/kotlin/su/afk/yummy/pc/extractors/alloha/
  AllohaBorthTest, AllohaFingerprintTest, AllohaResponseParsingTest, AllohaPageParsingTest,
  AllohaErrorPageTest, AllohaCdnHeadersTest, AllohaMirrorPickerTest, AllohaClientProfileTest,
  AllohaLiveTest (под ALLOHA_LIVE=1)
```

## 9. Ограничения и риски

- **Зависимость от разметки/скриптов Alloha.** Маркеры `viewporti`, `fileList`, `app.<хеш>.js`,
  ключ `Authorizations`, поля `bnsi`, `pnr`/`pnk` — всё это контракт живого сайта; при смене плеера
  сломается и Android-версия, и этот порт (это порт, а не независимый парсер).
- **CDN блокирует сессии**: `403` + `X-VD: session_blocked` после 5–8 минут без «живой сессии»,
  `client_blocked` при подозрительном отпечатке/частоте. Экстрактор держит WS и отчёты, но без
  loopback-прокси не умеет подменять `edge_hash` у уже запущенного mpv (см. §5).
- **TLS-отпечаток JVM не равен браузерному** — отдельные CDN-хосты могут рвать соединение до HTTP
  (проверено: два из трёх зеркал в прогоне). mpv использует собственный TLS, поэтому игре это не
  мешает; но проба зеркал и `--verify` из JVM могут врать. Для проверки ссылки надёжнее mpv/`curl`.
- **Позиция и байты для WS/отчётов приблизительные** (настенные часы, `bytesLoadedTotal=0`) —
  см. §5; это может сократить срок жизни сессии на длинных сериях по сравнению с Android.
- **Одна озвучка в результате**: смена озвучки — повторный resolve (плоский контракт `ResolvedStream`).
- **Ссылки короткоживущие**: не кэшировать надолго, получать перед воспроизведением.
- **Лицензия.** Код — производная CC BY-NC 4.0 (YummyTV): коммерческое использование запрещено,
  атрибуция обязательна; подробности — `pc/LICENSE`, `pc/NOTICE`, `pc/README.md`.

## 10. Интеграция в реестр (для ведущего агента)

```kotlin
// pc/extractors/src/main/kotlin/su/afk/yummy/pc/extractors/ExtractorRegistry.kt
import su.afk.yummy.pc.extractors.alloha.allohaResolver

private val resolvers: List<StreamResolver> = listOf(
    KodikResolver(),
    allohaResolver,
)
```

Когда внешний плеер закрылся, вызвать `AllohaResolver.releaseActiveSession()` — иначе WS и `/events`
живут до TTL (45 минут) и на выходе JVM. Контракт `StreamResolver` этого хука не имеет, его добавляет
слой, запускающий mpv.
