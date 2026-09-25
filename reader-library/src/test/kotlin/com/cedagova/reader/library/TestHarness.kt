package com.cedagova.reader.library

import com.cedagova.reader.auth.ReaderAuthClient
import com.cedagova.reader.auth.ReaderAuthConfig
import com.cedagova.reader.auth.ReaderClock
import com.cedagova.reader.auth.RetryWaiter
import com.cedagova.reader.auth.session.SessionStore
import com.cedagova.reader.auth.session.StoredSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// The seams :reader-library's tests substitute. There is deliberately no fake
// of ReaderApiClient here: every test below drives a REAL ReaderAuthClient —
// real bearer, real single-flight refresh, real 401/403/429/502 policy — over
// one Ktor mock engine, because the point of these tests is that the module's
// operations inherit :reader-auth's behaviour rather than restate it.

const val API_URL = "https://api.test"
const val PROVIDER_URL = "https://provider.test"
const val PUBLISHABLE_KEY = "sb_publishable_test_key"

val testConfig = ReaderAuthConfig(
    supabaseUrl = PROVIDER_URL,
    publishableKey = PUBLISHABLE_KEY,
    readerApiBaseUrl = API_URL,
)

class FakeClock(var now: Instant = Clock.System.now()) : ReaderClock {
    override fun now(): Instant = now
}

class RecordingWaiter : RetryWaiter {
    val waits = mutableListOf<Duration>()
    override suspend fun wait(duration: Duration) {
        waits += duration
    }
}

class InMemorySessionStore(initial: StoredSession? = null) : SessionStore {
    @Volatile var session: StoredSession? = initial
    override suspend fun save(session: StoredSession) {
        this.session = session
    }
    override suspend fun load(): StoredSession? = session
    override suspend fun clear() {
        session = null
    }
}

fun session(accessToken: String = "access-1", refreshToken: String = "refresh-1", expiresAt: Instant) =
    StoredSession.forTests(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = expiresAt,
        userId = "user-1",
        email = "reader@example.test",
    )

fun sessionJson(accessToken: String, refreshToken: String) = """
    {"access_token":"$accessToken","refresh_token":"$refreshToken","token_type":"bearer","expires_in":3600,
     "user":{"id":"user-1","aud":"authenticated","email":"reader@example.test"}}
""".trimIndent()

/** reader-api's `ErrorResponse` shape, as the call policy reads it. */
fun apiError(code: String, requestId: String = "req-1", retryable: Boolean = false) =
    """{"code":"$code","message":"$code","category":"auth","retryable":$retryable,"request_id":"$requestId"}"""

/** One request as the fake server saw it. */
data class Recorded(
    val method: String,
    val host: String,
    val path: String,
    val query: String,
    val headers: Map<String, String>,
    val body: String,
) {
    val bearer: String? get() = headers["Authorization"]?.removePrefix("Bearer ")
    val route: String get() = "$method $path" + if (query.isEmpty()) "" else "?$query"
}

typealias Responder = suspend MockRequestHandleScope.(Recorded) -> HttpResponseData

/**
 * Plays reader-api (and the identity provider's refresh grant) behind one
 * [MockEngine]. Each route has a queue of responders; the last one repeats, so
 * a test states only the answers that matter. A route is matched on
 * `METHOD /path?query` first and on `METHOD /path` second, so a test can pin an
 * exact query string when the query is what it is proving.
 */
class FakeServers {
    val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())
    private val routes = mutableMapOf<String, ArrayDeque<Responder>>()

    val engine = MockEngine { data ->
        val recorded = data.record()
        requests += recorded
        val queue = routes[recorded.route] ?: routes["${recorded.method} ${recorded.path}"]
            ?: error("unexpected ${recorded.route}")
        val responder = if (queue.size > 1) queue.removeFirst() else queue.first()
        responder(this, recorded)
    }

    fun on(route: String, responder: Responder) {
        routes[route] = ArrayDeque(listOf(responder))
    }

    fun queue(route: String, vararg responders: Responder) {
        routes[route] = ArrayDeque(responders.toList())
    }

    fun requestsTo(path: String): List<Recorded> = requests.filter { it.path == path }
    fun routes(): List<String> = requests.map { it.route }

    private suspend fun HttpRequestData.record(): Recorded = Recorded(
        method = method.value,
        host = url.host,
        path = url.encodedPath,
        query = url.encodedQuery,
        headers = headers.entries().associate { (k, v) -> k to v.joinToString(",") } +
            (body.contentType?.let { mapOf(HttpHeaders.ContentType to it.toString()) } ?: emptyMap()),
        body = try {
            body.toByteArray().decodeToString()
        } catch (e: Exception) {
            ""
        },
    )
}

fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    vararg extraHeaders: Pair<String, String>,
): HttpResponseData {
    val pairs = listOf(HttpHeaders.ContentType to "application/json") + extraHeaders
    return respond(body, status, headersOf(*pairs.map { (k, v) -> k to listOf(v) }.toTypedArray()))
}

fun networkFailure(): Nothing = throw IOException("connection refused")

// The routes these tests use.
const val LIBRARY = "GET /v1/reader/library"
const val PROGRESS = "GET /v1/reader/progress"
const val MUTATIONS = "POST /v1/reader/sync/mutations"
const val DELTAS = "GET /v1/reader/sync/deltas"
const val CAPABILITIES = "GET /v1/reader/capabilities?clientVersion=1.0.0"
const val REFRESH_GRANT = "POST /auth/v1/token?grant_type=refresh_token"

/**
 * One signed-in client whose transport is [servers], returned as the module's
 * operations. `close()` on the returned handle releases the SDK's client.
 */
class Harness(
    val servers: FakeServers = FakeServers(),
    val clock: FakeClock = FakeClock(),
    val waiter: RecordingWaiter = RecordingWaiter(),
    /** False for the one test that proves a signed-out client never calls out. */
    signedIn: Boolean = true,
) {
    val store = InMemorySessionStore(
        if (signedIn) session(expiresAt = clock.now + 3600.seconds) else null,
    )

    private var auth: ReaderAuthClient? = null

    suspend fun operations(): ReaderLibraryClient {
        val client = ReaderAuthClient.createForTests(testConfig, store, servers.engine, clock, waiter)
        client.awaitReady()
        auth = client
        return ReaderLibraryClient(client.api)
    }

    suspend fun close() {
        auth?.close()
    }
}

/** The repository root, for the test that reads the committed contract document. */
fun repositoryRoot(): File {
    var candidate: File? = File("").absoluteFile
    while (candidate != null) {
        if (File(candidate, "settings.gradle.kts").isFile) return candidate
        candidate = candidate.parentFile
    }
    error("no settings.gradle.kts above ${File("").absoluteFile}")
}
