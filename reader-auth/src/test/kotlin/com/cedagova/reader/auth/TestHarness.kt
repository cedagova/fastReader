package com.cedagova.reader.auth

import com.cedagova.reader.auth.session.SessionCipher
import com.cedagova.reader.auth.session.SessionStore
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred

// The seams the unit tests substitute: a reversible fake cipher, a settable
// clock, a waiter that records instead of sleeping, an in-memory store, and
// one mock engine that plays both the identity provider and reader-api.
//
// MockEngine answers on its own dispatcher threads, not on the runTest
// scheduler, so anything a responder touches must be thread-safe, and a test
// that needs callers parked must wait on a signal from the responders
// ([Arrivals]), never on yield(), which only advances the test scheduler.

/** XORs every byte; reversible, and guarantees no plaintext byte survives unchanged. */
class FakeCipher(private val key: Byte = 0x5A, var failDecrypt: Boolean = false) : SessionCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray = ByteArray(plaintext.size) {
        (
            plaintext[it].toInt() xor
                key.toInt()
            ).toByte()
    }
    override fun decrypt(blob: ByteArray): ByteArray {
        if (failDecrypt) throw java.security.GeneralSecurityException("key lost")
        return encrypt(blob)
    }
}

class FakeClock(var now: Instant = Clock.System.now()) : ReaderClock {
    override fun now(): Instant = now
    fun advance(by: Duration) {
        now += by
    }
}

class RecordingWaiter : RetryWaiter {
    val waits = mutableListOf<Duration>()
    override suspend fun wait(duration: Duration) {
        waits += duration
    }
}

class InMemorySessionStore(initial: UserSession? = null) : SessionStore {
    @Volatile var session: UserSession? = initial
    val saves = mutableListOf<UserSession>()
    override suspend fun save(session: UserSession) {
        this.session = session
        saves += session
    }
    override suspend fun load(): UserSession? = session
    override suspend fun clear() {
        session = null
    }
}

const val SUPABASE_URL = "https://provider.test"
const val PUBLISHABLE_KEY = "sb_publishable_test_key"
const val API_URL = "https://api.test"

val testConfig =
    ReaderAuthConfig(supabaseUrl = SUPABASE_URL, publishableKey = PUBLISHABLE_KEY, readerApiBaseUrl = API_URL)

fun user(id: String = "user-1", email: String = "reader@example.test") =
    UserInfo(aud = "authenticated", id = id, email = email)

fun session(
    accessToken: String = "access-1",
    refreshToken: String = "refresh-1",
    expiresAt: Instant,
    user: UserInfo? = user(),
) = UserSession(
    accessToken = accessToken,
    refreshToken = refreshToken,
    expiresIn = 3600,
    tokenType = "bearer",
    user = user,
    expiresAt = expiresAt,
)

fun sessionJson(
    accessToken: String,
    refreshToken: String,
    userId: String = "user-1",
    email: String = "reader@example.test",
) = """
    {"access_token":"$accessToken","refresh_token":"$refreshToken","token_type":"bearer","expires_in":3600,
     "user":{"id":"$userId","aud":"authenticated","email":"$email"}}
""".trimIndent()

fun preAuthJson(
    applicationId: String = ReaderAuthPolicy.CLIENT_ID,
    publicClientId: String = PUBLISHABLE_KEY,
    authorityOrigin: String = SUPABASE_URL,
    status: String = "compatible",
    enabledProviders: List<String> = listOf("password"),
    emailOtp: Boolean = true,
    availability: String = "available",
    reason: String = "available",
    retryable: Boolean = false,
    generatedAt: String = PRE_AUTH_GENERATED_AT,
    freshUntil: String = "2026-09-11T01:00:00Z",
    staleUntil: String = "2026-09-11T02:00:00Z",
    validUntil: String = "2026-12-01T00:00:00Z",
) = """
    {"schemaVersion":"reader.pre-auth.v1","generatedAt":"$generatedAt","freshUntil":"$freshUntil","staleUntil":"$staleUntil",
     "compatibility":{"status":"$status","requestedVersion":"1.0.0","minimumVersion":"1.0.0","supportedMajor":1},
     "accountEntry":{"availability":"$availability","reason":"$reason","retryable":$retryable},
     "configuration":{"revision":"2026-09-11.1","validUntil":"$validUntil","client":{"applicationId":"$applicationId"},
       "authentication":{"kind":"supabase","authorityOrigin":"$authorityOrigin","publicClientId":"$publicClientId",
         "enabledProviders":[${enabledProviders.joinToString(",") { "\"$it\"" }}],"emailOtp":$emailOtp}},
     "postAuth":{"schemaVersion":"reader.capabilities.v1","path":"/v1/reader/capabilities","actorScoped":true}}
""".trimIndent()

/** `generatedAt` of [preAuthJson]; its default document is fresh for an hour after it and stale an hour later. */
const val PRE_AUTH_GENERATED_AT = "2026-09-11T00:00:00Z"

const val NO_SELECTOR_PRE_AUTH =
    """{"schemaVersion":"reader.pre-auth.v1",""" +
        """"compatibility":{"status":"client_unknown","requestedVersion":"1.0.0",""" +
        """"minimumVersion":null,"supportedMajor":null},""" +
        """"accountEntry":{"availability":"unavailable","reason":"client_selection_missing","retryable":false},""" +
        """"configuration":null}"""

fun apiError(code: String, requestId: String = "req-1", retryable: Boolean = false) =
    """{"code":"$code","message":"$code","category":"auth","retryable":$retryable,"request_id":"$requestId"}"""

fun providerError(code: String, message: String = code) = """{"error_code":"$code","msg":"$message"}"""

/** One request as the fake servers saw it. */
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
 * Plays the identity provider (`provider.test`) and reader-api (`api.test`)
 * behind one [MockEngine]. Each route has a queue of responders; the last
 * one repeats, so a test states only the answers that matter.
 */
class FakeServers {
    val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())

    // Guarded by itself: responders are picked on engine threads.
    private val routes = mutableMapOf<String, ArrayDeque<Responder>>()

    val engine = MockEngine { data ->
        val recorded = data.record()
        requests += recorded
        val responder = synchronized(routes) {
            val queue = routes[recorded.route] ?: routes["${recorded.method} ${recorded.path}"]
                ?: error("unexpected ${recorded.route}")
            if (queue.size > 1) queue.removeFirst() else queue.first()
        }
        responder(this, recorded)
    }

    /** The answer for [route]; a later call replaces it. */
    fun on(route: String, responder: Responder) {
        synchronized(routes) { routes[route] = ArrayDeque(listOf(responder)) }
    }

    /** Successive answers for [route]; the last one repeats. */
    fun queue(route: String, vararg responders: Responder) {
        synchronized(routes) { routes[route] = ArrayDeque(responders.toList()) }
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

/**
 * A count of events seen on engine threads that a test can suspend on:
 * [await] resumes once [arrive] has been called [expected] times.
 */
class Arrivals(private val expected: Int) {
    private val count = AtomicInteger()
    private val reached = CompletableDeferred<Unit>()

    fun arrive() {
        if (count.incrementAndGet() == expected) reached.complete(Unit)
    }

    suspend fun await() = reached.await()
}

/**
 * A responder that parks until [gate] completes, so a test can prove callers
 * were waiting concurrently. [entered], when given, is told as each request
 * reaches the gate.
 */
fun gated(gate: CompletableDeferred<Unit>, entered: Arrivals? = null, then: Responder): Responder = { recorded ->
    entered?.arrive()
    gate.await()
    then(this, recorded)
}

// Provider routes as the SDK addresses them.
const val OTP = "POST /auth/v1/otp"
const val VERIFY = "POST /auth/v1/verify"
const val PASSWORD_GRANT = "POST /auth/v1/token?grant_type=password"
const val REFRESH_GRANT = "POST /auth/v1/token?grant_type=refresh_token"
const val RECOVER = "POST /auth/v1/recover"
const val USER = "PUT /auth/v1/user"
const val LOGOUT_LOCAL = "POST /auth/v1/logout?scope=local"
const val LOGOUT_OTHERS = "POST /auth/v1/logout?scope=others"

// reader-api routes.
const val PRE_AUTH = "GET /v1/reader/pre-auth?clientVersion=1.0.0"
const val CAPABILITIES = "GET /v1/reader/capabilities?clientVersion=1.0.0"
const val PROFILE = "PUT /v1/reader/profile"

/**
 * A protected route that is *not* one of the three the contract names, used to
 * pin the generic `get`/`put`/`post` verbs a host builds further calls on. The
 * path is deliberately not a real reader-api route: this module knows the
 * policy, not the route list.
 */
const val GENERIC_PATH = "/v1/reader/generic"
const val GENERIC_POST = "POST /v1/reader/generic"

const val CAPABILITIES_BODY = """{"schemaVersion":"reader.capabilities.v1","capabilities":{"library":"available"}}"""

/** The one hour minus [inMargin] seconds a test needs to place a session on either side of the margin. */
fun FakeClock.expiring(inSeconds: Long): Instant = now + inSeconds.seconds

/** The repository root, for tests that read tracked documents. */
fun repositoryRoot(): File {
    var candidate: File? = File("").absoluteFile
    while (candidate != null) {
        if (File(candidate, "settings.gradle.kts").isFile) return candidate
        candidate = candidate.parentFile
    }
    error("no settings.gradle.kts above ${File("").absoluteFile}")
}
