# Экстрактор Kodik для ПК (модуль `:extractors`)

Порт `KodikExtractor` из Android-клиента YummyTV на Kotlin/JVM: получает по iframe-ссылке балансера
Kodik прямую HLS-ссылку для внешнего плеера (mpv). Проверено на живом URL 22.11.2026 — ссылка
получена, плейлист отдаёт `#EXTM3U` (детали и вывод в разделе 5).

**Based on YummyTV — https://github.com/Helandy/YummyTV (CC BY-NC 4.0).** Логика перенесена из
`src/YummyTV-main/source/kodik/data/src/main/kotlin/su/afk/yummy/tv/source/kodik/data/extractor/KodikExtractor.kt`;
лицензия проекта и NOTICE — в корне `pc/`, коммерческое использование запрещено.

## 0. Коротко

- **Модуль:** `pc/extractors`, Gradle-проект `:extractors`, Kotlin JVM, toolchain 21.
- **Пакет:** `su.afk.yummy.pc.extractors`.
- **Вход:** iframe-URL вида
  `https://kodikplayer.com/season/<id>/<hash>/720p?translations=false&only_episode=true&only_season=true&episode=1`.
- **Выход:** `ResolvedStream(url, headers, qualities)`, где `url` — подписанный CDN-URL HLS-манифеста
  (`https://…/<q>.mp4:hls:manifest.m3u8`), `headers` — заголовки для плеера, `qualities` — качества по
  возрастанию.
- **CLI:** `:extractors:run --args="<iframe-url> [--verify]"`; `--verify` сам делает GET и проверяет,
  что ответ начинается с `#EXTM3U`.
- **Зависимости:** OkHttp 5.5.1 (из `gradle/libs.versions.toml`), kotlinx-coroutines, `org.json:json:20250517`
  (замена `org.json.JSONObject` из Android; в каталог не добавлялась — версия локальна для модуля).
- **Тесты:** `:extractors:test` — офлайн-фикстуры, снятые с настоящего ответа `/ftor`.

## 1. Контракт API

```kotlin
package su.afk.yummy.pc.extractors

/** Прямой поток для mpv. qualities: "480p" -> URL, по возрастанию; последняя пара = url. */
data class ResolvedStream(
    val url: String,
    val headers: Map<String, String>,
    val qualities: List<Pair<String, String>>,
)

/** iframe-URL -> прямой поток. Ходит в сеть, выполняется на Dispatchers.IO. */
interface StreamResolver {
    suspend fun resolve(iframeUrl: String): Result<ResolvedStream>
}

class KodikResolver(
    val userAgent: String = DEFAULT_USER_AGENT,   // default: Windows Chrome 149
    val onStage: (String) -> Unit = {},           // необязательный прогресс для логов/CLI
) : StreamResolver
```

- Ошибки наружу не бросаются: `resolve` возвращает `Result`.
- `KodikBlockedException(statusCode, message)` — Kodik ответил страницей-ошибкой/403/«Видео не найдено»;
  это аналог `PlayerStreamResolveResult.KodikBlocked` в Android-версии, UI может показать «источник
  заблокирован», а не общую ошибку.
- `KodikResolveException(message)` — технический отказ (нет `urlParams`/`vInfo`/скрипта, ответ без
  ссылок и т.п.).
- `PlaylistVerifier().check(url, headers)` — отдельная проверка результата (`PlaylistProbe`: HTTP-код,
  префикс тела, `isHlsPlaylist`).
- `userAgent` лучше передавать из настроек приложения (`BrowserUserAgentProfile`), тогда UA у запросов
  к балансеру и у плеера совпадает.

Подключение к UI-модулю (когда будет интеграция; сейчас GUI Kodik не вызывает):

```kotlin
// pc/app/build.gradle.kts
implementation(project(":extractors"))

// экран: mpv уже умеет принимать заголовки
scope.launch {
    KodikResolver().resolve(video.iframeUrl)
        .onSuccess { stream -> MpvLauncher.play(stream.url, stream.headers, title = title) }
        .onFailure { error -> /* KodikBlockedException -> «источник недоступен» */ }
}
```

## 2. Алгоритм (1:1 с Android-версией)

1. Нормализация iframe-URL: `//host/...` → `https://host/...`, ссылка без схемы → `https://...`.
2. `GET` iframe-страницы с браузерными заголовками и cookie-jar. Из HTML вынимаются:
   - `urlParams = '{"d","d_sign","pd","pd_sign","ref","ref_sign",…}'` — подписанные параметры;
   - `vInfo.type` / `vInfo.hash` / `vInfo.id` (на некоторых страницах — `videoInfo.*`);
   - `src="/assets/js/app.player_single.<hash>.js"` — скрипт плеера.
3. `GET` скрипта плеера. В нём ищутся `atob("…")`; из декодированных base64 значений берётся короткий
   путь, начинающийся с `/` (в проверке — `/ftor`). Путь кэшируется по URL скрипта (скрипт
   версионирован в URL); при ошибке кэш сбрасывается. Если `atob`-путь не найден — запасной `/ftor`.
4. `POST` на `<origin из скрипта><путь>`, `application/x-www-form-urlencoded`, поля:
   `d`, `d_sign`, `pd`, `pd_sign`, `ref` (уже URL-encoded, подставляется как есть), `ref_sign`,
   `bad_user=true`, `cdn_is_working=true`, `type`, `hash`, `id`, `info=%7B%7D`.
   На HTTP 5xx — один повтор через 700 мс.
5. Из JSON-ответа `links` качества `240…1080`; `src` без `//` декодируется ROT18+base64 (по буквам
   `+18` с переносом через `Z`/`z`), затем протокол-относительная ссылка получает `https:`.
6. Каждое качество проверяется HEAD-запросом: качество в URL (`/(\d+)\.mp4:hls:manifest\.m3u8`) может
   не совпадать с ключом. Если ожидаемое выше реального, URL «чинится» подстановкой качества и
   проверяется HEAD; если манифеста нет — качество переименовывается в реальное.
7. Возвращается максимальное реальное качество; в `headers` — `User-Agent` (mpv получает его через
   `--user-agent`, остальные заголовки не требуются).

## 3. HTTP-заголовки

| Запрос | Заголовки |
|---|---|
| `GET` iframe-страница | `Referer: https://yani.tv/`, `User-Agent: <браузерный>`, `Accept-Language: ru-RU,ru;q=0.9,en;q=0.8` |
| `GET` `app.player_single*.js` | `Referer: <iframe-url>`, `User-Agent`, `Accept-Language` |
| `POST` `/ftor` | `Referer: <iframe-url>`, `User-Agent`, `Content-Type: application/x-www-form-urlencoded`, `X-Requested-With: XMLHttpRequest`, `Cookie` (автоматически из cookie-jar) |
| `HEAD` манифестов / `GET` плейлиста | `User-Agent` (тот же, что в `ResolvedStream.headers`) |

Cookie-jar обязателен по контракту Android-версии: DDoS-Guard Kodik на iframe-странице ставит
`_mail_id`, `__ddg1_`, `__ddg8_`, `__ddg9_`, `__ddg10_`, и они должны уходить в запросы скрипта и
`/ftor`. В текущей проверке Kodik отвечал `200` и без cookie (curl с подписанным телом), но cookie-jar
оставлен: он бесплатен, а зеркала/гео-правила могут требовать сессию.

Что реально помогло на проверке:
- **браузерный `User-Agent`** — с первого запроса использовался UA Windows Chrome из
  `BrowserUserAgentProfile.WINDOWS_CHROME`; `curl` без браузерного UA не проверялся, но именно UA —
  первое, что отсекает DDoS-Guard у балансеров;
- **cookie-jar на всю цепочку** iframe → скрипт → `/ftor` (OkHttp `CookieJar`, в Android-версии cookies
  переносились вручную);
- **`Referer: https://yani.tv/`** на страницу и **`Referer: <iframe-url>`** на `/ftor` — как в
  Android-версии; в текущем прогоне страница отдавала `200` даже без Referer, но заголовок оставлен для
  паритета и для зеркал;
- **`Accept-Language: ru-RU…`** — паритет с Android-версией (некоторые балансеры YummyTV отдают контент
  только «русскому» браузеру).

403/JS-заглушка в проверке не встретилась: страница сразу отдала `200` и нормальный HTML. Если Kodik
начнёт отдавать challenge — экстрактор вернёт `KodikBlockedException` с HTTP-кодом и текстом из
`<div class="message">`; лечится тем же набором (UA, Referer, Accept-Language, cookies), а полноценный
обход JS-челленджа/PO-token — вне области этого модуля (как и в Android-версии).

## 4. Запуск CLI

```powershell
$env:JAVA_HOME='E:\neuiroslop\yummypc\tools\jdk\jdk-21.0.13.1+2'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
cd E:\neuiroslop\yummypc\pc

# разрешить iframe-URL в HLS и проверить плейлист
.\gradlew.bat --console=plain :extractors:run --args="https://kodikplayer.com/season/77530/9f8291faa668c3357e0822ba23c3a366/720p?translations=false&only_episode=true&only_season=true&episode=1 --verify"

# без проверки плейлиста
.\gradlew.bat --console=plain :extractors:run --args="https://kodikplayer.com/season/.../720p?episode=1"
```

Тестовый URL из задания (сезон 77530, озвучка 2x2 New, серия 1).

Коды возврата CLI: `0` — успех; `1` — экстрактор отказал; `2` — нет/неверные аргументы; `3` — ссылка
получена, но плейлист не HLS. Gradle пробрасывает ненулевой код как падение таска.

## 5. Результат живой проверки (22.11.2026)

Вывод CLI (`--verify`), реальная серия:

```
iframe: https://kodikplayer.com/season/77530/9f8291faa668c3357e0822ba23c3a366/720p?translations=false&only_episode=true&only_season=true&episode=1
[kodik] GET iframe page
[kodik] GET player script
[kodik] POST endpoint /ftor
[kodik] check quality manifests

stream: https://cloud2.solodcdn.com/useruploads/c6dabb77-2774-43ae-946f-4e74c1b325b6/2c08876acccf7cc475b2152253a7696a:2026112220/480.mp4:hls:manifest.m3u8
header: User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36
qualities:
  360p -> https://cloud2.solodcdn.com/useruploads/c6dabb77-2774-43ae-946f-4e74c1b325b6/2c08876acccf7cc475b2152253a7696a:2026112220/360.mp4:hls:manifest.m3u8
  480p -> https://cloud2.solodcdn.com/useruploads/c6dabb77-2774-43ae-946f-4e74c1b325b6/2c08876acccf7cc475b2152253a7696a:2026112220/480.mp4:hls:manifest.m3u8

verify: HTTP 200, first line: "#EXTM3U"
verify: OK - playlist starts with #EXTM3U
```

Независимая проверка `curl` по этой же ссылке (как требует задание):

```
curl: http=200 size=10700 final=https://fenris.cloud2.solodcdn.com/.../480.mp4:hls:manifest.m3u8
#EXTM3U
#EXT-X-TARGETDURATION:6
#EXT-X-ALLOW-CACHE:YES
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-VERSION:3
```

Наблюдения:
- iframe просил `720p`, но Kodik отдал ключи `360/480/720`, где `720` — та же подписанная ссылка, что
  `480`: в URL манифеста реально `480`. HEAD «починенного» `720.mp4:hls:manifest.m3u8` вернул `404`,
  поэтому максимум — `480p`. Это ожидаемое поведение fallback-логики, а не ошибка.
- CDN отвечает `302` с `cloud2.solodcdn.com` на `fenris.cloud2.solodcdn.com`; mpv следует редиректу
  сам, поэтому в `url` остаётся исходный «красивый» адрес.
- Ошибка-путь проверен отдельно: на несуществующую серию Kodik отдаёт страницу с
  `<div class="message">Видео не найдено</div>`, CLI печатает `FAILED: Видео не найдено` и выходит с
  кодом `1`.

## 6. Структура модуля

```
pc/extractors/
  build.gradle.kts
  src/main/kotlin/su/afk/yummy/pc/extractors/
    ResolvedStream.kt      — модель результата
    StreamResolver.kt      — контракт экстрактора
    KodikExceptions.kt     — KodikBlockedException / KodikResolveException
    KodikParsing.kt        — чистые шаги разбора и кодирования (regex, base64/ROT18, тело /ftor)
    KodikHttpClient.kt     — OkHttp-клиент с cookie-jar и чтением префикса тела
    KodikResolver.kt       — сам экстрактор (порт KodikExtractor)
    PlaylistVerifier.kt    — GET-проверка «плейлист начинается с #EXTM3U»
    KodikCli.kt            — `main`, команда `:extractors:run`
  src/test/kotlin/.../KodikParsingTest.kt
```

`pc/settings.gradle.kts` содержит `include(":extractors")` — это единственная правка вне каталога
модуля (плюс этот файл документации).

## 7. Ограничения и риски

- **Ссылка короткоживущая.** В URL зашита подпись с меткой времени (`…:2026112220`). Ссылку нужно
  получать непосредственно перед воспроизведением и не кэшировать надолго: пауза/перезапуск mpv через
  час потребуют повторного resolve.
- **Подпись привязана к запросу (IP и подписанные `d_sign`/`pd_sign`).** Переиспользовать `urlParams`
  из чужого ответа нельзя; экстрактор каждый раз читает страницу заново.
- **Качество в ответе не гарантировано.** Ключ `links[720]` может указывать на `480`-манифест; реальное
  качество определяется по URL и HEAD-проверке. Поэтому `qualities` может содержать меньше пар, чем
  ключей в JSON (одинаковые качества схлопываются).
- **Зависимость от текущего устройства Kodik.** Логика завязана на конкретные маркеры страницы
  (`urlParams`, `vInfo`, `app.player_single`, `atob`-путь, `/ftor`). Если Kodik сменит разметку/плеер,
  модуль повторит поломку Android-версии — это порт, а не независимый парсер.
- **DDoS-Guard.** Возможны 403/JS-challenge в зависимости от IP/региона/частоты запросов; экстрактор
  отдаст `KodikBlockedException`. Обхода челленджей (браузер/PO-token) нет.
- **Гео-ограничения CDN.** Часть ссылок может отдавать 403/404 вне разрешённого региона — это
  проверяется только реальным GET плейлиста (`--verify` / `PlaylistVerifier`).
- **Только Kodik.** Другие балансеры (CVH, Alloha, VK и т.д.) — отдельные порты; `:extractors` пока их
  не содержит.
- **GUI не интегрирован.** Задача — модуль + CLI; вызов из `:app` (DetailsScreen, кнопка «смотреть»)
  делается отдельным шагом (см. сниппет в разделе 1). `MpvLauncher.play(url, headers)` уже принимает
  `ResolvedStream.headers`.
- **Лицензия.** Код — производная CC BY-NC 4.0 (YummyTV): коммерческое использование запрещено,
  атрибуция обязательна; подробности — `pc/LICENSE`, `pc/NOTICE`, `pc/README.md`.
