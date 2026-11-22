// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors

import su.afk.yummy.pc.extractors.aksor.aksorResolver
import su.afk.yummy.pc.extractors.alloha.allohaResolver
import su.afk.yummy.pc.extractors.cvh.cvhResolver
import su.afk.yummy.pc.extractors.rutube.rutubeResolver
import su.afk.yummy.pc.extractors.sibnet.sibnetResolver
import su.afk.yummy.pc.extractors.vk.vkResolver
import su.afk.yummy.pc.extractors.zedfilm.zedfilmResolver

/**
 * Реестр экстракторов балансеров.
 *
 * Порядок важен: проверяется по очереди, первый подходящий по `supports` используется.
 * Kodik стоит первым как самый массовый, дальше — остальные iframe-балансеры yani.
 */
object ExtractorRegistry {

    private val resolvers: List<StreamResolver> = listOf(
        KodikResolver(),
        allohaResolver,
        cvhResolver,
        vkResolver,
        rutubeResolver,
        sibnetResolver,
        aksorResolver,
        zedfilmResolver,
    )

    fun all(): List<StreamResolver> = resolvers

    fun ids(): List<String> = resolvers.map { it.id }

    fun resolverFor(iframeUrl: String): StreamResolver? =
        resolvers.firstOrNull { it.supports(iframeUrl) }

    suspend fun resolve(iframeUrl: String): Result<ResolvedStream> =
        resolverFor(iframeUrl)?.resolve(iframeUrl)
            ?: Result.failure(
                UnsupportedBalancerException(
                    "Балансер не поддержан: $iframeUrl (известные: ${ids().joinToString()})",
                ),
            )

    fun supports(iframeUrl: String): Boolean = resolverFor(iframeUrl) != null
}

/** Балансер, для которого ещё нет экстрактора (или ссылка не распознана). */
class UnsupportedBalancerException(message: String) : Exception(message)
