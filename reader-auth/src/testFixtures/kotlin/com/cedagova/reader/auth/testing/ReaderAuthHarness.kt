package com.cedagova.reader.auth.testing

import com.cedagova.reader.auth.ReaderAuthClient
import com.cedagova.reader.auth.ReaderAuthConfig
import com.cedagova.reader.auth.session.SessionStore
import com.cedagova.reader.auth.session.StoredSession
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import java.util.UUID
import kotlin.time.Instant

/**
 * A real [ReaderAuthClient] over [servers] (#199, A197-F002): the production
 * wiring — the same bearer, single-flight refresh and 401/403/429/502 policy —
 * with an in-memory session store and a mock engine in place of the Keystore
 * store and OkHttp. It replaces `ReaderAuthClient.createForTests`, so the
 * production API carries no test-only entry point.
 *
 * A module layered on this one (`:reader-library`) and a host use it to prove
 * their own code against the real call policy, which a hand-written double of
 * the client could not show. A host that only needs scripted answers uses
 * [FakeReaderAuthOperations] instead.
 *
 * [session], when given, is stored before the client starts, so the client
 * starts signed in. [storedSession] reads the store back — null after a
 * sign-out or a rejected token cleared it — and replaces it.
 */
public class ReaderAuthHarness(
    public val servers: FakeServers = FakeServers(),
    public val clock: FakeClock = FakeClock(),
    public val waiter: RecordingWaiter = RecordingWaiter(),
    session: TestSession? = null,
    public val config: ReaderAuthConfig = testConfig,
    private val requestIds: () -> String = { UUID.randomUUID().toString().lowercase() },
) {
    internal val store = InMemorySessionStore(session?.toUserSession())

    private val built = mutableListOf<ReaderAuthClient>()

    /**
     * The session the store holds now, or null when none is stored. Setting it
     * replaces what the store holds, as another process writing it would; a
     * client built afterwards starts from it.
     */
    public var storedSession: TestSession?
        get() = store.session?.toTestSession()
        set(value) {
            store.session = value?.toUserSession()
        }

    /**
     * A new client over the same store and servers, ready (its stored session
     * read) — each call is a process start. Throws
     * `ReaderAuthException.NotConfigured` for a blank [config] value, exactly
     * as production does.
     */
    public suspend fun client(): ReaderAuthClient =
        ReaderAuthClient.build(config, store, servers.engine, clock, waiter, requestIds).also {
            built += it
            it.awaitReady()
        }

    /** Releases the provider SDK's client of every [client] built. */
    public suspend fun close() {
        built.forEach { it.close() }
    }
}

/**
 * The in-memory [SessionStore] of the fixtures. Internal like the store type:
 * this module's own tests use it directly (they read the provider session
 * inside), everyone else through [ReaderAuthHarness].
 */
internal class InMemorySessionStore(initial: UserSession? = null) : SessionStore {
    @Volatile var session: UserSession? = initial
    val saves: MutableList<UserSession> = mutableListOf()

    override suspend fun save(session: StoredSession) {
        this.session = session.value
        saves += session.value
    }

    override suspend fun load(): StoredSession? = session?.let(::StoredSession)

    override suspend fun clear() {
        session = null
    }
}

/** An `authenticated` provider user. */
internal fun user(id: String = "user-1", email: String? = "reader@example.test"): UserInfo =
    UserInfo(aud = "authenticated", id = id, email = email)

/** A one-hour bearer provider session. */
internal fun session(
    accessToken: String = "access-1",
    refreshToken: String = "refresh-1",
    expiresAt: Instant,
    user: UserInfo? = user(),
): UserSession = UserSession(
    accessToken = accessToken,
    refreshToken = refreshToken,
    expiresIn = 3600,
    tokenType = "bearer",
    user = user,
    expiresAt = expiresAt,
)

private fun TestSession.toUserSession(): UserSession = session(
    accessToken = accessToken,
    refreshToken = refreshToken,
    expiresAt = expiresAt,
    user = user(userId, email),
)

private fun UserSession.toTestSession(): TestSession = TestSession(
    expiresAt = expiresAt,
    accessToken = accessToken,
    refreshToken = refreshToken,
    userId = user?.id.orEmpty(),
    email = user?.email,
)
