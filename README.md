# YummyPC — неофициальный десктоп-клиент YummyAnime

Клиент YummyAnime для Windows на Kotlin + Compose Desktop со встроенным плеером на базе mpv.

[![Скачать](https://img.shields.io/badge/⬇_Скачать-1.0.0-F08A4B?style=for-the-badge)](https://github.com/arbuzproject/YummyPC/releases/latest)
[![Release](https://img.shields.io/github/v/release/arbuzproject/YummyPC?label=version&color=F08A4B)](https://github.com/arbuzproject/YummyPC/releases)
[![License: CC BY-NC 4.0](https://img.shields.io/badge/license-CC%20BY--NC%204.0-lightgrey)](LICENSE)

> **Based on YummyTV — https://github.com/Helandy/YummyTV** (CC BY-NC 4.0).
> См. `LICENSE` и `NOTICE`. Проект не связан с YummyAnime и не является официальным клиентом.

## Скачать

Готовые сборки — в разделе **[Releases](https://github.com/arbuzproject/YummyPC/releases/latest)**:

- **Portable** (`YummyPC-1.0.0-portable.zip`) — распаковать и запустить `YummyPC.exe`, ничего устанавливать не нужно.
- **Установщик** (`YummyPC-1.0.0.msi`) — обычная установка в систему.

Требования: Windows 10/11 x64. Плеер mpv входит в portable-сборку.

## Возможности

- Каталог, новинки, топ, расписание, поиск с фильтрами и сортировкой.
- Страница тайтла: рейтинги, скриншоты, озвучки и балансеры, серии, похожие тайтлы, жанры, подписка на новые серии, списки.
- Плеер: переключение озвучки и балансера, выбор качества, автопропуск опенинга/эндинга с задержкой, автопереход к следующей серии, полный экран, синхронизация просмотров.
- Главная: продолжить просмотр, новые серии из ваших списков и новые серии всех тайтлов.
- Профиль: статистика просмотра, активность, списки, история, коллекции.
- Новости и комментарии.
- Тёмная, светлая и системная темы оформления.
- Локальная библиотека: списки и история сохраняются на устройстве, если нет соединения или входа в аккаунт.

## Скриншоты

| | |
|---|---|
| ![Главная](screenshots/v11-home.png) | ![Страница тайтла](screenshots/v11-details.png) |
| ![Поиск](screenshots/v11-search.png) | ![Комментарии](screenshots/v11-comments.png) |
| ![Плеер: качество](screenshots/v12-quality.png) | ![Новости](screenshots/v10-news.png) |
| ![Профиль](screenshots/v10-profile.png) | ![Коллекции](screenshots/v10-collections.png) |
| ![Светлая тема](screenshots/v10-home-light.png) | ![Настройки](screenshots/v10-settings.png) |

## Сборка

Требования: Windows 10/11 x64, **JDK 21**, **mpv**.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
cd pc
.\gradlew.bat :app:createDistributable
```

Готовая сборка появится в `pc\app\build\compose\binaries\main\app\YummyPC\`.
Рядом с `YummyPC.exe` положите `mpv\mpv.exe` и `mpv\mpv.com`.

Клиент не хранит токены сервиса: они остаются на серверной части (`backend/`).
Адрес своего сервера укажите локально — в переменной окружения `YUMMYPC_BACKEND`
или в `%USERPROFILE%\.yummypc\settings.json` (`backendUrl`). В репозитории адресов нет.

## Структура

| Каталог | Что это |
|---|---|
| `pc/` | Gradle-проект клиента: `:api`, `:app`, `:extractors`, `:player-ipc` |
| `pc/docs/` | Документация: контракт API, экстракторы, плеер, правила порта |
| `backend/` | Серверная часть на Go: прокси к API и внешние балансеры |
| `screenshots/` | Скриншоты приложения |

## О проекте

Проект создан с помощью нейросетей: код, интерфейс и документация разрабатывались с использованием ИИ
(DeepSeek и другие модели). Возможны ошибки и неполные сценарии — используйте на свой риск.

## Лицензия

Производная работа от [YummyTV](https://github.com/Helandy/YummyTV), распространяется на условиях
**CC BY-NC 4.0**: свободное использование, изменение и распространение только в некоммерческих целях,
с указанием авторства. Полный текст — в `LICENSE`.
