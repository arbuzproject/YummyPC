// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import su.afk.yummy.pc.extractors.ResolvedStream
import su.afk.yummy.pc.extractors.StreamResolver
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Экстрактор Alloha: iframe-URL балансера -> прямой HLS-поток.
 *
 * Порт `AllohaExtractor` из YummyTV 1:1 по шагам, но без Android-специфики:
 * 1. `GET` страница плеера (Referer сайта-встраивателя) -> зерно `viewporti`, id файла, токен, ключ
 *    `Authorizations` из скрипта сайта.
 * 2. `POST /bnsi/movies/{id}` с подписью `Borth` (fingerprint установки + перестановка `viewporti`) ->
 *    лесенки озвучек, субтитры, тикеты WS/отчётов; у каждого качества проверяется первое зеркало.
 * 3. WebSocket сессии -> первый `edge_hash`, который CDN ждёт в `Accepts-Controls`; HTTP-отчёты
 *    `/events` и `/stat` идут рядом. Сессия остаётся живой после возврата ссылки
 *    ([AllohaResolver.releaseActiveSession] закрывает её).
 * 4. Из лесенки озвучки по умолчанию собирается [ResolvedStream]; заголовки CDN получает внешний плеер.
 *
 * Отличия от Android-версии (осознанные упрощения, см. `docs/alloha.md`):
 * - нет WebView, loopback-прокси и интерцептора заголовков: плееру уходит первый `edge_hash`, ротация
 *   на лету не поддерживается;
 * - позиция для WS и отчётов считается по настенным часам, а не по реальному плееру;
 * - в [ResolvedStream.qualities] попадает лесенка только одной (выбранной по умолчанию) озвучки —
 *   контракт ПК-порта это позволяет.
 *
 * @param userAgent UA браузера для всех запросов и для плеера; из него считаются client hints, а
 *   fingerprint установки строится по тому же UA. По умолчанию — Chrome под текущую ОС.
 * @param onStage необязательный обработчик прогресса (0..N строк) — для CLI/логов.
 */
class AllohaResolver(
    private val userAgent: String = allohaDefaultUserAgent(),
    private val onStage: (String) -> Unit = {},
) : StreamResolver {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Профиль установки читается один раз: fingerprint не должен меняться между запусками. */
    private val clientProfile: AllohaClientProfile by lazy { allohaInstallProfile(userAgent) }

    private val opener: AllohaHttpSessionOpener by lazy {
        AllohaHttpSessionOpener(
            client = http,
            userAgent = userAgent,
            fingerprint = clientProfile.fingerprint,
            onStage = onStage,
        )
    }

    override fun supports(iframeUrl: String): Boolean = iframeUrl.contains("alloha", ignoreCase = true)

    override suspend fun resolve(iframeUrl: String): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        try {
            Result.success(extract(iframeUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AllohaSourceUnavailableException) {
            Result.failure(e)
        } catch (e: AllohaResolveException) {
            Result.failure(e)
        } catch (e: IOException) {
            // Причина в тексте нужна для отчётов: без неё не отличить таймаут от HTTP 403 у bnsi.
            Result.failure(
                AllohaResolveException("Alloha: session was not opened (${e.sessionFailureReason()})", e),
            )
        } catch (e: Exception) {
            Result.failure(
                AllohaResolveException("${e::class.java.simpleName}: ${e.message.orEmpty()}", e),
            )
        }
    }

    private suspend fun extract(iframeUrl: String): ResolvedStream {
        val fullUrl = AllohaPageParsing.normalizeIframeUrl(iframeUrl)
        val opened = openWithRetry(fullUrl)
        val withSocket = withSocket(opened, fullUrl)
        return buildStream(withSocket)
    }

    /**
     * Открывает WebSocket плеера и кладёт выданный им `edge_hash` в заголовки медиа как
     * `Accepts-Controls`. Сокет, который не ответил, сессию не ломает: сырые ссылки играли и без него.
     */
    private suspend fun withSocket(opened: AllohaOpenedSession, iframeUrl: String): AllohaOpenedSession {
        val ticket = opened.socketTicket ?: return opened
        AllohaSessionKeeper.release()
        val tracks = opened.sources.audioTracks
        val track = tracks.firstOrNull { it.isDefault } ?: tracks.first()
        val bestQualityEntry = track.qualities.entries
            .maxByOrNull { it.key.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val resolution = bestQualityEntry?.key?.filter(Char::isDigit).orEmpty().ifEmpty { DEFAULT_RESOLUTION }
        val origin = opened.headers["origin"] ?: iframeUrl
        val clock = AllohaPlaybackClock()
        val socket = AllohaPlaybackSocket(
            client = http,
            userAgent = userAgent,
            origin = origin,
            onLog = onStage,
            positionMs = clock::positionMs,
        )
        val reporter = opened.viewTicket?.let { view ->
            AllohaViewReporter(
                client = http,
                ticket = view,
                iframeUrl = iframeUrl,
                origin = origin,
                userAgent = userAgent,
                profile = clientProfile,
                stream = AllohaPlayedStream(
                    manifestUrl = bestQualityEntry?.value.orEmpty(),
                    audioLabel = track.label,
                    qualityHeight = resolution.toIntOrNull() ?: 0,
                ),
                positionMs = clock::positionMs,
                // Длительности у iframe-ссылки нет: вехи `view_percent` тогда не отправляются, как и в
                // Android-версии при неизвестной длительности.
                durationMs = { 0L },
                onLog = onStage,
            ).also { it.start() }
        }
        val session = AllohaLiveSession(socket = socket, reporter = reporter, onLog = onStage)
        AllohaSessionKeeper.attach(session)
        val hash = try {
            session.open(ticket, resolution = resolution, audioId = track.id)
        } catch (cancelled: CancellationException) {
            // Плеер ушёл, пока сокет открывался: закрыть сессию больше некому.
            session.close()
            throw cancelled
        }
        onStage("ws first edge_hash=${hash != null}")
        return if (hash == null) opened else opened.copy(headers = opened.headers + (AllohaEdgeHash.HEADER to hash))
    }

    /** Один повтор на временный сбой сети; отсутствие источника — финально и не повторяется. */
    private suspend fun openWithRetry(iframeUrl: String): AllohaOpenedSession {
        var lastError: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                onStage("retry ${attempt + 1}/$MAX_ATTEMPTS")
                delay(RETRY_DELAY_MS)
            }
            try {
                return opener.open(iframeUrl)
            } catch (error: AllohaSourceUnavailableException) {
                throw error
            } catch (error: IOException) {
                onStage("open attempt ${attempt + 1}/$MAX_ATTEMPTS failed: ${error.message}")
                lastError = error
            }
        }
        throw checkNotNull(lastError)
    }

    /**
     * Поток, с которого стартует плеер: озвучка по умолчанию (или первая), её лучшее качество. Заголовки
     * CDN отдаются плееру как есть; `x-requested-with` добавляется, как его добавляет WebView.
     */
    private fun buildStream(opened: AllohaOpenedSession): ResolvedStream {
        val tracks = opened.sources.audioTracks
        val track = tracks.firstOrNull { it.isDefault } ?: tracks.first()
        val qualities = track.qualities.entries.map { it.key to it.value }
        val headers = opened.headers + ("x-requested-with" to X_REQUESTED_WITH)
        return ResolvedStream(
            url = qualities.last().second,
            headers = headers,
            qualities = qualities,
        )
    }

    /**
     * Наши ошибки открытия уже несут текст «Alloha: bnsi HTTP 403» — префикс снимаем; у сетевых
     * (OkHttp) добавляем имя класса: оно из `java.io`/`java.net` и не переименовывается.
     */
    private fun IOException.sessionFailureReason(): String {
        val text = message.orEmpty().removePrefix("Alloha: ").trim()
        val ownError = message.orEmpty().startsWith("Alloha: ")
        val reason = when {
            ownError -> text
            text.isEmpty() -> javaClass.simpleName
            else -> "${javaClass.simpleName}: $text"
        }
        return reason.take(MAX_REASON_LENGTH)
    }

    companion object {
        /** Значение `x-requested-with` на медиазапросах: как имя пакета у WebView в Android-версии. */
        const val X_REQUESTED_WITH = "su.afk.yummy.pc"

        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val CALL_TIMEOUT_SECONDS = 90L
        private const val MAX_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 500L
        private const val MAX_REASON_LENGTH = 80
        private const val DEFAULT_RESOLUTION = "1080"

        /**
         * Закрывает живую сессию Alloha (WebSocket и отчёты). Хост вызывает это, когда внешний плеер
         * закрылся; без вызова сессия закроется сама по TTL (45 минут) или на выходе JVM.
         */
        fun releaseActiveSession() {
            AllohaSessionKeeper.release()
        }
    }
}
