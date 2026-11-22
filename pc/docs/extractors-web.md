# Экстракторы web-балансеров для ПК: VK, CVH, Rutube, Sibnet, Aksor, Zedfilm

Порт шести экстракторов из Android-клиента YummyTV на Kotlin/JVM для `:extractors` desktop-клиента
YummyPC. Каждый экстрактор получает iframe-ссылку балансера и возвращает прямой поток для mpv.

**Based on YummyTV — https://github.com/Helandy/YummyTV (CC BY-NC 4.0).** Логика перенесена из
модулей source (`source/vk/data`, `source/cvh/data`, `source/rutube/data`, `source/sibnet/data`,
`source/aksor/data`, `source/zedfilm/data`, файлы `*Extractor.kt`/`*Utils.kt`); лицензия проекта
и NOTICE — в корне `pc/`, коммерческое использование запрещено.

Дата проверки: 22.11.2026. Живые прогоны — реальные iframe-URL из yani API и настоящие CDN.

## 0. Коротко

| Балансер | Пакет | Класс | Живой прогон | Что отдаёт |
|---|---|---|---|---|
| VK | `...pc.extractors.vk` | `VkResolver` | **да** (iframe yani) | mp4-лестница 144–720p + HLS `auto` |
| CVH (CdnVideoHub) | `...pc.extractors.cvh` | `CvhResolver` | **да** (iframe yani) | mp4-лестница 240–2160p на okcdn |
| Rutube | `...pc.extractors.rutube` | `RutubeResolver` | **да** (iframe yani, аниме 27125; встречается редко) | HLS-мастер + варианты по `RESOLUTION` |
| Sibnet | `...pc.extractors.sibnet` | `SibnetResolver` | **нет**: yani не отдаёт, сайт отвечает 403 этой сети | один mp4 |
| Aksor | `...pc.extractors.aksor` | `AksorResolver` | **да** (iframe yani) | DASH `.mpd` (или mp4) 360–4K |
| Zedfilm | `...pc.extractors.zedfilm` | `ZedfilmResolver` | **да** (iframe yani) | DASH `.mpd`/mp4 из `video_Init` и разметки |

Контракт — как у Kodik-экстрактора (`docs/kodik.md`): `supports(iframeUrl)` + `resolve(iframeUrl):
Result<ResolvedStream>`, ошибки не бросаются наружу. Каждый пакет самодостаточен: свой HTTP-клиент,
свои исключения, чистая логика разбора вынесена в `<Name>Parsing` и покрыта офлайн-тестами.
Регистрационные файлы (`<Name>Registration.kt`) содержат по одной строке
`internal val <name>Resolver: StreamResolver = <Name>Resolver()` — ведущий агент добавляет их в
`ExtractorRegistry` без правок в пакетах.

## 1. Контракт и общие принципы

```kotlin
class VkResolver(
    userAgent: String = DEFAULT_USER_AGENT,   // Windows Chrome 149, как у Kodik
    autoQualityLabel: String = "auto",        // как называть «auto»
    onStage: (String) -> Unit = {},           // прогресс для логов/CLI
) : StreamResolver {
    override fun supports(iframeUrl: String): Boolean
    override suspend fun resolve(iframeUrl: String): Result<ResolvedStream>
}
```

`ResolvedStream(url, headers, qualities)`: `qualities` — по возрастанию, последняя пара равна `url`.
Если у балансера одно качество (Sibnet), в списке одна пара `"auto" to url`.

Общие правила порта:

- **UA по умолчанию** — `DEFAULT_USER_AGENT` каждого класса (Windows Chrome 149, как
  `KodikResolver.DEFAULT_USER_AGENT`); в приложении лучше передавать UA из настроек, чтобы он у
  запросов к балансеру и у плеера совпадал.
- **Заголовки плеера** — только те, что нужны CDN: UA, иногда Referer/Origin. Лишние заголовки
  (например `Accept-Language`/`Accept-Encoding`) в `ResolvedStream.headers` не попадают.
- **HTTP-клиенты отдельные в каждом пакете** (`<Name>Http.kt` на OkHttp): у VK и Zedfilm нужны
  байты тела (windows-1251), остальным — текст; так пакеты не зависят от `KodikHttpClient` и не
  ломают зону записи общих файлов.
- **Исключения типизированы** по смыслу, чтобы UI отличал «контента нет» от «сломался разбор»:
  - VK: `VkVideoUnavailableException(vkMessage, videoNotFound)`, `VkResolveException`;
  - CVH: `CvhRegionBlockedException(tags)`, `CvhUnavailableException`, `CvhResolveException`;
  - Rutube: `RutubeResolveException`;
  - Sibnet: `SibnetAccessForbiddenException`, `SibnetResolveException`;
  - Aksor: `AksorResolveException` (сетевой сбой — по префиксу типа исключения в `message`);
  - Zedfilm: `ZedfilmResolveException`.

## 2. VK (`vk/`)

Вход (yani): `https://ru.yummyani.me/iframeVK.html?id=<owner>_<video>`.
Выход: mp4-лестница на CDN VK (`mp4_144…mp4_720`) + HLS-мастер под меткой `auto`; последняя пара —
лучший mp4.

### 2.1. Алгоритм

1. Нормализация iframe-URL (`//` → `https://`, `\/`/`\uXXXX` раскрываются).
2. GET обёртки `iframeVK.html`; из `id=<owner>_<video>` строится
   `https://vk.com/video_ext.php?oid=<owner>&id=<video>&hd=1` (либо ссылка `video_ext.php` берётся из
   HTML обёртки).
3. GET `video_ext.php`. **Классический путь (1:1 с Android-версией)**: если в HTML есть маркер
   `id="video_ext_msg"` — видео недоступно (`VkVideoUnavailableException`, текст читается из
   windows-1251-байтов), если `{"error_code":204}` в `apiPrefetchCache` — доступ закрыт
   (`videoNotFound = true`), иначе ссылки на поток вычитываются из JSON/HTML (`mp4_720`, `hls`,
   блок `files`, `data-video-src`, `<source>`), а «урезанная» старая страница перезапрашивается до
   5 раз.
4. **Актуальный VK (2026) отдаёт SPA**: в HTML только анонимный `window.vk.webToken.access_token` и
   предзагрузка `video.getHashes` (`csrf` + подпись `video`). Экстрактор определяет такую страницу
   (`isSpaPage`) и добирает player config запросом
   `GET https://api.vk.com/method/video.getEmbed?owner_id=…&video_id=…&hash=…&v=5.289&access_token=…`.
   Из `response.video.files` берутся `mp4_144…mp4_2160` и `hls`/`hls_fmp4` → `auto`; `dash_sep`
   пропускается (отдельный поток без манифеста).
5. Лестница приводится к стандартному порядку качеств, последняя = лучшая.

### 2.2. Заголовки

| Запрос | Заголовки |
|---|---|
| GET обёртки / `video_ext.php` | `Referer: <iframe-url>`, `User-Agent`, `Accept: */*` |
| GET `api.vk.com/method/video.getEmbed` | `Referer: https://vk.com/`, `User-Agent`, `Accept: */*` |
| плеер (mp4/HLS с CDN VK) | `Referer: <video_ext-URL>`, `Origin: https://vk.com`, `User-Agent` |

### 2.3. Живая проверка (22.11.2026)

```
iframe: https://ru.yummyani.me/iframeVK.html?id=-234920203_456239687
[vk] GET iframe page
[vk] GET video_ext page (attempt 1)
[vk] GET video.getEmbed api
[vk] build quality ladder
stream: https://vkvd450.okcdn.ru/?srcIp=…&expires=1795798613737&…&type=3&sig=MWpS9CbPttQ&…&id=18074514623153…
headers: Referer: https://vk.com/video_ext.php?oid=-234920203&id=456239687&hd=1
         Origin: https://vk.com
         User-Agent: Mozilla/5.0 … Chrome/149.0.0.0 Safari/537.36
qualities: auto -> https://vkvd450.okcdn.ru/video.m3u8?...  (HLS), 144p/240p/360p/480p/720p -> mp4 …
verify: HTTP 200, тело mp4 (ftypisom…moov)
mpv: exit 0, h264 1280x720 30 fps, видео играет
```

Ограничения: ссылки подписаны по IP (`srcIp`) и времени (`expires`), живут часы — не кэшировать;
`video.getEmbed` требует свежий анонимный токен со страницы, поэтому страница читается заново на
каждый resolve; приватные/удалённые видео отдают `VkVideoUnavailableException`.

## 3. CVH (`cvh/`)

Вход (yani): `//ru.yummyani.me/iframeCVH.html?dubbing_code=AnimeVost&anime_id=61967&episode=1&dubbing=…`.
Выход: mp4-лестница `240p…2160p` на `okcdn`.

### 3.1. Алгоритм

1. Query iframe-URL: `anime_id` (обязателен), `episode` (по умолчанию 1), `dubbing_code`, `dubbing`.
2. GET playlist API
   `https://plapi.cdnvideohub.com/api/v1/player/sv/playlist?pub=745&id=<anime_id>&aggr=mali`
   (один повтор через 700 мс на любую ошибку).
3. `tags` + пустой список = регион-блок (`CvhRegionBlockedException`, тег 5); пустой `items` или нет
   записи нужной серии/озвучки — `CvhUnavailableException`.
4. Выбор записи: сериал фильтруется по `episode` (фильм — нет, у него поля нет); озвучка ищется по
   `dubbing_code`, затем по `"<voiceType> <voiceStudio>"` из `dubbing`, затем по подписи без
   «Озвучка », иначе первая запись серии.
5. GET video API `https://plapi.cdnvideohub.com/api/v1/player/sv/video/<vkId>`; из `sources`
   собирается mp4-лестница (`mpegLowestUrl`…`mpegFullHdUrl`, `mpegQhdUrl|mpeg2kUrl` → 1440p,
   `mpeg4kUrl` → 2160p). HLS намеренно не берётся: манифесты CdnVideoHub содержат сегменты с
   сырыми IP-адресами CDN, не переписанными на `failoverHost`.
6. Подмена хоста okcdn на `failoverHost`: сырые IPv4 — всегда, именованный узел — только при
   `forceFailover=true` (конструктор `CvhResolver(forceFailover = true)` — перерезолв после сбоя
   воспроизведения).

### 3.2. Заголовки

| Запрос | Заголовки |
|---|---|
| GET playlist/video API | `Referer: https://ru.yummyani.me/`, `User-Agent`, `Accept: application/json` |
| плеер (mp4 на okcdn) | **только `User-Agent`** |

Важно: okcdn подписанные ссылки CVH не любят лишних заголовков. В проверке `mpv` с добавленными
вручную `Referer`/`Origin` получал от CDN `HTTP 400`; с одним UA (как отдаёт экстрактор) — играет.
Поэтому `ResolvedStream.headers` у CVH — только UA, лишнего не добавлять.

### 3.3. Живая проверка (22.11.2026)

```
iframe: https://ru.yummyani.me/iframeCVH.html?dubbing_code=AnimeVost&anime_id=61967&episode=1&dubbing=…
[cvh] GET playlist (anime_id=61967) -> items=46, выбран AnimeVost episode=1 (vkId=16188204604080)
[cvh] GET video/16188204604080
stream: https://vd237.okcdn.ru/?expires=…&srcIp=…&type=5&sig=XZ_DcuII0Ks&…&id=17928681818800…
headers: User-Agent: Mozilla/5.0 … Chrome/149.0.0.0 Safari/537.36
qualities: 240p…1080p -> mp4 (последняя — 1080p)
verify: HTTP 200, video/mp4
mpv: exit 0, h264 1920x1080 23.98 fps, видео играет
```

Ограничения: ссылки подписаны и короткоживущие; при недоступности okcdn-узла — `forceFailover` или
повторный resolve; качеств `2160p` (4K) в проверенном ответе не было — лестница строится по факту
ключей.

## 4. Rutube (`rutube/`)

Вход: `https://rutube.ru/play/embed/<32-hex-id>` (любая ссылка с `rutube.ru`).
Выход: мастер-плейлист HLS как `auto` + варианты, разобранные из `#EXT-X-STREAM-INF` по `RESOLUTION`.

### 4.1. Алгоритм

1. Id видео — сегмент пути из 32 hex-символов (`/play/embed/<id>`, `/video/<id>/`), иначе первое
   совпадение в ссылке.
2. GET `https://rutube.ru/api/play/options/<id>/?no_404=true`; ссылка на мастер —
   `video_balancer.m3u8`, если её нет/`null` — `video_balancer.default`; иначе отказ.
3. GET мастер-плейлист: каждая пара `#EXT-X-STREAM-INF`/URI даёт качество по `RESOLUTION`;
   незнакомые высоты (например `854x800`) отбрасываются, относительные URI разрешаются от хоста
   мастера. Недоступный мастер — не отказ: остаётся один `auto` (как в Android-версии).
4. Лестница по возрастанию: `auto` (мастер) → 360p → 720p → …

### 4.2. Заголовки

| Запрос | Заголовки |
|---|---|
| options API / мастер | `Referer: <iframe-url>`, `Origin: https://rutube.ru`, `User-Agent`, `Accept: */*` |
| плеер (HLS) | `Referer: <iframe-url>`, `Origin: https://rutube.ru`, `User-Agent` |

### 4.3. Статус живого прогона

Rutube-балансер в yani **встречается редко**: в топах (571 тайтл) его нет, и он нашёлся при более
широком скане (ещё 1273 тайтла по просмотрам, offset 300–1500) — у тайтла «Эф — история мелодий. Пролог»
(`GET /anime/27125/videos`, единственная серия):
`//rutube.ru/play/embed/2f22bf4308e52cf2368c3ef7a28f518c`. `RutubeLiveTest` берёт именно эту
yani-ссылку.

```
iframe: https://rutube.ru/play/embed/2f22bf4308e52cf2368c3ef7a28f518c
[rutube] GET options for 2f22bf4308e52cf2368c3ef7a28f518c
[rutube] GET master playlist
stream: https://bl.rutube.ru/route/2f22bf4308e52cf2368c3ef7a28f518c.m3u8?guids=…&sign=…&expire=…&scheme=https
headers: Referer: https://rutube.ru/play/embed/2f22bf4308e52cf2368c3ef7a28f518c
         Origin: https://rutube.ru
         User-Agent: Mozilla/5.0 … Chrome/149.0.0.0 Safari/537.36
qualities: auto -> мастер bl.rutube.ru (у этой раздачи один вариант 480x272, поэтому лестница из auto)
verify: HTTP 200, first line: "#EXTM3U"
mpv: exit 0, h264 [Constrained Baseline] 480x272 23 fps, HLS играет
```

Для сравнения, на публичном ролике Rutube с тремя вариантами (`RESOLUTION` 640x360/1280x720/1920x1080)
экстрактор строит полную лестницу 360p/720p/1080p и отдаёт 1080p-вариант (`#EXTM3U`, mpv играет
h264 1920x1080) — так проверен разбор мастер-плейлиста.

## 5. Sibnet (`sibnet/`)

Вход: `//video.sibnet.ru/shell.php?videoid=<id>` (любая ссылка с `sibnet.ru`).
Выход: один mp4, `qualities = [auto -> url]`.

### 5.1. Алгоритм

1. GET страницы плеера (`Referer: https://yani.tv/`, `Accept: text/html…`).
2. HTTP 403 — `SibnetAccessForbiddenException`; другой не-2xx — `SibnetResolveException("HTTP N")`.
3. Ссылка: первая из `player.src([{src: "…"` или `<source src="…">`; относительные разрешаются от
   URL плеера (`/v/…` → `https://video.sibnet.ru/v/…`).
4. Плееру — `Referer: <iframe-url>`, `Origin: https://video.sibnet.ru`, UA.

### 5.2. Статус живого прогона

**Живого yani-URL нет**: скан 1844 тайтлов (571 топ + 1273 по просмотрам) не встретил ни одного
Sibnet-балансера. Вдобавок сам Sibnet закрыт для этой сети: GET
`https://video.sibnet.ru/shell.php?videoid=6181468` (URL из фикстуры Android-теста) отвечает
`403 Forbidden: Request forbidden by administrative rules`. Это именно тот случай, который экстрактор
различает как `SibnetAccessForbiddenException`; чужие 403 не маскируются под «потока нет».

Порт сделан по исходнику и офлайн-фикстурам (`SibnetParsingTest`): 403, `player.src`, `<source>`,
относительные/protocol-relative/экранированные URL. Живой тест не включён: проверять нечего, кроме
403 с этой сети, — при появлении раздачи Sibnet в yani тест добавляется по образцу `VkLiveTest`.

## 6. Aksor (`aksor/`)

Вход (yani): `https://player.aksor.tv/video/<hash>`.
Выход: DASH `.mpd` (в проверке — 1080p) или mp4-лестница `q360…q4k`.

### 6.1. Алгоритм

1. Hash — сегмент после `video` в пути (иначе последний сегмент).
2. GET `https://player.aksor.tv/api/video/<hash>`; из `qualities` берутся `q360,q480,q720,q1080,
   q2k,q4k` (подписи `360p…1080p`, `2K`, `4K`), пустые/`null` пропускаются, пробелы в путях CDN
   кодируются (`%20`, иначе часть узлов отдаёт 404).
3. Если API не дал качеств (404/HTML) — запасной разбор страницы: мета `video_url` (кроме шаблонов
   `{{ … }}`), затем `<script src>` → поиск пути `…/api` в скрипте → повторный запрос видео.
4. Сетевой сбой возвращает `AksorResolveException` с префиксом типа исключения
   (`SocketTimeoutException: …`), как в Android-версии; «потока нет» — без префикса.

### 6.2. Заголовки

| Запрос | Заголовки |
|---|---|
| player API | `Referer: <player-url>`, `User-Agent`, `Accept: application/json` |
| страница плеера/скрипты | `Referer: <player-url>` (страница: `https://yani.tv/`), `User-Agent`, `Accept` |
| плеер (DASH/mp4) | `Referer: <player-url>`, `User-Agent` |

### 6.3. Живая проверка (22.11.2026)

```
iframe: https://player.aksor.tv/video/8d4b9ff83b37b94e542c49fa3a8d2286
[aksor] GET player API
stream: https://old.tut-video.net/static13/video/a119/SHIZA%20Project/01/1080.mpd
headers: Referer: https://player.aksor.tv/video/8d4b9ff83b37b94e542c49fa3a8d2286
         User-Agent: Mozilla/5.0 … Chrome/149.0.0.0 Safari/537.36
qualities: 1080p -> …/1080.mpd
verify: HTTP 200, application/octet-stream, тело MPD (`<MPD xmlns="urn:mpeg:dash…`)
mpv: exit 0, h264 [Main] 1404x1080, DASH играет
```

Ограничения: у этого ролика в API было только `q1080` (остальные `null`) — лестница из одной пары;
пробелы в именах озвучек (`SHIZA Project`) кодируются в `%20`; `.mpd` mpv открывает как DASH.

## 7. Zedfilm (`zedfilm/`)

Вход (yani): `//zedfilm.ru/<id>` (также `hlamer.ru`).
Выход: DASH `.mpd` (поле `url`) или mp4 (`url2`) из `video_Init`, либо ссылки из разметки/скриптов.

### 7.1. Алгоритм

1. GET iframe-страницы; тело декодируется как **windows-1251**.
2. `video_Init('<base64 JSON>')` → `url` (основной DASH), `url2` (mp4-фолбэк); первый валидный
   побеждает.
3. Дополнительно сканируются `https?://…(.mpd|.m3u8|.mp4)`, `//…`, `file/src/source/url/hls/dash =
   "…"`, `<source src>`. Рекламные URL отбрасываются (хосты `doubleclick`/`yandex`, целые слова
   `ads`/`ima`), качество берётся из текста ссылки без хоста (1440p не читается как 144p).
4. Лестница по стандартному порядку; WebView-фолбэка нет — при смене контракта страницы
   экстрактор честно отказывает `ZedfilmResolveException`.

### 7.2. Заголовки

| Запрос | Заголовки |
|---|---|
| GET iframe-страницы | `Referer: https://yani.tv/`, `Origin: https://hlamer.ru`, `User-Agent`, `Accept: text/html…`, `Accept-Language: ru-RU,ru;q=0.9,en;q=0.8` |
| плеер (mp4/DASH) | `Referer: <iframe-url>`, `Origin: https://hlamer.ru`, `User-Agent` |

`Accept-Encoding: gzip` вручную не ставится (в Android-версии его ставили): OkHttp сам добавляет
`gzip` и декодирует ответ; ручная установка отключила бы декодирование. Для сайта важен факт
заголовка — без сжатия Zedfilm отвечает на iframe 404 «video not found».

### 7.3. Живая проверка (22.11.2026)

URL взят из yani API: `GET /anime/11932/videos` содержит балансер «Плеер Zedfilm» (26 серий).

```
iframe: https://zedfilm.ru/208134
[zedfilm] GET iframe page, HTTP 200
[zedfilm] extract stream urls
stream: https://media2.krasview.ru/video/1ccfea564944cd5/Storm_Hawks_s02e214.mp4
headers: Referer: https://zedfilm.ru/208134
         Origin: https://hlamer.ru
         User-Agent: Mozilla/5.0 … Chrome/149.0.0.0 Safari/537.36
qualities: auto -> mp4
verify: HTTP 200, video/mp4
mpv: exit 0, hevc 640x480 25 fps, видео играет
```

Ограничения: Zedfilm в yani встречается редко (26 записей у одного тайтла из 1844 просканированных);
`video_Init` может отсутствовать — тогда работают только ссылки из разметки; windows-1251 обязательна
для текста страницы.

## 8. Интеграция с реестром (делает ведущий агент)

Каждый пакет отдаёт `internal val <name>Resolver: StreamResolver`; правка `ExtractorRegistry.kt` —
импорты и семь строк в списке:

```kotlin
import su.afk.yummy.pc.extractors.aksor.aksorResolver
import su.afk.yummy.pc.extractors.cvh.cvhResolver
import su.afk.yummy.pc.extractors.rutube.rutubeResolver
import su.afk.yummy.pc.extractors.sibnet.sibnetResolver
import su.afk.yummy.pc.extractors.vk.vkResolver
import su.afk.yummy.pc.extractors.zedfilm.zedfilmResolver

private val resolvers: List<StreamResolver> = listOf(
    KodikResolver(),
    vkResolver,
    cvhResolver,
    rutubeResolver,
    sibnetResolver,
    aksorResolver,
    zedfilmResolver,
)
```

Пересечений `supports` между экстракторами нет: VK — `vk.com`/`vkvideo`/`video_ext.php`/`iframeVK`,
CVH — `iframecvh`/`cvh`, Rutube — `rutube.ru`, Sibnet — `sibnet.ru`, Aksor — `aksor.tv`,
Zedfilm — `zedfilm.ru`/`hlamer.ru`. Порядок в реестре на поведение не влияет.

## 9. Проверки

### 9.1. Офлайн-тесты (`:extractors:test`, сеть не нужна)

Парсеры/декодеры каждого пакета покрыты отдельными классами:

| Файл | Что покрыто |
|---|---|
| `vk/VkParsingTest.kt` | `iframeVK` → `video_ext`, разбор JSON/HTML-страницы, окна-1251 сообщения, `error_code 204`, лестница качеств, SPA-маркеры и `video.getEmbed` (`files`, ошибки API) |
| `cvh/CvhParsingTest.kt` | query, плейлист, выбор серии/озвучки (7 сценариев), регион-блок, лестница mp4, `failoverHost` (IPv4/именованный/refresh) |
| `rutube/RutubeParsingTest.kt` | id видео, `video_balancer` (m3u8/default/null), мастер-плейлист в лестницу, относительные URI, auto-label |
| `sibnet/SibnetParsingTest.kt` | `player.src`/`<source>`, относительные/protocol-relative/экранированные URL |
| `aksor/AksorParsingTest.kt` | hash, метки `q2k/q4k`, `%20`-кодирование, мета `video_url`, скрипты и путь API |
| `zedfilm/ZedfilmParsingTest.kt` | `video_Init` (base64+JSON), url/url2, лестница, экранирование, рекламные URL, 1440p vs 144p, качество не из хоста |

### 9.2. Живые тесты (`*LiveTest`, включаются переменной окружения)

Живые тесты помечены `assumeTrue` и в обычном прогоне **SKIPPED**, чтобы `:extractors:test` не
зависел от сети. Включение:

```powershell
$env:JAVA_HOME='E:\neuiroslop\yummypc\tools\jdk\jdk-21.0.13.1+2'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
cd E:\neuiroslop\yummypc\pc
$env:EXTRACTORS_LIVE='1'
.\gradlew.bat --no-daemon --console=plain :extractors:test --tests "*VkLiveTest" --tests "*CvhLiveTest" --tests "*RutubeLiveTest" --tests "*AksorLiveTest" --tests "*ZedfilmLiveTest"
```

`--no-daemon` здесь важен: переменная окружения должна попасть в свежий JVM тестового воркера, а
общий Gradle-демон может быть запущен без неё. Результаты и stdout тестов — в
`pc/extractors/build/test-results/test/TEST-*.xml`.

`VkLiveTest`, `CvhLiveTest`, `RutubeLiveTest`, `AksorLiveTest`, `ZedfilmLiveTest` проверяют не только
«resolve не упал», но и что ссылка открывается: `PlaylistVerifier` делает GET и требует HTTP 2xx, для
HLS — тело `#EXTM3U`. Независимо от тестов ссылки проверены `curl` и `mpv` (см. §2.3/3.3/4.3/6.3/7.3).

Итог прогона 22.11.2026: живой набор — `BUILD SUCCESSFUL`, 5 тестов PASSED (VK, CVH, Rutube, Aksor,
Zedfilm); офлайн-набор шести пакетов — 80 тестов PASSED (`VkParsingTest` 22, `CvhParsingTest` 17,
`ZedfilmParsingTest` 15, `RutubeParsingTest` 12, `AksorParsingTest` 9, `SibnetParsingTest` 5).
Без `EXTRACTORS_LIVE=1` живые тесты уходят в SKIPPED, и `:extractors:test` от сети не зависит.

### 9.3. Независимая проверка ссылок (curl + mpv)

| Балансер | curl | mpv (`--no-config --vo=null --ao=null --frames=5`) |
|---|---|---|
| VK | `200 video/mp4` | exit 0, h264 1280x720 30 fps |
| CVH | `200 video/mp4` | exit 0, h264 1920x1080 23.98 fps (только с UA, без Referer) |
| Rutube | `200 application/vnd.apple.mpegurl`, `#EXTM3U` | exit 0, h264 [Constrained Baseline] 480x272, HLS (у yani-раздачи один вариант; на публичном ролике с 3 вариантами — 1920x1080) |
| Aksor | `200 application/octet-stream`, тело MPD | exit 0, h264 1404x1080 DASH |
| Zedfilm | `200 video/mp4` | exit 0, hevc 640x480 25 fps |

Команда mpv для самопроверки (консольный launcher, иначе `mpv.exe` — GUI-подсистема и не печатает
лог):

```powershell
tools\mpv\mpv.com --no-config --vo=null --ao=null --frames=5 `
  --user-agent="<UA>" --http-header-fields="Referer: <referer>,Origin: <origin>" -- "<url>"
```

### 9.4. Как искали живые URL балансеров

Через живой yani API (заголовки `X-Application`/`Lang`, как в `:api`):

1. `GET https://api.yani.tv/anime?limit=100&offset=N&sort=rating|views|top` — первые 900 позиций,
   дедуплицировано 571 тайтл;
2. для каждого — `GET https://api.yani.tv/anime/{id}/videos`, поиск `iframe_url` по хостам
   балансеров;
3. второй проход: `offset 300–1500, sort=views` — ещё 1273 новых тайтла (итого 1844).

Результат: VK — 123 записи, CVH — 685, Zedfilm — 26, Rutube — 1 (аниме 27125), Sibnet — 0.
Kodik/Alloha/YouTube — остальное. Поэтому живые тесты есть у VK, CVH, Rutube, Aksor (URL дан в
задании) и Zedfilm; для Sibnet живого URL в yani нет, а сам сайт отдаёт 403 этой сети.

## 10. Структура модуля

```
pc/extractors/src/main/kotlin/su/afk/yummy/pc/extractors/
  vk/
    VkResolver.kt        — экстрактор (порт VkExtractor + добор player API)
    VkParsing.kt         — чистые шаги разбора (тестируются без сети)
    VkHttp.kt            — OkHttp-клиент: GET, тело байтами (windows-1251)
    VkExceptions.kt      — VkVideoUnavailableException / VkResolveException
    VkRegistration.kt    — `internal val vkResolver`
  cvh/     CvhResolver, CvhParsing, CvhHttp, CvhExceptions, CvhRegistration
  rutube/  RutubeResolver, RutubeParsing, RutubeHttp, RutubeExceptions, RutubeRegistration
  sibnet/  SibnetResolver, SibnetParsing, SibnetHttp, SibnetExceptions, SibnetRegistration
  aksor/   AksorResolver, AksorParsing, AksorHttp, AksorExceptions, AksorRegistration
  zedfilm/ ZedfilmResolver, ZedfilmParsing, ZedfilmHttp, ZedfilmExceptions, ZedfilmRegistration
pc/extractors/src/test/kotlin/.../<name>/
  <Name>ParsingTest.kt   — офлайн-фикстуры
  <Name>LiveTest.kt      — живой прогон (VK, CVH, Rutube, Aksor, Zedfilm), EXTRACTORS_LIVE=1
```

Общие файлы модуля (`KodikResolver`, `StreamResolver`, `ResolvedStream`, `ExtractorRegistry`,
`build.gradle.kts`) в этих пакетах не менялись — кроме правки реестра, которую делает ведущий агент.

## 11. Ограничения и риски

- **Короткоживущие подписанные ссылки.** VK/CVH (`okcdn`), Aksor/Zedfilm (CDN) отдают ссылки с
  `expires`/подписью и привязкой к IP: получать непосредственно перед воспроизведением, не кэшировать
  надолго; после паузы/повтора — повторный resolve.
- **VK-исходник устарел.** Android-версия парсит статическую страницу `video_ext.php`, а VK с 2026
  отдаёт SPA. Порт сохраняет старый разбор первым шагом (старые зеркала/разметка) и добавляет добор
  через `video.getEmbed` с анонимным web-token страницы. Если VK сменит SPA-контракт (имя токена,
  метод API, `files`), VK-экстрактор сломается первым — остальные пять от VK не зависят.
- **CVH: только mp4.** HLS-манифесты CdnVideoHub содержат сегменты с сырыми IP, не переписанными на
  `failoverHost`, поэтому HLS пропущен осознанно (как в Android-версии). Качество 4K/1440p бывает не
  у всех раздач.
- **CVH: заголовки плеера — только UA.** Добавление Referer/Origin к okcdn-ссылке может дать 400
  (проверено на mpv).
- **Rutube в yani встречается редко** (единственная известная раздача — аниме 27125, найдена широким
  сканом): порт и живой тест есть, но в обычном каталоге балансер почти не попадается.
- **Sibnet в yani не встречается (скан 1844 тайтлов), а сайт отвечает 403 этой сети.** Порт покрыт
  офлайн-тестами; живой прогон невозможен — экстрактор корректно вернёт
  `SibnetAccessForbiddenException`.
- **Zedfilm редко встречается в yani** и зависит от `video_Init`/windows-1251; при смене разметки —
  `ZedfilmResolveException` (WebView-фолбэка нет, как и в Android-версии).
- **Гео/CDN и антибот.** Часть CDN может отдавать 403/404 вне разрешённого региона; VK/CVH могут
  потребовать cookie/капчу — экстрактор вернёт типизированный отказ, обхода челленджей нет.
- **UA из настроек.** Для продакшена передавайте в конструкторы UA приложения, а не `DEFAULT_USER_AGENT`:
  mpv и запросы к балансерам должны слать одинаковый отпечаток.
- **Лицензия.** Код — производная CC BY-NC 4.0 (YummyTV): коммерческое использование запрещено,
  атрибуция обязательна (`pc/LICENSE`, `pc/NOTICE`).
