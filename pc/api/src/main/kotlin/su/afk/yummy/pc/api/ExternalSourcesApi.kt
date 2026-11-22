// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable

/** Внешний источник, который резолвит сервер YummyPC (AniLibria и другие). */
data class ExternalSource(
    val source: String,
    val dubbing: String,
    val animeId: Int,
    val releaseId: Int?,
    val title: String,
    val episode: String,
    val url: String,
    val qualities: List<ExternalQuality>,
) {
    val label: String
        get() = if (qualities.isEmpty()) dubbing else "$dubbing · ${qualities.last().quality}"
}

data class ExternalQuality(
    val quality: String,
    val url: String,
)

@Serializable
data class ExternalSourceDto(
    val source: String = "",
    val dubbing: String = "",
    val anime_id: Int = 0,
    val release_id: Int = 0,
    val title: String = "",
    val episode: String = "",
    val url: String = "",
    val qualities: List<ExternalQualityDto> = emptyList(),
)

@Serializable
data class ExternalQualityDto(
    val quality: String = "",
    val url: String = "",
)

/**
 * Запросы к серверу YummyPC: внешние балансеры, которые требуют серверного резолва.
 *
 * Сервер знает индекс AniLibria и сопоставляет тайтл yani по MAL id или названию,
 * поэтому клиенту не нужно хранить ключи и парсить чужие API.
 */
class ExternalSourcesApi(private val client: HttpClient = YaniHttp.client) {

    suspend fun sources(animeId: Int, episode: Int): List<ExternalSource> {
        val base = YummyConfig.backendUrl
        if (base.isBlank()) return emptyList()
        val dto = client.get("$base/v1/sources/$animeId/$episode").body<ExternalSourceDto>()
        if (dto.url.isBlank()) return emptyList()
        return listOf(
            ExternalSource(
                source = dto.source,
                dubbing = dto.dubbing.ifBlank { dto.source },
                animeId = dto.anime_id,
                releaseId = dto.release_id.takeIf { it > 0 },
                title = dto.title,
                episode = dto.episode,
                url = dto.url,
                qualities = dto.qualities.map { ExternalQuality(it.quality, it.url) },
            ),
        )
    }
}
