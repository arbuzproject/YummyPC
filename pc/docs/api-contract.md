# Сетевой контракт YummyAnime API (`api.yani.tv`) — спецификация для JVM-порта

Отчёт восстановлен по исходникам Android-клиента `src/YummyTV-main` и проверен 6 живыми GET-запросами
к `https://api.yani.tv` 22.11.2026. Сырые примеры ответов — в `pc/samples/`, журнал проверок —
`pc/samples/_http-checks.txt`. Отчёт не является кодом приложения: это контракт, который нужно
повторить на JVM.

## 0. Коротко

- **База:** `https://api.yani.tv`, без префикса `/api`. Константа `YANI_BASE_URL`
  (`src/YummyTV-main/core/network/src/main/kotlin/su/afk/yummy/tv/core/network/yani/YaniEndpoints.kt`).
  Удалённо подменяются только host/port (и только на `https`), пути запросов не меняются.
- **Формат:** везде JSON-обёртка `{"response": ...}`; ответы `Content-Type: application/json; charset=utf-8`,
  `Cache-Control: no-store`. Ошибки — `{"error", "error_title", "error_code", "error_name"}`.
- **Главная страница:** `GET /feed` (один запрос, внутри все секции ленты).
- **Каталог/поиск:** `GET /anime` с query-параметрами; фильтры — `GET /anime/genres`.
- **Страница тайтла:** `GET /anime/{id}` + `GET /anime/{id}/videos`
  (+ `recommendations`, `trailers`, `schedule`, `studio/{url}`, `director/{id}`, `genres/{id}`).
- **Эндпоинта `GET /anime/catalog/releases`, который предполагался в задании, в приложении нет.**
  В коде нет ни `catalog/releases`, ни `/releases` у yani. Каталог — это `GET /anime`; `GET /anime/catalog`
  упоминается только в комментарии `SearchTypeTitles.kt` как тяжёлая (~325 КБ, до 20 с) ручка, которую
  приложение больше не использует. Порт должен опираться на реальные пути ниже.
- **Заголовки:** `X-Application` (токен приложения), `Lang` (язык контента), `Authorization: Bearer <refresh-токен>`,
  `Accept: application/json` + `image/avif` (второй — только если устройство умеет AVIF).
- **Авторизация не нужна** для публичных GET (`/feed`, `/anime`, `/anime/{id}`, `/anime/{id}/videos`,
  `/anime/genres` — проверено). Нужна для прогресса, профиля, подписок и `/video/last-watches`.
- Парсинг обязан быть лояльным: `ignoreUnknownKeys = true`, `explicitNulls = false`,
  `coerceInputValues = true`; сервер регулярно присылает лишние поля, `null` вместо значений,
  протокол-относительные URL картинок (`//imgproxy.yani.tv/...`).

## 1. Клиент, который воспроизводим на JVM

Источники: `core/network/src/main/kotlin/su/afk/yummy/tv/core/network/HttpClientFactory.kt`,
`.../NetworkTimeouts.kt`, `.../yani/YaniHttpClientFactory.kt`, `.../yani/YaniApiJson.kt`.

| Параметр | Значение в Android-клиенте |
|---|---|
| Движок | Ktor на OkHttp (на JVM достаточно OkHttp/Ktor/Java HttpClient — важен контракт, не движок) |
| Таймауты Yani | connect **10 с**, request **20 с**, socket **15 с** (`NetworkTimeouts.Yani`) |
| Проверка статуса | `expectSuccess = true`: любой не-2xx бросает исключение. Ручки, которые сами разбирают тело ошибки (login/register/reset-password, YummyTV API), точечно ставят `expectSuccess = false` |
| Ретраи | `HttpRequestRetry`, `maxRetries = 1` (то есть **один** повтор, несмотря на формулировку «два повтора» в `docs/network-and-auth.md`): только `GET`, только на 5xx и на исключения, кроме `CancellationException`, `ClientRequestException` (4xx) и `HttpRequestTimeoutException`; пауза `500 мс × номер повтора` |
| Сжатие | плагин `ContentEncoding` (gzip, deflate) |
| JSON | `YaniApiJson`: `ignoreUnknownKeys = true`, `explicitNulls = false`, `coerceInputValues = true` |
| Логи | в debug Ktor `Logging` с `LogLevel.BODY`; `Authorization`, `Cookie`, `Set-Cookie`, `X-Application` маскируются. В release тело не читается (крупные ответы, `/videos` до ~5 МБ) |
| Прочее | общий OkHttp с DoH для картинок yani (`ImageHostsDns`), таймауты картинок 3 с / 5 с; к API это не относится |

Заголовки навешивает клиентский плагин `YaniApplicationHeader` **только при `request.url.host == api.yani.tv`**
(подмена host/port из feature-флага делается после проверки хоста, поэтому заголовки попадают и в
подменённый адрес). На сторонние API клиент yani не используется: заголовки токена туда не уходят.

Константы заголовков: `X-Application`, `Lang`, `Authorization` с префиксом `"Bearer "` —
`YaniHttpClientFactory.kt`.

## 2. HTTP-заголовки

### 2.1 Сводная таблица

| Заголовок | Значение | Откуда берётся | Когда не отправляется |
|---|---|---|---|
| `X-Application` | токен приложения | `YaniApplicationToken.DEFAULT = "ze645twqfeql6l1u"` (`core/model/.../settings/YaniApplicationToken.kt`); пользователь может переопределить в настройках (DataStore-ключ `yani_application_token`, `YaniAccountSettingsStore.yaniApplicationToken`, `SettingsPreferenceKeys.kt`); кэш — `YaniRequestHeaderCache` | если значение пустое (пустая строка = токен не задан, остаётся дефолт хранилища) |
| `Lang` | код языка контента: `ru`, `en`, `uk` | `YaniContentLanguage` (`core/model/.../settings/YaniContentLanguage.kt`, `DEFAULT = RUSSIAN("ru")`), хранится в DataStore; читается **на каждый запрос** (`settingsStore.yaniContentLanguage.first().apiCode`), чтобы смена языка не отставала на один запрос | если код пустой |
| `Authorization` | `Bearer <refresh-токен>` | `YaniAuthPreferences.refreshToken` — единственный носитель сессии (`SecureYaniAuthPreferences`, SharedPreferences `yani_auth_secure_preferences`), кэш `YaniRequestHeaderCache` | если токен пустой (гость) **или** запрос уже сам задал `Authorization` (например `getProfile(token)` при входе) |
| `Accept` | `application/json`, к нему добавляется ещё одно значение `image/avif` | `application/json` ставит Ktor `ContentNegotiation`; `image/avif` добавляет плагин, если `ImageFormatSupport.isAvifSupported()` (`yani/YaniImageAccept.kt`; WebP намеренно не просят — его версии мелкие) | `image/avif` не добавляется, если AVIF не декодируется |
| `User-Agent` | не задан для yani | дефолт OkHttp (`okhttp/<version>`) | — |

Значения `Lang` влияют и на фильтры каталога (`types` приходят в ответе на русском/украинском/английском из
зашитого в клиент списка, см. `SearchTypeTitles.kt`), и на тексты тайтлов.

### 2.2 Что будет, если заголовков нет

- **Без `X-Application` и `Lang`** публичный `GET /feed` всё равно вернул `200`, и тело **посимвольно
  совпало** с ответом, где оба заголовка были (408 774 символа, 0 различий; проверка X1/X2 в
  `pc/samples/_http-checks.txt`). То есть на момент проверки сервер их на публичных GET не требует.
  Приложение всё равно шлёт их всегда: токен может понадобиться на других ручках/при лимитах, а `Lang`
  управляет языком ответа. Порт должен повторять поведение приложения, а не рассчитывать на снисходительность сервера.
- **Без `Authorization`** приложение работает в гостевом режиме: публичные ручки отвечают (проверено),
  а `/video/last-watches`, `/profile`, `PUT /video/{videoId}` и прочее вернут `401/403` (по коду: сессия
  определяется `refreshToken.isNotBlank()`, без токена ручки не вызываются; ответ не-2xx превращается в
  исключение из-за `expectSuccess`). Вживую не проверялось — израсходован лимит запросов.
- **Без `Accept: image/avif`** сервер подменяет AVIF на JPEG: в поле `mega` приходит
  `posters/full/<id>.jpg` вместо `posters/mega/<id>.avif`. Проверено на `/feed` (X2) против `/anime`
  (X3–X6). Это влияет только на URL картинок, структура DTO не меняется.
- **С неверным/отозванным `X-Application`** поведение не проверялось; вероятны `401/403` или
  ограничения. Риск вынесен в раздел 6.

### 2.3 Переопределение адреса

`YaniApiConfig` (`core/network/.../yani/YaniApiConfig.kt`) принимает удалённый feature-флаг:
только `https`, пустое/невалидное/совпадающее с дефолтом значение игнорируется; подменяются **только host и port**,
пути остаются прежними. Для JVM-порта это значит: конфиг базового адреса — только хост, а пути вроде
`/anime/{id}` жёстко зашиты в контракт.

## 3. Эндпоинты

### 3.1 Главная, каталог/поиск, страница тайтла

| Метод | Путь | Ключевые query-параметры | Что возвращает | Где в коде |
|---|---|---|---|---|
| GET | `/feed` | нет | `YaniFeedDto` — вся лента: анонсы, карусель, новинки, рекомендации, новые видео, посты, блогеры, подборки | `feature/home/data/.../network/YaniHomeApi.kt` |
| GET | `/anime` | `q`, `genres`, `exclude_genres`, `types`, `status`, `from_year`, `to_year`, `season`, `min_age`, `sort`, `sort_forward`, `limit`, `offset` | `YaniSearchResponseDto` (`response[]` — карточки `YaniSearchItemDto`) | `feature/catalog/data/.../network/YaniSearchApi.kt` |
| GET | `/anime/genres` | нет | `YaniSearchGenresResponseDto`: `response.genres[]`, `response.groups[]` — опции фильтра | `YaniSearchApi.getGenres` |
| GET | `/anime/{id}` | нет | `YaniAnimeDetailsDto` — карточка тайтла | `feature/details/data/.../network/YaniAnimeApi.kt` |
| GET | `/anime/{id}/videos` | нет | `YaniAnimeVideosDto` (`response[]` — озвучки/плееры/серии; тяжёлый ответ) | `YaniAnimeApi.getAnimeVideos` |
| GET | `/anime/{id}/recommendations` | `from_ai` (Boolean), `limit=24` | `YaniRecommendationsDto` | `YaniAnimeApi.getAnimeRecommendations` |
| GET | `/anime/{id}/trailers` | нет | `YaniTrailersResponseDto` | `YaniAnimeApi.getAnimeTrailers` |
| GET | `/anime/schedule` | нет | `YaniScheduleResponseDto` (расписание на неделю) | `feature/schedule/data/.../network/YaniScheduleApi.kt` |
| GET | `/anime/studio/{url}` | нет | `YaniStudioDetailsDto` (студия по слагу из `url` карточки) | `YaniAnimeApi.getStudio` |
| GET | `/director/{id}` | нет | `YaniDirectorDetailsDto` | `YaniAnimeApi.getDirector` |
| GET | `/anime/genres/{id}` | нет | `YaniGenreDetailsDto` | `YaniAnimeApi.getGenre` |

Особенности:

- **`/anime`** — это и поиск, и каталог. Если `q` пустой/короче 2 символов, параметр `q` не отправляется
  (сервер отвечает `400` на запрос короче двух символов — комментарий в `CatalogViewModel.kt`,
  `MIN_QUERY_LENGTH = 2`). Многозначные фильтры отправляются **повторяющимся параметром**
  (`genres=8&genres=15`, `types=tv`, `status=released`, `min_age=3` и т.д.).
- Значения `sort`: `title`, `rating`, `rating_counters`, `views`, `top`, `id`, `year`
  (`SearchSort.kt`); `sort_forward` — Boolean, сериализуется как `true`/`false`. По умолчанию
  `sort=top`, `sort_forward=true` (как на сайте).
- Значения фильтров: `types` — `tv|ona|ova|movie|special` (зашиты в клиент, `SearchTypeTitles.kt`);
  `status` — `released|ongoing|announcement`; `season` — `winter|spring|summer|fall`;
  `min_age` — 1..5 (G/PG/PG-13/R-17/R+), `genres` — числовые `value` из `/anime/genres`.
  `from_year`/`to_year` — Int.
- Ответ `/anime` — **только массив, без total**. Пагинация: `limit` по умолчанию 20
  (`CatalogViewModel.PAGE_SIZE`), `nextOffset = offset + response.size`, `canLoadMore = response.size >= limit`
  (`SearchStorageMapper.kt`). Сервер может вернуть меньше `limit` — это конец выдачи.
- `sort=random` + `limit=1` — ручка «Случайный тайтл» (`YaniSearchApi.getRandomAnime`).
- `/anime/{id}` принимает и числовой id, и слаг: в `YaniAccountApi` есть вызов `GET /anime/{slug}`;
  клиент деталей ходит по id `anime_id`. Для порта надёжнее числовой id.
- `/anime/{id}/videos` у гостя **не содержит** полей `subscribed` и `watched` (они появляются у
  авторизованного); `iframe_url` — протокол-относительный (`//kodikplayer.com/...`), как и постеры.

### 3.2 Смежные ручки, нужные для полноценного порта (кратко)

| Метод | Путь | Что делает |
|---|---|---|
| GET | `/video/last-watches` | «Продолжить просмотр» (только с токеном; сервер отдаёт ≤ 4 записей, `limit` игнорируется) |
| GET | `/video/watch-history` | история просмотров, `limit`/`offset` |
| PUT | `/video/{videoId}` | прогресс: тело `{time, duration, times}` (`YaniPutVideoBodyDto`) → `{response: Boolean}` |
| POST | `/video` | массовая выгрузка: `{videos: [{video_id, time, date}]}` → Boolean |
| DELETE | `/video` | удаление прогресса: `{video_ids: [Int]}` → Boolean |
| POST | `/profile/login` | вход: `{login, password, captcha_response?}` → `{response: {token}}` (не-2xx разбирается вручную, капча = 420) |
| GET | `/profile/token` | обновление токена (`refreshToken()`) |
| GET | `/profile` | профиль; `Authorization` можно задать явно (проверка токена при входе) |
| POST | `/profile/logout`, `PATCH /profile`, `PATCH /profile/password` | выход, правка профиля, смена пароля |
| GET | `/users/{id}/lists`, `/users/{id}/lists/{listId}`, `/users/{id}/lists/subs` | списки пользователя, подписки |
| GET/PUT/DELETE | `/anime/{id}/list`, `/anime/{id}/list/fav`, `/anime/{id}/rate` | списки/избранное/оценка тайтла |
| PUT/DELETE | `/anime/{id}/recommend`, `/anime/{id}/recommend/{similarId}/vote` | рекомендации: скрыть/вернуть/голос |
| GET | `/comments/{targetType}/{targetId}`, `/comments/{id}/children` | комментарии |
| GET | `/reviews`, `/anime/{id}/reviews`, `/reviews/{id}` | рецензии |
| GET | `/posts`, `/posts/{id}`, `/posts/categories` | посты |
| GET | `/collection/{id}`, `/collection` | подборки |
| GET | `/dialogs`, `/dialogs/{userId}/messages` | личные сообщения |
| GET | `/bloggers`, `/bloggers/video`, `/bloggers/video/{id}`, `/anime/{id}/bloggervideos` | блогеры/видео |
| GET | `/pages/{type}` | статические страницы; ответ — **сырой текст**, не JSON-обёртка |

Все они ходят тем же клиентом с теми же заголовками. Полный список — `YaniAccountApi.kt` (531 строка),
`YaniMessagesApi.kt`, `YaniCommentsApi.kt`, `YaniReviewsApi.kt`, `YaniPostsApi.kt`, `YaniCollectionApi.kt`,
`YaniBloggerVideosApi.kt`, `YaniUserListsRepository.kt` и т.п.

### 3.3 Формат успеха и ошибки

- Успех: `{"response": <объект/массив/примитив>}`. Исключение — `/pages/{type}` (сырой текст).
- Ошибка: `{"error": "...", "error_title": "...", "error_code": 2, "error_name": "..."}`
  (`YaniErrorResponseDto`, `feature/account/data/.../dto/common/YaniErrorResponseDto.kt`).
  Капча: статус `420` **или** `error_code == 420` **или** слово «капч»/«captcha» в `error`
  (`YaniAccountApi.isCaptchaRequired`). Скрытые списки сервер отдаёт как `401` с `error_code = 2`
  (комментарий в `YaniUserListsRepository.kt`).
- Пустой `response` (0 элементов) — штатный ответ, но в клиенте он не затирает кэш: пустые лента/рекомендации/видео
  считаются сбоем (`MirrorDataSkippedException`).

## 4. DTO

Ниже — 4 главных DTO (главная, каталог/поиск, страница тайтла, видео тайтла) и «продолжить просмотр» как
дополнение. Все DTO — `kotlinx.serialization` (`@Serializable`), у полей есть дефолты; в колонке
«JSON (`@SerialName`)» указано фактическое имя в ответе. Если аннотации нет — имя совпадает с Kotlin-полем.

### 4.1 Главная: `YaniFeedDto`

Файл: `src/YummyTV-main/feature/home/data/src/main/kotlin/su/afk/yummy/tv/data/home/dto/YaniFeedDto.kt`
(эндпоинт `GET /feed`; пример — `pc/samples/feed.json`).

`YaniFeedDto`:

| Поле | Тип | JSON (`@SerialName`) |
|---|---|---|
| `response` | `YaniFeedResponseDto` | `response` |

`YaniFeedResponseDto`:

| Поле | Тип | JSON (`@SerialName`) |
|---|---|---|
| `announcements` | `List<YaniAnimeDto>` | `announcements` |
| `topCarousel` | `YaniCarouselDto` | `top_carousel` (`@SerialName`) |
| `new` | `List<YaniAnimeDto>` | `new` |
| `recommends` | `List<YaniAnimeDto>` | `recommends` |
| `newVideos` | `List<YaniVideoDto>` | `new_videos` (`@SerialName`) |
| `posts` | `YaniPostsDto` | `posts` |
| `blogger` | `YaniBloggerDto` | `blogger` |
| `collections` | `List<YaniCollectionDto>` | `collections` |

Вложенные типы (все в том же файле):

| Тип | Поля (Kotlin ← JSON) |
|---|---|
| `YaniCarouselDto` | `items: List<YaniAnimeDto>`; в реальном ответе ещё `season`, `year` — игнорируются |
| `YaniAnimeDto` | `animeId: Int?` ← `anime_id`, `animeUrl: String?` ← `anime_url`, `title: String`, `description: String`, `poster: YaniPosterDto?`, `rating: YaniRatingDto?`, `year: Int?` |
| `YaniRatingDto` | `average: Double?` ← `average` (реально есть ещё `counters`, `kp_rating`, `shikimori_rating`, `myanimelist_rating` и др. — игнорируются) |
| `YaniVideoDto` | `videoId: Int?` ← `video_id`, `animeId: Int?` ← `anime_id`, `animeUrl: String?` ← `anime_url`, `title: String`, `description: String`, `episodeTitle: String?` ← `ep_title`, `dubTitle: String?` ← `dub_title`, `playerTitle: String?` ← `player_title`, `poster: YaniPosterDto?` |
| `YaniPosterDto` | `small`, `medium`, `big`, `fullsize`, `mega` — все `String?`; реально приходит ещё `huge` (игнорируется) |
| `YaniPostsDto` | `items: List<YaniPostDto>`; в реальном ответе ещё `types` — игнорируется |
| `YaniPostDto` | `id: Int?`, `title: String` |
| `YaniBloggerDto` | `videos: YaniBloggerVideosDto`; в реальном ответе ещё `people` — игнорируется |
| `YaniBloggerVideosDto` | `items: List<YaniBloggerVideoDto>`; реально есть ещё `categories` |
| `YaniBloggerVideoDto` | `id: Int?`, `title: String` (остальные десятки полей ответа игнорируются) |
| `YaniCollectionDto` | `id: Int?`, `title: String`, `description: String`, `posterPreviews: List<YaniPosterDto>` ← `poster_previews`, `views: Int`, `likes: YaniCollectionLikesDto?` |
| `YaniCollectionLikesDto` | `likes: Int` (реально объект `{likes, dislikes, vote}`; `dislikes`/`vote` игнорируются) |

Реальный `/feed` дополнительно содержит `last_watches[]` и `schedule[]` (300 элементов) — DTO их не читает,
приложение берёт расписание отдельным `GET /anime/schedule`, а «продолжить просмотр» — отдельным
`GET /video/last-watches`. У элементов `new[]`/`announcements[]` в ответе **нет** поля `rating` (это нормально,
дефолт `null`); у `top_carousel.items[]` — есть.

### 4.2 Каталог/поиск: `YaniSearchResponseDto` и `YaniSearchItemDto`

Файл: `src/YummyTV-main/feature/catalog/data/src/main/kotlin/su/afk/yummy/tv/data/catalog/dto/YaniSearchDto.kt`
(эндпоинт `GET /anime`; пример — `pc/samples/catalog-search.json`).

| Тип | Поле | Тип поля | JSON (`@SerialName`) |
|---|---|---|---|
| `YaniSearchResponseDto` | `response` | `List<YaniSearchItemDto>` | `response` |
| `YaniSearchItemDto` | `animeId` | `Int?` | `anime_id` (`@SerialName`) |
| | `title` | `String` | `title` |
| | `description` | `String` | `description` |
| | `poster` | `YaniSearchPosterDto?` | `poster` |
| | `rating` | `YaniSearchRatingDto?` | `rating` |
| | `year` | `Int?` | `year` |
| `YaniSearchPosterDto` | `small`, `medium`, `big`, `fullsize`, `mega` | `String?` | одноимённые |
| `YaniSearchRatingDto` | `average` | `Double?` | `average` |

В реальном ответе у карточки есть ещё `anime_url`, `anime_status`, `genres[]`, `min_age`, `remote_ids`,
`season`, `top`, `type`, `views`, `blocked_in` — DTO их игнорирует (но `anime_url` имеет смысл добавить в
JVM-модель: сервер его реально присылает, см. `pc/samples/catalog-search.json`).

Фильтры-жанры (`GET /anime/genres`) — те же файлы:

| Тип | Поля |
|---|---|
| `YaniSearchGenresResponseDto` | `response: YaniSearchGenresDto` |
| `YaniSearchGenresDto` | `genres: List<YaniSearchGenreDto>`, `groups: List<YaniSearchGenreGroupDto>` |
| `YaniSearchGenreDto` | `title: String`, `value: Int?`, `groupId: Int?` ← `group_id` |
| `YaniSearchGenreGroupDto` | `title: String`, `id: Int?` |

В реальном ответе у жанра ещё `href` и `more_titles` (игнорируются), у групп `id = -1` у «негрупповых».
Реально пришло 103 жанра и 3 группы.

### 4.3 Страница тайтла: `YaniAnimeDetailsDto`

Файл: `src/YummyTV-main/feature/details/data/src/main/kotlin/su/afk/yummy/tv/data/details/dto/YaniAnimeDto.kt`
(эндпоинт `GET /anime/{id}`; пример — `pc/samples/anime-details-21747.json`).

| Тип | Поле | Тип поля | JSON (`@SerialName`) |
|---|---|---|---|
| `YaniAnimeDetailsDto` | `response` | `YaniAnimeResponseDto` | `response` |
| `YaniAnimeResponseDto` | `animeId` | `Int?` | `anime_id` (`@SerialName`) |
| | `animeUrl` | `String` | `anime_url` (`@SerialName`) |
| | `title` | `String` | `title` |
| | `description` | `String` | `description` |
| | `poster` | `YaniAnimePosterDto?` | `poster` |
| | `rating` | `YaniAnimeRatingDto` | `rating` |
| | `genres` | `List<YaniNamedDto>` | `genres` |
| | `year` | `Int?` | `year` |
| | `minAge` | `YaniAgeRatingDto?` | `min_age` (`@SerialName`) |
| | `views` | `Int?` | `views` |
| | `season` | `Int?` | `season` (1–4; 0 = неизвестен) |
| | `animeStatus` | `YaniAliasTitleDto?` | `anime_status` (`@SerialName`) |
| | `type` | `YaniAnimeTypeDto?` | `type` |
| | `episodes` | `YaniEpisodesDto?` | `episodes` |
| | `otherTitles` | `List<String>` | `other_titles` (`@SerialName`) |
| | `creators` | `List<YaniNamedDto>` | `creators` |
| | `studios` | `List<YaniNamedDto>` | `studios` |
| | `viewingOrder` | `List<YaniViewingOrderItemDto>` | `viewing_order` (`@SerialName`) |
| | `randomScreenshots` | `List<YaniScreenshotDto>` | `random_screenshots` (`@SerialName`) |
| | `user` | `YaniAnimeUserDto?` | `user` (только авторизованному) |
| | `reviewsCount` | `Int` | `reviews_count` (`@SerialName`) |
| | `commentsCount` | `Int` | `comments_count` (`@SerialName`) |
| | `parentCommentsCount` | `Int` | `parent_comments_count` (`@SerialName`) |
| | `postsCount` | `Int` | `posts_count` (`@SerialName`) |
| | `listsCount` | `Int` | `lists_count` (`@SerialName`) |
| | `trailersCount` | `Int` | `trailers_count` (`@SerialName`) |
| | `partnerVideosCount` | `Int` | `partner_videos_count` (`@SerialName`) |
| | `remoteIds` | `YaniRemoteIdsDto?` | `remote_ids` (`@SerialName`) |

Вложенные типы (все в том же файле):

| Тип | Поля (Kotlin ← JSON) |
|---|---|
| `YaniAnimePosterDto` | `small`, `medium`, `big`, `fullsize`, `mega` — `String?`; реально есть `huge` |
| `YaniAnimeRatingDto` | `average: Double?`, `counters: Int?`, `kinopoisk: Double?` ← `kp_rating`, `shikimori: Double?` ← `shikimori_rating`, `myAnimeList: Double?` ← `myanimelist_rating` |
| `YaniNamedDto` | `id: Int?`, `title: String`, `url: String?`, `alias: String?` |
| `YaniAgeRatingDto` | `value: Int?`, `title: String?`, `titleLong: String?` ← `title_long` |
| `YaniAliasTitleDto` | `title: String?`, `alias: String?` |
| `YaniAnimeTypeDto` | `name: String?`, `shortname: String?`, `value: Int?`, `alias: String?` |
| `YaniEpisodesDto` | `count: Int?`, `aired: Int?`, `nextDate: Long?` ← `next_date`, `prevDate: Long?` ← `prev_date` (epoch-секунды) |
| `YaniViewingOrderItemDto` | `animeId: Int?` ← `anime_id`, `title: String`, `data: YaniViewingOrderDataDto?`, `type: YaniAnimeTypeDto?`, `poster: YaniAnimePosterDto?`, `year: Int?`, `rating: Double?` |
| `YaniViewingOrderDataDto` | `text: String?` |
| `YaniScreenshotDto` | `id: Int?`, `episode: String?`, `sizes: YaniScreenshotSizesDto` |
| `YaniScreenshotSizesDto` | `small: String?`, `full: String?` |
| `YaniAnimeUserDto` | `rating: Double?` |
| `YaniRemoteIdsDto` | `myanimelistId: Int?` ← `myanimelist_id` (используется для запроса названий серий на своём бэкенде) |

Реальные поля, которых нет в DTO (игнорируются): `blocked_in` (массив стран блокировки, напр. `["IN"]`),
`collections_count`, `duration` (секунды), `blogger_videos_count`, `original` (напр. "Манга"),
`top {global, category}`, `translates[]`. Элементы `viewing_order[]` реально содержат ещё
`anime_url`, `anime_status`, `user`, `description`, `rating`, `data {text, id, index}` — DTO читает только
`anime_id`, `title`, `data.text`, `type`, `poster`, `year`, `rating`.

### 4.4 Видео тайтла: `YaniAnimeVideosDto`

Те же файл и эндпоинт: `src/YummyTV-main/feature/details/data/.../dto/YaniAnimeDto.kt`,
`GET /anime/{id}/videos` (пример — `pc/samples/anime-videos-21747.json`, 138 видео у 24-серийного тайтла
из-за всех озвучек/плееров).

| Тип | Поле | Тип поля | JSON (`@SerialName`) |
|---|---|---|---|
| `YaniAnimeVideosDto` | `response` | `List<YaniAnimeVideoDto>` | `response` |
| `YaniAnimeVideoDto` | `videoId` | `Int` | `video_id` (`@SerialName`) |
| | `data` | `YaniVideoDataDto` | `data` |
| | `number` | `String` | `number` (номер серии строкой, может быть `"02"`/`"1"`) |
| | `iframeUrl` | `String` | `iframe_url` (`@SerialName`) |
| | `duration` | `Int?` | `duration` (секунды) |
| | `views` | `Int?` | `views` |
| | `watched` | `YaniVideoWatchedDto?` | `watched` (только с токеном) |
| | `subscribed` | `Boolean` (дефолт `false`) | `subscribed` (только с токеном) |
| | `skips` | `YaniVideoSkipsDto?` | `skips` |
| `YaniVideoDataDto` | `player` | `String` | `player` |
| | `dubbing` | `String` | `dubbing` |
| | `playerId` | `Int?` | `player_id` (`@SerialName`) |
| `YaniVideoWatchedDto` | `endTime` | `Int?` | `end_time` (`@SerialName`) |
| | `date` | `Long?` | `date` (epoch-секунды) |
| `YaniVideoSkipsDto` | `opening`, `ending` | `JsonElement?` | `opening`, `ending` (сырые объекты `{time, length}`; тип намеренно не фиксирован) |

Реальные поля, которых нет в DTO (игнорируются): `author {avatars, roles}`, `date`, `index`.
У гостя полей `watched`/`subscribed` нет вообще. `iframe_url` протокол-относительный
(`//kodikplayer.com/...`).

### 4.5 «Продолжить просмотр»: `YaniLastWatchesDto`

Файл: `feature/home/data/src/main/kotlin/su/afk/yummy/tv/data/home/dto/YaniLastWatchesDto.kt`
(эндпоинт `GET /video/last-watches`, только с токеном).

| Тип | Поле | Тип | JSON (`@SerialName`) |
|---|---|---|---|
| `YaniLastWatchesDto` | `response` | `List<YaniLastWatchDto>` | `response` |
| `YaniLastWatchDto` | `animeId` | `Int` | `anime_id` (`@SerialName`) |
| | `title` | `String` | `title` |
| | `description` | `String` | `description` |
| | `poster` | `YaniPosterDto?` | `poster` |
| | `episode` | `String` | `ep_title` (`@SerialName`) |
| | `endTime` | `Int` | `end_time` (`@SerialName`) |
| | `duration` | `Int` | `duration` |
| | `date` | `Long` | `date` |
| | `screenshot` | `YaniLastWatchScreenshotDto?` | `screenshot` |
| `YaniLastWatchScreenshotDto` | `sizes` | `YaniLastWatchScreenshotSizesDto?` | `sizes` |
| `YaniLastWatchScreenshotSizesDto` | `small`, `full` | `String?` | одноимённые |

### 4.6 Общие правила разбора JSON

- Обёртка `{"response": ...}` — у всех ручек из этого отчёта; `/pages/{type}` отдаёт сырой текст.
- `ignoreUnknownKeys = true` — сервер присылает много полей, которых нет в DTO (перечислены выше).
  JVM-порт должен так же игнорировать незнакомые поля, иначе любой апдейт сервера ломает парсинг.
- `explicitNulls = false`, `coerceInputValues = true` — `null` в non-null поле с дефолтом превращается в
  дефолт (yani регулярно присылает `null` там, где по схеме строка/число, пример из кода — `sub.dubbing`).
- Ссылки на картинки и плеер — **протокол-относительные** (`//imgproxy.yani.tv/...`, `//static.yani.tv/...`,
  `//kodikplayer.com/...`); клиент приводит их к `https:` функцией `String.toHttpsUrl()`
  (`core/utils/.../network/HttpUrlUtils.kt`). `http://` внутри yani тоже меняется на `https://`.
- Сезон в списках пользователя бывает числом **и** слагом (`winter`/`spring`/…), поэтому в `YaniUserAnimeDto`
  поле `season` — `JsonElement?` с ручным разбором; в `YaniAnimeResponseDto` — `Int?`. Для JVM-порта
  это готовый рецепт: сырое поле + нормализация, а не строгий тип.
- Дробные рейтинги — `Double`; «нулевой» рейтинг (`0.0`) мапперы считают отсутствующим.
- Номера серий приходят строками, ведущие нули срезаются (`"02"` → `"1"`, но `"OVA 1"` не трогается —
  `normalizedEpisodeNumber()` в `core/common/.../utils/episode/EpisodeUtils.kt`).

## 5. Результаты реальных проверок

Всего 6 GET (лимит задания — не больше 5–6), `Invoke-WebRequest` из PowerShell 5.1, без ретраев.
Полный журнал с заголовками запросов и ответов — `pc/samples/_http-checks.txt`.

| № | Запрос | Заголовки | Статус | Размер тела | Сохранено |
|---|---|---|---|---|---|
| X1 | `GET /feed` | только `Accept: application/json` (без `X-Application`, `Lang`, `Authorization`, `image/avif`) | **200** | 408 774 симв. (555 569 байт) | не сохранён — посимвольно равен X2 (0 отличий) |
| X2 | `GET /feed` | `Accept: application/json`, `X-Application: ze645twqfeql6l1u`, `Lang: ru` | **200** | `Content-Length: 555569` | `pc/samples/feed.json` |
| X3 | `GET /anime?limit=10&offset=0&sort=top&sort_forward=true` | `Accept: application/json, image/avif`, `X-Application`, `Lang: ru` | **200** | `Content-Length: 32172` | `pc/samples/catalog-search.json` |
| X4 | `GET /anime/21747` | то же | **200** | `Content-Length: 13957` | `pc/samples/anime-details-21747.json` |
| X5 | `GET /anime/21747/videos` | то же | **200** | `Content-Length: 76120` | `pc/samples/anime-videos-21747.json` |
| X6 | `GET /anime/genres` | то же | **200** | `Content-Length: 10423` | `pc/samples/anime-genres.json` |

Что реально пришло:

- **Ответные заголовки** (одинаковые по смыслу у всех шести): `Content-Type: application/json; charset=utf-8`,
  `Cache-Control: no-store`, `Access-Control-Allow-Origin: *`, `Server: nginx`,
  `Connection: keep-alive`, `Keep-Alive: timeout=15`, `Server-Timing` (js/ip/db/total),
  `Date: Sun, 22 Nov 2026 08:19–08:20 GMT`. `ETag`/`Last-Modified` не присылаются — кэшировать нужно самим.
- **`/feed`**: `announcements[20]`, `top_carousel{items[30], season=4, year=26}`, `new[18]`,
  `recommends[0]`, `new_videos[20]`, `posts{items[10]}`, `blogger{videos{items[5]}, people}`, `collections[10]`,
  `last_watches[0]`, `schedule[300]`; постеры — JPEG (`posters/full/…jpg`), потому что `image/avif` не запрашивался.
- **Без заголовков `X-Application`/`Lang`** `/feed` вернул ровно тот же JSON (X1 и X2 совпали посимвольно,
  408 774 символа, 555 569 байт); при этом сервер по умолчанию отдал русский контент — значит `Lang` по
  умолчанию `ru`, а токен приложения на публичной ленте сейчас не обязателен.
- **`Accept: image/avif` работает**: в `/anime` (X3) `poster.mega = //imgproxy.yani.tv/posters/mega/1636669570.avif`,
  `poster.huge = …/huge/1636669570.avif`; при этом `big/small/medium/fullsize` остались
  `//imgproxy.yani.tv/posters/full/1636669570.jpg`. Без `image/avif` (X2) `mega` = JPEG `posters/full/…jpg`.
  То же подтвердили `/anime/21747`, `/anime/21747/videos`, `/anime/genres` (там картинок нет).
- **`/anime`**: 10 карточек, ids `10818, 1486, 24920, 481, 1509, 19627, 468, 1274, 610, 662`;
  первая — «Аватар: Легенда об Аанге» (`anime_id=10818`). Полей пагинации/`total` в ответе нет.
- **`/anime/21747`**: «Чёрный клевер 2», `episodes{count=24, aired=9, next_date=1795874400, prev_date=1795269600}`,
  `rating{average=9.4056, counters=1351, kp_rating=8.3, shikimori_rating=8.9044, myanimelist_rating=8.89}`,
  `remote_ids.myanimelist_id=61967`, `viewing_order[7]`, `min_age{value=3,title="PG-13"}`,
  `anime_status{value=1,title="онгоинг"}`, `type{name="Сериал",shortname="ТВ",value=1,alias="tv"}`;
  постер — AVIF в `mega`.
- **`/anime/21747/videos`**: 138 элементов; элемент 0 — `video_id=1028416`,
  `data{player="Плеер Kodik", dubbing="Озвучка AniStar", player_id=4}`, `number="1"`, `duration=1222`,
  `iframe_url=//kodikplayer.com/season/...`, `skips{opening{time:0,length:329}, ending{time:1202,length:19}}`.
- **`/anime/genres`**: 103 жанра, 3 группы; жанр `{title, value, group_id, href, more_titles[]}`,
  группа `{"title":"Японские","id":-1}`.
- Тайминги сервера (`Server-Timing total`): `/anime` ≈ 97 мс, `/anime/{id}` ≈ 3,7 мс, остальные — единицы мс
  на момент проверки; это не гарантия, закладывайте таймауты из §1.

Файлы-образцы в `pc/samples/`: `feed.json`, `catalog-search.json`, `anime-details-21747.json`,
`anime-videos-21747.json`, `anime-genres.json`, журнал `_http-checks.txt`, описание — `README.md`.
Всё сохранено как пришло (UTF-8, без BOM), файлы не редактировались.

## 6. Риски

1. **`X-Application` — публичный встроенный токен** `ze645twqfeql6l1u`. Проверка показала, что публичные GET
   отвечают и без него, но это поведение сервера, а не гарантия: токен могут начать требовать, ограничивать
   по нему частоту или отзывать. Нужен конфигурируемый токен + пользовательское переопределение (как в
   настройках приложения) и готовность к `401/403`.
2. **Нет подписи запросов у `api.yani.tv`** — подписываются только запросы к **своему** серверу YummyTV
   (`/api/...`, копия yani `/api/yani/...`): интерфейс `SiteRequestSigner` (`X-Sig*`), реализация живёт в
   отдельном приватном модуле `private/sitesign`, без неё сервер отклоняет запросы. Если порт захочет
   ходить через копию на своём сервере, придётся реализовать подпись или поднять сервер без неё.
3. **Авторизация.** Единственный носитель сессии — refresh-токен в `Authorization: Bearer`. Без него нет
   `/video/last-watches`, прогресса, подписок, профиля, пользовательских списков. Автообновления по 401 нет:
   ретраи в клиенте только для GET/5xx, токен обновляется отдельным `GET /profile/token` (при старте, по
   событиям). Android-хранилище — Keystore/AES-GCM с fallback; на JVM нужен аналог (DPAPI/Windows Credential
   Manager) и та же логика отката сессии, иначе «полувошедшее» состояние.
4. **`Lang`.** В коде три кода: `ru`, `en`, `uk`. Сейчас сервер по умолчанию отдаёт `ru`; для `en/uk`
   контент может быть неполным (переводы тайтлов/описаний). Заголовок нужно отправлять на каждый запрос,
   а не кэшировать один раз: смена языка не должна отставать.
5. **AVIF.** `Accept: image/avif` меняет URL постеров на `.avif` (поля `mega`/`huge`). JVM может не уметь
   декодировать AVIF «из коробки» (ImageIO требует плагин; JavaFX/Skia — отдельная зависимость). Варианты:
   не просить AVIF (тогда сервер отдаст JPEG в `mega`/`fullsize`, трафик больше) или добавить AVIF-декодер.
   Смешивать нельзя: формат определяется заголовком на весь запрос.
6. **Лояльный парсинг обязателен.** Сервер присылает лишние поля, `null` в non-null полях, плавающие типы
   (`season` числом и слагом в списках пользователя), номера серий строками с ведущими нулями. Строгий
   JSON-парсер сломается на первом же обновлении API. Нужны те же `ignoreUnknownKeys`/`explicitNulls=false`/
   `coerceInputValues=true` и ручная нормализация сезона/серий.
7. **Протокол-относительные URL.** `//imgproxy.yani.tv`, `//static.yani.tv`, `//kodikplayer.com` — без
   нормализации в `https://` картинки/плеер не загрузятся на JVM.
8. **Картинки могут не открываться** (блокировки/DNS/CDN). В Android-клиенте для этого есть DoH, память о
   сбоях хоста и цепочка запасных источников (постер: ссылка из API → тот же путь на `static.yani.tv` →
   свой хост статики). Для порта минимум — таймауты 3–5 с и запасной URL, иначе интерфейс будет «висеть».
9. **Кэширование.** `Cache-Control: no-store`, нет `ETag`; сервер ничего не кэширует на клиенте. TTL из
   кода: лента 60 с, результаты поиска 1 ч (копия — 5 мин), фильтры 24 ч, детали 24 ч, видео 5 мин,
   трейлеры 6 ч, рекомендации 6 ч/5 мин, last-watches 60 с, watch-history 10 с. Без своих кэшей порт будет
   спамить сервер и тормозить.
10. **Пагинация без `total`.** Конец выдачи определяется эвристикой `response.size < limit`; сервер может
    вернуть меньше, чем просили. `q` короче 2 символов — `400` (клиент не отправляет такой запрос).
11. **Ошибки и кэш.** Из-за `expectSuccess` не-2xx не должен разбираться в «пустой успех» с дефолтами:
    иначе 401/5xx затрёт кэш пустотой. Для `login`/`register`/reset и своего API статус разбирается вручную;
    капча — 420 (в статусе и/или `error_code`).
12. **Гео/контентные ограничения.** В ответах есть `blocked_in` (например, `["IN"]`) и поля `blocked_*` у
    источников; возможны региональные `403` на отдельные тайтлы. Порт должен показывать ошибку, а не падать.
13. **Тяжёлые ответы.** `/anime/{id}/videos` у длинных тайтлов — до нескольких мегабайт (у 24-серийного
    тайтла пришло 138 видео, 76 КБ только в этом срезе; в коде упоминается ~5 МБ). Нужны асинхронность,
    таймаут запроса 20 с и запрет логировать тело в проде.
14. **Лимиты/антибот неизвестны.** Сделано всего 6 запросов, сервер не показал ни `429`, ни капчи. Не
    исключены ограничения по IP/пользователю; нужен вежливый backoff и кэш.
15. **Расхождение документации и кода.** В `docs/yani-api.md`/`network-and-auth.md` написано «два повтора»
    и таймауты 20/40/40 с, а в коде `maxRetries = 1` и `NetworkTimeouts.Yani = 10/20/15`. Для порта
    верить коду (см. §1); при сомнениях сверяться с `YaniHttpClientFactory.kt` и `NetworkTimeouts.kt`.
16. **Свой бэкенд (опция).** Контракт трёх ручек своего сервера (`GET {baseApi}/anime/mal/{malId}?lang=`,
    `POST {baseApi}/availability`, статика AVIF) — отдельный, см. `docs/own-backend.md`. Он нужен для
    названий/описаний серий и внешних озвучек; без него приложение работает, но теряет эти данные.
    Подпись запросов к своему серверу — отдельный риск (см. п.2).

## 7. Чек-лист JVM-порта

1. HTTP-клиент: HTTPS, gzip/deflate, таймауты 10/20/15 с, один ретрай GET на 5xx, `expectSuccess`-семантика.
2. Провайдер заголовков: `X-Application` (дефолт `ze645twqfeql6l1u` + переопределение), `Lang` (ru/en/uk,
   на каждый запрос), `Authorization: Bearer <token>` (если токен есть и запрос не задал свой).
3. `Accept`: `application/json` всегда; `image/avif` — только при подтверждённой поддержке декодера.
4. JSON: kotlinx.serialization (JVM) с теми же тремя флагами; DTO из §4 как есть; unknown-поля не ломают разбор.
5. Нормализация URL: `//...` → `https://...`; `http://` → `https://`.
6. Токен-менеджмент: безопасное хранение, вход `POST /profile/login`, обновление `GET /profile/token`,
   выход `POST /profile/logout`, откат сессии при ошибке после записи токена.
7. Кэши с TTL из §6.9 (лента/поиск/детали/видео/расписание) и правило «ошибка/пустота не затирает кэш».
8. Пагинация `offset += response.size`, `canLoadMore = size >= limit`, `q` не короче 2 символов.
9. Ошибки: маппинг кодов 401/403/404/429/5xx на сообщения; тело ошибки — `YaniErrorResponseDto`; капча 420.
10. Конфиг: базовый адрес (`https`, только хост/порт при переопределении), свой API/статика — если нужен
    свой бэкенд; значения не хардкодить в UI-слое.

## 8. Источники

Документация Android-проекта:

- `src/YummyTV-main/docs/yani-api.md` — клиент, заголовки, поля с особенностями, видео, профиль, списки.
- `src/YummyTV-main/docs/network-and-auth.md` — клиенты, заголовки, токен, Keystore, ошибки.
- `src/YummyTV-main/docs/own-backend.md` — контракт своего сервера (TMDB-серии, availability, статика), подпись.
- `src/YummyTV-main/docs/continue-watching.md`, `image-loading-and-memory.md`, `subscriptions.md` — смежные детали.

Код (ключевые файлы):

- `core/network/src/main/kotlin/su/afk/yummy/tv/core/network/yani/YaniEndpoints.kt` — `YANI_BASE_URL`.
- `.../yani/YaniHttpClientFactory.kt` — заголовки, ретраи, `expectSuccess`, `Accept`.
- `.../yani/YaniRequestHeaderCache.kt` — источники значений заголовков.
- `.../yani/YaniImageAccept.kt` — `image/avif`.
- `.../yani/YaniApiConfig.kt` — удалённая подмена host/port.
- `.../yani/YaniApiJson.kt` — флаги JSON.
- `.../NetworkTimeouts.kt`, `.../HttpClientFactory.kt` — таймауты, gzip/deflate.
- `core/model/.../settings/YaniApplicationToken.kt`, `YaniContentLanguage.kt` — дефолты.
- `feature/home/data/.../network/YaniHomeApi.kt`, `.../dto/YaniFeedDto.kt`, `.../dto/YaniLastWatchesDto.kt`.
- `feature/catalog/data/.../network/YaniSearchApi.kt`, `.../dto/YaniSearchDto.kt`, `.../repository/YaniSearchRepository.kt`.
- `feature/details/data/.../network/YaniAnimeApi.kt`, `.../dto/YaniAnimeDto.kt`, `.../mapper/YaniAnimeMapper.kt`.
- `feature/schedule/data/.../network/YaniScheduleApi.kt`, `.../dto/YaniScheduleDto.kt`.
- `feature/account/data/.../network/YaniAccountApi.kt`, `.../dto/video/*.kt`, `.../dto/common/YaniErrorResponseDto.kt`.
- `core/utils/.../network/HttpUrlUtils.kt` — нормализация URL.

Живые проверки и образцы: `pc/samples/_http-checks.txt`, `pc/samples/*.json`.
