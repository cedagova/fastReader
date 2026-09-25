package com.cedagova.reader.auth.testing

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

// The one mock-engine harness of the Reader libraries (#199, A197-F002): the
// identity provider and reader-api behind one Ktor MockEngine, used by this
// module's tests, :reader-library's and a host's.
//
// MockEngine answers on its own dispatcher threads, not on the runTest
// scheduler, so anything a responder touches must be thread-safe, and a test
// that needs callers parked must wait on a signal from the responders
// ([Arrivals]), never on yield(), which only advances the test scheduler.

/** One request as a fake server saw it. */
public data class Recorded(
    val method: String,
    val host: String,
    val path: String,
    val query: String,
    val headers: Map<String, String>,
    val body: String,
) {
    /** The bearer token the request carried, without its scheme. */
    val bearer: String? get() = headers["Authorization"]?.removePrefix("Bearer ")

    /** `METHOD /path?query`, as [FakeServers.on] matches it. */
    val route: String get() = "$method $path" + if (query.isEmpty()) "" else "?$query"
}

/** How a fake server answers one request. */
public typealias Responder = suspend MockRequestHandleScope.(Recorded) -> HttpResponseData

/**
 * Plays the identity provider ([PROVIDER_URL]) and reader-api ([API_URL])
 * behind one [MockEngine]. Each route has a queue of responders; the last one
 * repeats, so a test states only the answers that matter. A route is matched
 * on `METHOD /path?query` first and on `METHOD /path` second, so a test can
 * pin an exact query string when the query is what it is proving. A request no
 * route answers fails the call with "unexpected <route>".
 */
public class FakeServers {
    /** Every request, in arrival order. */
    public val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())

    // Guarded by itself: responders are picked on engine threads.
    private val routes = mutableMapOf<String, ArrayDeque<Responder>>()

    /** The engine a client under test is built over. */
    public val engine: MockEngine = MockEngine { data ->
        val recorded = data.recorded()
        requests += recorded
        val responder = synchronized(routes) {
            val queue = routes[recorded.route] ?: routes["${recorded.method} ${recorded.path}"]
                ?: error("unexpected ${recorded.route}")
            if (queue.size > 1) queue.removeFirst() else queue.first()
        }
        responder(this, recorded)
    }

    /** The answer for [route]; a later call replaces it. */
    public fun on(route: String, responder: Responder) {
        synchronized(routes) { routes[route] = ArrayDeque(listOf(responder)) }
    }

    /** Successive answers for [route]; the last one repeats. */
    public fun queue(route: String, vararg responders: Responder) {
        synchronized(routes) { routes[route] = ArrayDeque(responders.toList()) }
    }

    /** The requests whose path is [path], in arrival order. */
    public fun requestsTo(path: String): List<Recorded> = requests.filter { it.path == path }

    /** Every request's [Recorded.route], in arrival order. */
    public fun routes(): List<String> = requests.map { it.route }
}

/**
 * This request as a [Recorded]: headers joined per name, plus the body's
 * content type, and the body as text. For a fake server of a host's own (a
 * storage provider, say) that records requests the way [FakeServers] does.
 * [withBody] false records an empty body, for a server that reads a binary
 * or streamed body itself and must not consume it here.
 */
public suspend fun HttpRequestData.recorded(withBody: Boolean = true): Recorded = Recorded(
    method = method.value,
    host = url.host,
    path = url.encodedPath,
    query = url.encodedQuery,
    headers = headers.entries().associate { (k, v) -> k to v.joinToString(",") } +
        (body.contentType?.let { mapOf(HttpHeaders.ContentType to it.toString()) } ?: emptyMap()),
    body = if (!withBody) {
        ""
    } else {
        try {
            body.toByteArray().decodeToString()
        } catch (e: Exception) {
            ""
        }
    },
)

/** A JSON answer with [status] and any [extraHeaders]. */
public fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    vararg extraHeaders: Pair<String, String>,
): HttpResponseData {
    val pairs = listOf(HttpHeaders.ContentType to "application/json") + extraHeaders
    return respond(body, status, headersOf(*pairs.map { (k, v) -> k to listOf(v) }.toTypedArray()))
}

/** What a responder throws to play a connection that never reached the server. */
public fun networkFailure(): Nothing = throw IOException("connection refused")

/**
 * A count of events seen on engine threads that a test can suspend on:
 * [await] resumes once [arrive] has been called [expected] times.
 */
public class Arrivals(private val expected: Int) {
    private val count = AtomicInteger()
    private val reached = CompletableDeferred<Unit>()

    public fun arrive() {
        if (count.incrementAndGet() == expected) reached.complete(Unit)
    }

    public suspend fun await(): Unit = reached.await()
}

/**
 * A responder that parks until [gate] completes, so a test can prove callers
 * were waiting concurrently. [entered], when given, is told as each request
 * reaches the gate.
 */
public fun gated(gate: CompletableDeferred<Unit>, entered: Arrivals? = null, then: Responder): Responder = { recorded ->
    entered?.arrive()
    gate.await()
    then(this, recorded)
}

// Identity-provider routes as the provider SDK addresses them.
public const val OTP: String = "POST /auth/v1/otp"
public const val VERIFY: String = "POST /auth/v1/verify"
public const val PASSWORD_GRANT: String = "POST /auth/v1/token?grant_type=password"
public const val REFRESH_GRANT: String = "POST /auth/v1/token?grant_type=refresh_token"
public const val RECOVER: String = "POST /auth/v1/recover"
public const val USER: String = "PUT /auth/v1/user"
public const val LOGOUT_LOCAL: String = "POST /auth/v1/logout?scope=local"
public const val LOGOUT_OTHERS: String = "POST /auth/v1/logout?scope=others"

// The reader-api routes the contract names, as the client addresses them.
public const val PRE_AUTH: String = "GET /v1/reader/pre-auth?clientVersion=1.0.0"
public const val CAPABILITIES: String = "GET /v1/reader/capabilities?clientVersion=1.0.0"
public const val PROFILE: String = "PUT /v1/reader/profile"
