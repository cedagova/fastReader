package com.cedagova.reader.auth.api

import com.cedagova.reader.auth.ReaderAuthConfig
import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.auth.ReaderAuthPolicy
import com.cedagova.reader.auth.RetryWaiter
import com.cedagova.reader.auth.SessionRefresher
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The one thin reader-api client (CONTRACT.md, "reader-api call policy").
 * It knows the three routes the contract needs and exposes [get], [put] and
 * [post] so
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
 * - any other 401 `auth.*` on a protected call: the session is cleared and
 *   [ReaderAuthException.SignedOut] is thrown (a public route's 401 is an
 *   [ReaderAuthException.ApiError]; it said nothing about the session). The
 *   clear waits for any refresh in flight and happens only while the rejected
 *   token is still the stored one; when another caller already replaced it,
 *   the call is retried once with the replacement instead (#153);
 * - 403: [ReaderAuthException.Forbidden], session intact;
 * - 429, 502 `auth.jwks_dependency_failed`, and any 5xx whose body says
 *   `retryable: true` (#158): one retry after `Retry-After` (default 10 s),
 *   then [ReaderAuthException.TryLater]. A `Retry-After` above
 *   [ReaderAuthPolicy.MAX_INLINE_RETRY_AFTER] is not waited out: the call
 *   ends at once with `TryLater` carrying the server's value (#157);
 * - any other failure: [ReaderAuthException.ApiError] with the server's `code`
 *   and `request_id`; a network failure or timeout is
 *   [ReaderAuthException.NetworkUnavailable]. Nothing else is retried.
 */
public class ReaderApiClient internal constructor(
    private val config: ReaderAuthConfig,
    private val http: HttpClient,
    private val refresher: SessionRefresher,
    private val waiter: RetryWaiter,
    private val requestIds: () -> String = { UUID.randomUUID().toString().lowercase() },
) {

    /**
     * `GET /v1/reader/pre-auth?clientVersion=…`: public, called before any
     * sign-in. A 2xx whose object does not decode as the document is an
     * [ReaderAuthException.ApiError] (#191), like a 2xx that is not JSON.
     */
    public suspend fun preAuth(): PreAuthDocument = send(
        HttpMethod.Get,
        PRE_AUTH_PATH,
        authenticated = false,
        clientVersion = true,
        read = PreAuthDocument::parse,
    ).value

    /** `GET /v1/reader/capabilities?clientVersion=…`: the first authenticated call after sign-in. */
    public suspend fun capabilities(): JsonObject = capabilitiesResponse().document

    /**
     * The same call, with the `X-Request-ID` the successful attempt carried
     * beside the document (#100). reader-api echoes that id, so it is the one
     * to quote when reading a server log against what the screen showed. Same
     * request, same headers, same policy as [capabilities]; only the return
     * shape differs.
     */
    public suspend fun capabilitiesResponse(): ReaderApiResponse =
        send(HttpMethod.Get, CAPABILITIES_PATH, authenticated = true, clientVersion = true) { it }
            .let { ReaderApiResponse(it.value, it.requestId) }

    /** `PUT /v1/reader/profile`: the upsert that precedes any profile `GET`. */
    public suspend fun upsertProfile(update: ReaderProfileUpdate): JsonObject = request(
        HttpMethod.Put,
        PROFILE_PATH,
        authenticated = true,
        body = json.encodeToString(ReaderProfileUpdate.serializer(), update),
    )

    /** Any further protected `GET` a host needs, under the same policy. */
    public suspend fun get(path: String): JsonObject = request(HttpMethod.Get, path, authenticated = true)

    /** Any further protected `PUT` a host needs, under the same policy. */
    public suspend fun put(path: String, body: JsonObject): JsonObject =
        request(HttpMethod.Put, path, authenticated = true, body = body.toString())

    /**
     * Any further protected `POST` a host needs, under the same policy (#112).
     *
     * Additive and behaviour-neutral: it is [put] with a different method, so
     * the headers, the timeout, the refresh-and-retry and every error branch
     * above are the same ones [get] and [put] already use, and no existing
     * caller changes. `CONTRACT.md` describes the policy, not the verb list, so
     * it is unchanged. [path] may carry a query string; the caller is
     * responsible for it being a route the published contract declares.
     */
    public suspend fun post(path: String, body: JsonObject): JsonObject =
        request(HttpMethod.Post, path, authenticated = true, body = body.toString())

    private suspend fun request(
        method: HttpMethod,
        path: String,
        authenticated: Boolean,
        clientVersion: Boolean = false,
        body: String? = null,
    ): JsonObject = send(method, path, authenticated, clientVersion, body) { it }.value

    /** A successful answer as [send]'s `read` decoded it, with the id the successful attempt sent. */
    private class Answer<T>(val value: T, val requestId: String)

    /**
     * One call under the policy above. Every attempt — the first, the one after
     * a refresh, the one after `Retry-After` — carries a fresh id, and the id
     * reported is the one the successful attempt sent. [read] decodes the
     * successful JSON object; a body it cannot decode is an
     * [ReaderAuthException.ApiError] with the answer's status.
     */
    private suspend fun <T> send(
        method: HttpMethod,
        path: String,
        authenticated: Boolean,
        clientVersion: Boolean = false,
        body: String? = null,
        read: (JsonObject) -> T,
    ): Answer<T> {
        var refreshed = 0
        var waited = 0
        while (true) {
            val session = if (authenticated) {
                refresher.sessionForRequest() ?: throw ReaderAuthException.SignedOut(code = null)
            } else {
                null
            }
            val requestId = requestIds()
            val response = try {
                http.request(config.readerApiOrigin + path) {
                    this.method = method
                    header(HEADER_CLIENT, config.clientId)
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    header(HEADER_REQUEST_ID, requestId)
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
            if (response.status.isSuccess()) return Answer(response.decoded(read), requestId)

            val error = ErrorBody.of(response)
            when {
                response.status == HttpStatusCode.Unauthorized -> {
                    if (error.code == ReaderAuthPolicy.EXPIRED_TOKEN_CODE && session != null &&
                        refreshed < ReaderAuthPolicy.RETRY_LIMIT
                    ) {
                        refreshed += 1
                        refresher.refreshAfterRejection(session.accessToken)
                            ?: throw ReaderAuthException.SignedOut(error.code, error.requestId)
                        continue
                    }
                    if (session != null && error.code != ReaderAuthPolicy.EXPIRED_TOKEN_CODE &&
                        error.code?.startsWith(AUTH_CODE_PREFIX) == true
                    ) {
                        if (refresher.clearAfterRejection(session.accessToken)) {
                            throw ReaderAuthException.SignedOut(error.code, error.requestId)
                        }
                        // Another caller already replaced the rejected token (a refresh or a
                        // sign-in): the rejection is stale, so retry once with the replacement.
                        if (refreshed < ReaderAuthPolicy.RETRY_LIMIT) {
                            refreshed += 1
                            continue
                        }
                    }
                    throw ReaderAuthException.ApiError(
                        response.status.value,
                        error.code,
                        error.requestId,
                        error.message,
                    )
                }

                response.status == HttpStatusCode.Forbidden ->
                    throw ReaderAuthException.Forbidden(error.code, error.requestId)

                response.status == HttpStatusCode.TooManyRequests ||
                    (
                        response.status == HttpStatusCode.BadGateway &&
                            error.code == ReaderAuthPolicy.JWKS_DEPENDENCY_FAILED_CODE
                        ) ||
                    (response.status.value >= SERVER_ERROR && error.retryable) -> {
                    val retryAfter = ReaderAuthPolicy.retryAfter(response.headers[HttpHeaders.RetryAfter])
                    if (waited < ReaderAuthPolicy.RETRY_LIMIT && ReaderAuthPolicy.retriesInline(retryAfter)) {
                        waited += 1
                        waiter.wait(retryAfter)
                        continue
                    }
                    throw ReaderAuthException.TryLater(response.status.value, error.code, retryAfter, error.requestId)
                }

                else -> throw ReaderAuthException.ApiError(
                    response.status.value,
                    error.code,
                    error.requestId,
                    error.message,
                )
            }
        }
    }

    private suspend fun <T> HttpResponse.decoded(read: (JsonObject) -> T): T {
        val text = bodyAsText()
        val document = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ReaderAuthException.ApiError(
                status.value,
                null,
                headers[HEADER_REQUEST_ID],
                "response is not a JSON object",
            )
        }
        return try {
            read(document)
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization's SerializationException is an IllegalArgumentException.
            throw ReaderAuthException.ApiError(
                status.value,
                null,
                headers[HEADER_REQUEST_ID],
                "response is not the expected document",
            )
        }
    }

    /**
     * reader-api's `ErrorResponse`: `code`, `message`, `category`, `retryable`,
     * `request_id`. [retryable] is the server's own verdict and is `false`
     * when the field is missing or not a boolean; [category] is read for
     * completeness and drives nothing. A field that is an object, an array or
     * `null` counts as absent (#191), so an unexpected error body — a proxy's
     * own JSON, say — still leaves the status policy to decide the branch.
     */
    private class ErrorBody(
        val code: String?,
        val message: String,
        val requestId: String?,
        val category: String? = null,
        val retryable: Boolean = false,
    ) {
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
                    code = body.text("code"),
                    message = body.text("message") ?: text.take(MAX_MESSAGE),
                    requestId = body.text("request_id") ?: fromHeader,
                    category = (body["category"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    retryable =
                        (body["retryable"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false,
                )
            }
        }
    }

    public companion object {
        /** [key] as text when it is a JSON scalar; an object, an array, `null` or a missing key is `null`. */
        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

        public const val PRE_AUTH_PATH: String = "/v1/reader/pre-auth"
        public const val CAPABILITIES_PATH: String = "/v1/reader/capabilities"
        public const val PROFILE_PATH: String = "/v1/reader/profile"
        public const val HEADER_CLIENT: String = "X-Reader-Client"
        public const val HEADER_REQUEST_ID: String = "X-Request-ID"
        public const val QUERY_CLIENT_VERSION: String = "clientVersion"
        private const val AUTH_CODE_PREFIX = "auth."
        private const val MAX_MESSAGE = 200
        private const val SERVER_ERROR = 500

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
