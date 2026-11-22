# YummyPC — порт YummyTV на ПК

Неофициальный desktop-клиент YummyAnime для Windows. Порт Android-клиента
[YummyTV](https://github.com/Helandy/YummyTV) на Kotlin + Compose Desktop.

**Based on YummyTV — https://github.com/Helandy/YummyTV**

Проект распространяется под CC BY-NC 4.0 (см. `LICENSE`, `NOTICE`).
Коммерческое использование запрещено. Оригинальный проект и его авторы не связаны с этим портом.

## Архитектура

| Модуль | Назначение |
|---|---|
| `:api` | yani API (Ktor), модели для UI, кэш ответов на 10 минут, авторизация, списки, история |
| `:extractors` | экстракторы балансеров: Kodik, Alloha, CVH, VK, Rutube, Aksibnet, Zedfilm |
| `:player-ipc` | JSON IPC к mpv через named pipe (JNA + overlapped I/O) |
| `:app` | Compose Desktop UI: главная, топ, расписание, поиск, тайтл, плеер, профиль, списки, история, настройки |

Токены приложения yani клиент не хранит: запросы идут через сервер YummyPC (`backend/`).
Сборка и возможности — в корневом README.