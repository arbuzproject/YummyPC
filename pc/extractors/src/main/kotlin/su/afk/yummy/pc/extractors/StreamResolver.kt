// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors

/**
 * Контракт экстрактора балансера: iframe-URL -> прямой поток.
 *
 * [resolve] ходит в сеть и выполняется на `Dispatchers.IO`: вызывать из корутины, а не с UI-потока
 * Compose. Ошибки не бросаются наружу, а упаковываются в [Result]: вызывающая сторона различает
 * блокировку Kodik по [KodikBlockedException], остальные отказы — по [KodikResolveException].
 */
interface StreamResolver {

    /** Человекочитаемое имя балансера для логов и UI. */
    val id: String
        get() = this::class.simpleName?.removeSuffix("Resolver").orEmpty()

    /** Умеет ли этот экстрактор разбирать конкретный iframe-URL. */
    fun supports(iframeUrl: String): Boolean

    suspend fun resolve(iframeUrl: String): Result<ResolvedStream>
}
