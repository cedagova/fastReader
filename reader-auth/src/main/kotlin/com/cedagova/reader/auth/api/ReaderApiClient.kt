package com.cedagova.reader.auth.api

import com.cedagova.reader.auth.ReaderAuthConfig
import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.auth.ReaderAuthPolicy
import com.cedagova.reader.auth.RetryWaiter
import com.cedagova.reader.auth.SessionRefresher
import io.github.jan.supabase.auth.Auth
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The one thin reader-api client (CONTRACT.md, "reader-api call policy").
 * It knows the three routes the contract needs and exposes [get] and [put] so
 * a host reuses the same headers, timeout, refresh, and error policy for every
 * further route. `ReaderApiPolicyTest` pins each branch.
 *
 * Every request carries `X-Reader-Client`, `Accept: application/json`, a fresh
 * lowercase-UUID `X-Request-ID`, a 10 s timeout, and — for a protected route —
 * `Authorization: Bearer` with a session that was refreshed first when inside
 * the margin. Responses:
 *
 * - 401 `auth.expired_token`: one single-flight refresh and one retry, then
 *   surfaced as [ReaderAuthException.ApiError] with the session intact;
 * - any other 401 `auth.*`: the session is cleared and
 *   [ReaderAuthException.SignedOut] is thrown;
 * - 403: [ReaderAuthException.Forbidden], session intact;
 * - 429, and 502 `auth.jwks_dependency_failed`: one retry after `Retry-After`
 *   (default 10 s), then [ReaderAuthException.TryLater];
 * - any other failure: [ReaderAuthException.ApiError] with the server's `code`
 *   and `request_id`; a network failure or timeout is
 *   [ReaderAuthException.NetworkUnavailable]. Nothing else is retried.
 */
class ReaderApiClient internal constructor(
    private val config: ReaderAuthConfig,
    private val http: HttpClient,
    private val auth: Auth,
    private val refresher: SessionRefresher,
    private val waiter: RetryWaiter,
    private val requestIds: () -> String = { UUID.randomUUID().toString().lowercase() },
) {

    /** `GET /v1/reader/pre-auth?clientVersion=…`: public, called before any sign-in. */
    suspend fun preAuth(): PreAuthDocument =
        PreAuthDocument.parse(request(HttpMethod.Get, PRE_AUTH_PATH, authenticated = false, clientVersion = true))

    /** `GET /v1/reader/capabilities?clientVersion=…`: the first authenticated call after sign-in. */
    suspend fun capabilities(): JsonObject =
        request(HttpMethod.Get, CAPABILITIES_PATH, authenticated = true, clientVersion = true)

    /** `PUT /v1/reader/profile`: the upsert that precedes any profile `GET`. */
    suspend fun upsertProfile(update: ReaderProfileUpdate): JsonObject =
        request(HttpMethod.Put, PROFILE_PATH, authenticated = true, body = json.encodeToString(ReaderProfileUpdate.serializer(), update))

    /** Any further protected `GET` a host needs, under the same policy. */
    suspend fun get(path: String): JsonObject = request(HttpMethod.Get, path, authenticated = true)

    /** Any further protected `PUT` a host needs, under the same policy. */
    suspend fun put(path: String, body: JsonObject): JsonObject =
        request(HttpMethod.Put, path, authenticated = true, body = body.toString())

    private suspend fun request(
        method: HttpMethod,
        path: String,
        authenticated: Boolean,
        clientVersion: Boolean = false,
        body: String? = null,
    ): JsonObject {
        var refreshed = 0
        var waited = 0
        while (true) {
            val session = if (authenticated) {
                refresher.sessionForRequest() ?: throw ReaderAuthException.SignedOut(code = null)
            } else null
            val response = try {
                http.request(config.readerApiOrigin + path) {
                    this.method = method
                    header(HEADER_CLIENT, config.clientId)
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    header(HEADER_REQUEST_ID, requestIds())
                    session?.let { header(HttpHeaders.Authorization, "Bearer ${it.accessToken}") }
                    if (clientVersion) parameter(QUERY_CLIENT_VERSION, config.clientVersion)
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw ReaderAuthException.NetworkUnavailable(e)
            }
            if (response.status.isSuccess()) return response.jsonBody()

            val error = ErrorBody.of(response)
            when {
                response.status == HttpStatusCode.Unauthorized -> {
                    if (error.code == ReaderAuthPolicy.EXPIRED_TOKEN_CODE && session != null && refreshed < ReaderAuthPolicy.RETRY_LIMIT) {
                        refreshed += 1
                        refresher.refreshAfterRejection(session.accessToken)
                            ?: throw ReaderAuthException.SignedOut(error.code, error.requestId)
                        continue
                    }
                    if (error.code != ReaderAuthPolicy.EXPIRED_TOKEN_CODE && error.code?.startsWith(AUTH_CODE_PREFIX) == true) {
                        auth.clearSession()
                        throw ReaderAuthException.SignedOut(error.code, error.requestId)
                    }
                    throw ReaderAuthException.ApiError(response.status.value, error.code, error.requestId, error.message)
                }
                response.status == HttpStatusCode.Forbidden ->
                    throw ReaderAuthException.Forbidden(error.code, error.requestId)
                response.status == HttpStatusCode.TooManyRequests ||
                    (response.status == HttpStatusCode.BadGateway && error.code == ReaderAuthPolicy.JWKS_DEPENDENCY_FAILED_CODE) -> {
                    val retryAfter = ReaderAuthPolicy.retryAfter(response.headers[HttpHeaders.RetryAfter])
                    if (waited < ReaderAuthPolicy.RETRY_LIMIT) {
                        waited += 1
                        waiter.wait(retryAfter)
                        continue
                    }
                    throw ReaderAuthException.TryLater(response.status.value, error.code, retryAfter, error.requestId)
                }
                else -> throw ReaderAuthException.ApiError(response.status.value, error.code, error.requestId, error.message)
            }
        }
    }

    private suspend fun HttpResponse.jsonBody(): JsonObject {
        val text = bodyAsText()
        return try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ReaderAuthException.ApiError(status.value, null, headers[HEADER_REQUEST_ID], "response is not a JSON object")
        }
    }

    /** reader-api's `ErrorResponse`: `code`, `message`, `category`, `retryable`, `request_id`. */
    private class ErrorBody(val code: String?, val message: String, val requestId: String?) {
        companion object {
            suspend fun of(response: HttpResponse): ErrorBody {
                val text = response.bodyAsText()
                val fromHeader = response.headers[HEADER_REQUEST_ID]
                val body = try {
                    json.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    return ErrorBody(null, text.take(MAX_MESSAGE), fromHeader)
                }
                return ErrorBody(
                    code = body["code"]?.jsonPrimitive?.content,
                    message = body["message"]?.jsonPrimitive?.content ?: text.take(MAX_MESSAGE),
                    requestId = body["request_id"]?.jsonPrimitive?.content ?: fromHeader,
                )
            }
        }
    }

    companion object {
        const val PRE_AUTH_PATH: String = "/v1/reader/pre-auth"
        const val CAPABILITIES_PATH: String = "/v1/reader/capabilities"
        const val PROFILE_PATH: String = "/v1/reader/profile"
        const val HEADER_CLIENT: String = "X-Reader-Client"
        const val HEADER_REQUEST_ID: String = "X-Request-ID"
        const val QUERY_CLIENT_VERSION: String = "clientVersion"
        private const val AUTH_CODE_PREFIX = "auth."
        private const val MAX_MESSAGE = 200

        private val json = Json { ignoreUnknownKeys = true }

        /** The Ktor client every reader-api request goes through: the contract's timeout, nothing else. */
        internal fun httpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout) {
                val millis = ReaderAuthPolicy.REQUEST_TIMEOUT.inWholeMilliseconds
                requestTimeoutMillis = millis
                connectTimeoutMillis = millis
                socketTimeoutMillis = millis
            }
        }
    }
}
