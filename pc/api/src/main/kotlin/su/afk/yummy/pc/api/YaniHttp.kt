// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Общий HTTP-слой desktop-клиента.
 *
 * Заголовки: `Lang` (язык контента) и `Authorization: Bearer` после входа в аккаунт.
 * Токены приложения yani (`X-Application`) клиент не отправляет — их подставляет сервер YummyPC.
 */
object YaniHttp {
    /** База запросов: свой сервер (`{backend}/yani`) или прямой api.yani.tv. */
    fun baseUrl(): String = YummyConfig.yaniBaseUrl()

    val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = true
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
            defaultRequest {
                header("Accept", "application/json")
                header(YaniAuthHeaders.LANGUAGE, YummyConfig.languageCode)
                val token = YummyConfig.userToken
                if (token.isNotBlank()) {
                    header(YaniAuthHeaders.AUTHORIZATION, YaniAuthHeaders.BEARER + token)
                }
            }
        }
    }
}