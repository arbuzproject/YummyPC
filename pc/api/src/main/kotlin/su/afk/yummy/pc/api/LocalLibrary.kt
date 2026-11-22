// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Запись списка в локальном снимке [LocalLibrary].
 *
 * @property id id тайтла на yani.
 * @property title название на момент сохранения.
 * @property posterUrl абсолютная ссылка на постер, если сервер её отдал.
 * @property href ключ статуса списка: `fav` для «Любимого», иначе href списка с сайта
 *   (`watch_now`, `will`, `watched`, `lost`, `postponed`) — по нему в локальном режиме
 *   работают вкладки и бейджи, как по [UserListEntry.statusKey].
 * @property rating оценка: личная оценка пользователя, если он её ставил, иначе рейтинг сайта.
 * @property year год выхода.
 * @property typeName тип тайтла (сериал, фильм…).
 */
@Serializable
data class StoredListEntry(
    val id: Int,
    val title: String,
    val posterUrl: String? = null,
    val href: String,
    val rating: Double? = null,
    val year: Int? = null,
    val typeName: String? = null,
)

/**
 * Локальный снимок пользовательских списков одного аккаунта.
 *
 * @property userId чей это снимок: у разных аккаунтов свои списки.
 * @property savedAtSec unix-время сохранения в секундах — экран показывает его как «сохранено …».
 * @property entries тайтлы всех списков в порядке ответа сервера.
 */
@Serializable
data class StoredLists(
    val userId: Int,
    val savedAtSec: Long,
    val entries: List<StoredListEntry> = emptyList(),
)

/**
 * Запись истории просмотра в локальном снимке [LocalLibrary].
 *
 * Повторяет [WatchHistoryItem] без кадра из серии: для оффлайн-показа кадр не нужен,
 * а файл он раздувает. Пустые `episode`/`posterUrl`/`watchedAtSec` означают, что сервер
 * этих данных не прислал.
 */
@Serializable
data class StoredHistory(
    val animeId: Int,
    val title: String,
    val episode: String = "",
    val posterUrl: String? = null,
    val watchedAtSec: Long? = null,
    val positionSec: Int = 0,
    val durationSec: Int = 0,
)

/** Отложенное действие «поставить статус списка»: уйдёт на сервер в [LocalLibrary.flush]. */
@Serializable
internal data class PendingListStatus(
    val animeId: Int,
    val listId: Int,
)

/** Отложенное действие «добавить/убрать Любимое»: уйдёт на сервер в [LocalLibrary.flush]. */
@Serializable
internal data class PendingFavorite(
    val animeId: Int,
    val favorite: Boolean,
)

/** Содержимое `library.json`: снимки, история и очередь неотправленных действий. */
@Serializable
private data class LibraryFileDto(
    val lists: List<StoredLists> = emptyList(),
    val history: List<StoredHistory> = emptyList(),
    val pendingListStatus: List<PendingListStatus> = emptyList(),
    val pendingFavorite: List<PendingFavorite> = emptyList(),
) {
    /** Совсем ничего не сохранено — такой файл создавать не нужно. */
    val isEmpty: Boolean
        get() = lists.isEmpty() && history.isEmpty() &&
            pendingListStatus.isEmpty() && pendingFavorite.isEmpty()
}

/**
 * Локальная библиотека: снимки списков и истории просмотра плюс очередь действий,
 * которые не удалось отправить на yani.
 *
 * Зачем: без авторизации или при недоступном сервере экраны «Мои списки» и «История
 * просмотра» показывают последний удачный снимок с пометкой «Локальная библиотека»
 * (вместо пустого экрана или приглашения войти). Действия со списками, сделанные
 * в оффлайне или без токена, складываются в очередь и отправляются на сервер в [flush],
 * как только он снова ответит.
 *
 * Хранение: `%USERPROFILE%\.yummypc\library.json` в формате kotlinx.serialization.
 * Все операции с файлом выполняются под общим монитором ([synchronized]), поэтому
 * UI-поток и корутины безопасно читают и пишут библиотеку одновременно. Ошибки
 * ввода-вывода и битый JSON глушатся: сломанный файл читается как пустая библиотека,
 * неудачная запись не роняет приложение.
 */
object LocalLibrary {

    /** Сколько снимков разных аккаунтов храним: при смене аккаунта старые вытесняются. */
    private const val MAX_STORED_ACCOUNTS = 5

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** Общий монитор: и файл, и структуры меняются только под ним. */
    private val lock = Any()

    /** Один [flush] за раз: иначе одна операция могла бы уйти на сервер дважды. */
    private val flushMutex = Mutex()

    /** `%USERPROFILE%\.yummypc\library.json`. */
    private val libraryFile: File
        get() {
            val home = System.getProperty("user.home")
                ?: System.getenv("USERPROFILE")
                ?: "."
            return File(File(home, ".yummypc"), "library.json")
        }

    // --- Списки ---

    /**
     * Запоминает списки пользователя [userId], только что присланные сервером: полный
     * снимок всех вкладок. Снимок того же пользователя заменяется целиком, снимки
     * прошлых аккаунтов вытесняются по одному. Некорректный id игнорируется.
     */
    fun saveLists(userId: Int, entries: List<UserListEntry>) {
        if (userId <= 0) return
        synchronized(lock) {
            val library = readLibrary()
            val snapshot = StoredLists(
                userId = userId,
                savedAtSec = nowSec(),
                entries = entries.map { it.toStoredListEntry() },
            )
            val lists = (library.lists.filterNot { it.userId == userId } + snapshot)
                .takeLast(MAX_STORED_ACCOUNTS)
            writeLibrary(library.copy(lists = lists))
        }
    }

    /**
     * Списки из локального снимка [userId] (все вкладки одним списком, как их отдал сервер).
     *
     * @return `null` — снимка этого пользователя нет; пустой список — снимок есть, но он пуст.
     */
    fun loadLists(userId: Int): List<StoredListEntry>? = synchronized(lock) {
        readLibrary().lists.lastOrNull { it.userId == userId }?.entries
    }

    /** Полный снимок [userId]: нужен экрану, чтобы показать дату сохранения. */
    fun loadListsSnapshot(userId: Int): StoredLists? = synchronized(lock) {
        readLibrary().lists.lastOrNull { it.userId == userId }
    }

    /** Самый свежий снимок среди всех аккаунтов: используется, если id пользователя неизвестен. */
    fun latestLists(): StoredLists? = synchronized(lock) {
        readLibrary().lists.maxByOrNull { it.savedAtSec }
    }

    // --- История просмотра ---

    /** Запоминает историю с сервера, заменяя предыдущий снимок целиком. */
    fun saveHistory(items: List<WatchHistoryItem>) {
        synchronized(lock) {
            val library = readLibrary()
            writeLibrary(library.copy(history = items.map { it.toStoredHistory() }))
        }
    }

    /**
     * История из локального снимка в порядке сервера (свежие сверху).
     *
     * @return `null` — снимка нет (или он пуст): экран в этом случае показывает пустое
     *   состояние или ошибку, но не «локальную копию».
     */
    fun loadHistory(): List<StoredHistory>? = synchronized(lock) {
        readLibrary().history.takeIf { it.isNotEmpty() }
    }

    // --- Очередь действий без сети ---

    /**
     * Откладывает смену статуса списка до следующего успешного соединения с сервером.
     *
     * Повторный вызов для того же тайтла заменяет предыдущее действие: на сайте важно
     * последнее выбранное, а не вся история переключений. Некорректный id игнорируется.
     */
    fun queueListStatus(animeId: Int, listId: Int) {
        if (animeId <= 0) return
        synchronized(lock) {
            val library = readLibrary()
            val pending = library.pendingListStatus.filterNot { it.animeId == animeId } +
                PendingListStatus(animeId = animeId, listId = listId)
            writeLibrary(library.copy(pendingListStatus = pending))
        }
    }

    /** Откладывает добавление/удаление «Любимого»; последнее действие для тайтла побеждает. */
    fun queueFavorite(animeId: Int, favorite: Boolean) {
        if (animeId <= 0) return
        synchronized(lock) {
            val library = readLibrary()
            val pending = library.pendingFavorite.filterNot { it.animeId == animeId } +
                PendingFavorite(animeId = animeId, favorite = favorite)
            writeLibrary(library.copy(pendingFavorite = pending))
        }
    }

    /** Сколько действий ждёт отправки (статусы списков + «Любимое»). */
    fun pendingCount(): Int = synchronized(lock) {
        val library = readLibrary()
        library.pendingListStatus.size + library.pendingFavorite.size
    }

    /**
     * Отправляет накопленные действия через [api], удаляя только успешные.
     *
     * Сначала уходят статусы списков, затем «Любимое» — как при обычной работе на сайте.
     * Ошибка сети, 401 или 5xx оставляют действие в очереди: оно отправится при следующем
     * вызове [flush]. Частичный успех тоже сохраняется — успешные действия не повторяются.
     * Параллельные вызовы выстраиваются через [flushMutex]; действия, поставленные в очередь
     * во время отправки, не теряются.
     *
     * @param api клиент yani; токен берётся из [YummyConfig], поэтому вызывающий ничего
     *   не передаёт.
     */
    suspend fun flush(api: YaniApi) {
        flushMutex.lock()
        try {
            val snapshot = synchronized(lock) { readLibrary() }
            val listOps = snapshot.pendingListStatus
            val favoriteOps = snapshot.pendingFavorite
            if (listOps.isEmpty() && favoriteOps.isEmpty()) return

            val sentListOps = ArrayList<PendingListStatus>(listOps.size)
            for (op in listOps) {
                if (attemptSend { api.setListStatus(op.animeId, op.listId) }) sentListOps += op
            }
            val sentFavoriteOps = ArrayList<PendingFavorite>(favoriteOps.size)
            for (op in favoriteOps) {
                if (attemptSend { api.setFavorite(op.animeId, op.favorite) }) sentFavoriteOps += op
            }
            if (sentListOps.isEmpty() && sentFavoriteOps.isEmpty()) return

            synchronized(lock) {
                val library = readLibrary()
                writeLibrary(
                    library.copy(
                        // Новые действия, добавленные во время отправки, отличаются по
                        // содержимому — filterNot их сохранит.
                        pendingListStatus = library.pendingListStatus.filterNot { it in sentListOps },
                        pendingFavorite = library.pendingFavorite.filterNot { it in sentFavoriteOps },
                    ),
                )
            }
        } finally {
            flushMutex.unlock()
        }
    }

    // --- Файл ---

    /** Читает файл; ошибка чтения или битый JSON — пустая библиотека. */
    private fun readLibrary(): LibraryFileDto {
        val file = libraryFile
        if (!file.isFile) return LibraryFileDto()
        return runCatching { json.decodeFromString<LibraryFileDto>(file.readText()) }
            .getOrElse { LibraryFileDto() }
    }

    /**
     * Пишет файл. Пока сохранять нечего и файла нет — не создаёт его; существующий файл
     * перезаписывает даже пустым содержимым, чтобы можно было очистить снимки.
     * Ошибки записи (нет прав, занятый файл) глушатся.
     */
    private fun writeLibrary(library: LibraryFileDto) {
        val file = libraryFile
        if (library.isEmpty && !file.isFile) return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(LibraryFileDto.serializer(), library))
        }
    }

    /**
     * Одна сетевая операция: `true` — сервер принял. Отмену корутины пробрасываем,
     * любую другую ошибку превращаем в `false`, чтобы действие осталось в очереди.
     */
    private suspend fun attemptSend(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (ignored: Exception) {
        false
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000
}

/** [UserListEntry] → запись снимка: для вкладок и бейджей важен [UserListEntry.statusKey]. */
private fun UserListEntry.toStoredListEntry(): StoredListEntry = StoredListEntry(
    id = animeId,
    title = title,
    posterUrl = posterUrl,
    href = statusKey,
    // Личная оценка важнее общей: именно её показывает карточка списка.
    rating = userRating.takeIf { it > 0 } ?: rating,
    year = year,
    typeName = typeName,
)

/** [WatchHistoryItem] → запись снимка; кадр из серии в оффлайн-показе не нужен. */
private fun WatchHistoryItem.toStoredHistory(): StoredHistory = StoredHistory(
    animeId = animeId,
    title = title,
    episode = episode,
    posterUrl = posterUrl,
    watchedAtSec = watchedAtSec,
    positionSec = positionSec,
    durationSec = durationSec,
)
