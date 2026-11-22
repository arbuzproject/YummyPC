// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Ошибка авторизации с текстом от сервера. */
class AuthException(message: String, val code: Int? = null) : Exception(message)

/** Пользователь yani, как его показывает клиент. */
data class UserProfile(
    val id: Int,
    val nickname: String,
    val about: String,
    val avatarUrl: String?,
    val bannerUrl: String? = null,
    val birthday: String? = null,
    val sex: String? = null,
    val roles: List<String> = emptyList(),
    val banned: Boolean = false,
    val lastOnlineSec: Long? = null,
)

/** Последняя просмотренная серия (для «Продолжить просмотр»). */
data class ContinueWatchingItem(
    val animeId: Int,
    val title: String,
    val posterUrl: String?,
    val episode: String,
    val endTimeSec: Int,
    val durationSec: Int,
) {
    val progress: Float
        get() = if (durationSec > 0) (endTimeSec.toFloat() / durationSec).coerceIn(0f, 1f) else 0f
}

/**
 * Авторизация и профиль yani.
 *
 * Вход: `POST /profile/login {login, password, need_json:true}` → `response.token`.
 * Токен уходит в `Authorization: Bearer <token>`; раз в 2–3 дня его можно обновить
 * через `GET /profile/token`.
 */
class YaniAuthApi(private val client: HttpClient = YaniHttp.client) {

    suspend fun login(login: String, password: String, captchaResponse: String? = null): Result<String> {
        val response: HttpResponse = try {
            client.post("${YaniHttp.baseUrl()}/profile/login") {
                contentType(ContentType.Application.Json)
                setBody(YaniLoginBodyDto(login = login, password = password, captchaResponse = captchaResponse))
            }
        } catch (e: Exception) {
            val body = runCatching { (e as? io.ktor.client.plugins.ClientRequestException)?.response?.bodyAsText() }
                .getOrNull()
            return Result.failure(AuthException(parseError(body) ?: (e.message ?: "Не удалось войти"), code = null))
        }
        if (!response.status.isSuccess()) {
            return Result.failure(AuthException(parseError(response.bodyAsText()) ?: "Не удалось войти", response.status.value))
        }
        val token = response.body<YaniLoginResponseDto>().response.token
        if (token.isBlank()) return Result.failure(AuthException("Сервер не вернул токен", null))
        YummyConfig.updateTokens(userToken = token)
        return Result.success(token)
    }

    /** Продление токена: раз в 2–3 дня, иначе сессия отвалится. */
    suspend fun refreshToken(): Result<String> = runCatching {
        val response: HttpResponse = client.get("${YaniHttp.baseUrl()}/profile/token")
        if (response.status == HttpStatusCode.Unauthorized) error("Сессия истекла — войдите заново")
        val token = response.body<YaniTokenResponseDto>().response.token
        if (token.isBlank()) error("Сервер не вернул токен")
        YummyConfig.updateTokens(userToken = token)
        token
    }

    suspend fun profile(): UserProfile {
        val dto = client.get("${YaniHttp.baseUrl()}/profile").body<YaniProfileResponseDto>().response
        return UserProfile(
            id = dto.id,
            nickname = dto.nickname,
            about = dto.about,
            avatarUrl = (dto.avatars.big ?: dto.avatars.full ?: dto.avatars.small).absUrl(),
            bannerUrl = (dto.banner?.cropped ?: dto.banner?.full).absUrl(),
            birthday = dto.bdate,
            sex = when (dto.sex) {
                1 -> "Мужской"
                2 -> "Женский"
                else -> null
            },
            roles = dto.roles,
            banned = dto.banned,
            lastOnlineSec = dto.lastOnline,
        )
    }

    suspend fun logout(): Result<Unit> = runCatching {
        runCatching { client.post("${YaniHttp.baseUrl()}/profile/logout") }
        YummyConfig.clearSession()
    }

    /** «Продолжить просмотр» с сервера (нужна авторизация). */
    suspend fun lastWatches(): List<ContinueWatchingItem> {
        val raw = client.get("${YaniHttp.baseUrl()}/video/last-watches").bodyAsText()
        val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return emptyList()
        val items = root["response"]?.let { element ->
            when {
                element is JsonObject -> (element["items"] ?: element) as? kotlinx.serialization.json.JsonArray
                else -> element as? kotlinx.serialization.json.JsonArray
            }
        } ?: return emptyList()
        return items.mapNotNull { node ->
            val obj = node as? JsonObject ?: return@mapNotNull null
            val animeId = obj["anime_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.content.orEmpty()
            val poster = obj["poster"] as? JsonObject
            val posterUrl = when {
                poster == null -> null
                else -> (poster["big"] ?: poster["medium"] ?: poster["small"])?.jsonPrimitive?.content
            }
            ContinueWatchingItem(
                animeId = animeId,
                title = title,
                posterUrl = posterUrl.absUrl(),
                episode = obj["episode"]?.jsonPrimitive?.content
                    ?: obj["number"]?.jsonPrimitive?.content
                    ?: obj["ep_title"]?.jsonPrimitive?.content.orEmpty(),
                endTimeSec = obj["end_time"]?.jsonPrimitive?.content?.toFloatOrNull()?.toInt() ?: 0,
                durationSec = obj["duration"]?.jsonPrimitive?.content?.toFloatOrNull()?.toInt() ?: 0,
            )
        }
    }

    private fun parseError(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val element = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return body.take(200)
        val error = element["error"]?.jsonPrimitive?.content
        val title = element["error_title"]?.jsonPrimitive?.content
        return (error ?: title)?.takeIf { it.isNotBlank() }
    }
}