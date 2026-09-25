package com.cedagova.reader.auth.testing

import com.cedagova.reader.auth.ReaderAuthConfig
import com.cedagova.reader.auth.ReaderAuthPolicy
import com.cedagova.reader.auth.ReaderClock
import com.cedagova.reader.auth.RetryWaiter
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Settable seams and the documents the fake servers answer with. Every token,
// key and address here is a test value; none is a credential.

/** The identity provider's origin behind [FakeServers]. */
public const val PROVIDER_URL: String = "https://provider.test"

/** The publishable key [testConfig] carries and [preAuthJson] names by default. */
public const val PUBLISHABLE_KEY: String = "sb_publishable_test_key"

/** reader-api's origin behind [FakeServers]. */
public const val API_URL: String = "https://api.test"

/** A configured client pointed at [FakeServers]. */
public val testConfig: ReaderAuthConfig =
    ReaderAuthConfig(supabaseUrl = PROVIDER_URL, publishableKey = PUBLISHABLE_KEY, readerApiBaseUrl = API_URL)

/** A clock a test sets and advances. */
public class FakeClock(public var now: Instant = Clock.System.now()) : ReaderClock {
    override fun now(): Instant = now

    public fun advance(by: Duration) {
        now += by
    }

    /** The instant [inSeconds] from [now], to place a session on either side of the refresh margin. */
    public fun expiring(inSeconds: Long): Instant = now + inSeconds.seconds
}

/** A waiter that records each wait instead of sleeping. */
public class RecordingWaiter : RetryWaiter {
    public val waits: MutableList<Duration> = mutableListOf()

    override suspend fun wait(duration: Duration) {
        waits += duration
    }
}

/**
 * A stored session as a test states it: a one-hour bearer session for an
 * `authenticated` user. [ReaderAuthHarness] seeds its store with one and reads
 * the stored one back as one. The tokens are test values; [toString] names
 * none of them anyway, like the module's own stored session.
 */
public data class TestSession(
    val expiresAt: Instant,
    val accessToken: String = "access-1",
    val refreshToken: String = "refresh-1",
    val userId: String = "user-1",
    val email: String? = "reader@example.test",
) {
    override fun toString(): String = "TestSession(userId=$userId, expiresAt=$expiresAt, <tokens redacted>)"
}

/** The provider's token-grant answer for a session with these tokens. */
public fun sessionJson(
    accessToken: String,
    refreshToken: String,
    userId: String = "user-1",
    email: String = "reader@example.test",
): String = """
    {"access_token":"$accessToken","refresh_token":"$refreshToken","token_type":"bearer","expires_in":3600,
     "user":{"id":"$userId","aud":"authenticated","email":"$email"}}
""".trimIndent()

/** `generatedAt` of [preAuthJson]; its default document is fresh for an hour after it and stale an hour later. */
public const val PRE_AUTH_GENERATED_AT: String = "2026-09-11T00:00:00Z"

/** reader-api's pre-auth document; by default one that [testConfig] accepts. */
public fun preAuthJson(
    applicationId: String = ReaderAuthPolicy.CLIENT_ID,
    publicClientId: String = PUBLISHABLE_KEY,
    authorityOrigin: String = PROVIDER_URL,
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
): String = """
    {"schemaVersion":"reader.pre-auth.v1","generatedAt":"$generatedAt","freshUntil":"$freshUntil","staleUntil":"$staleUntil",
     "compatibility":{"status":"$status","requestedVersion":"1.0.0","minimumVersion":"1.0.0","supportedMajor":1},
     "accountEntry":{"availability":"$availability","reason":"$reason","retryable":$retryable},
     "configuration":{"revision":"2026-09-11.1","validUntil":"$validUntil","client":{"applicationId":"$applicationId"},
       "authentication":{"kind":"supabase","authorityOrigin":"$authorityOrigin","publicClientId":"$publicClientId",
         "enabledProviders":[${enabledProviders.joinToString(",") { "\"$it\"" }}],"emailOtp":$emailOtp}},
     "postAuth":{"schemaVersion":"reader.capabilities.v1","path":"/v1/reader/capabilities","actorScoped":true}}
""".trimIndent()

/** A capabilities document with the library available. */
public const val CAPABILITIES_BODY: String =
    """{"schemaVersion":"reader.capabilities.v1","capabilities":{"library":"available"}}"""

/** reader-api's `ErrorResponse` shape, as the call policy reads it. */
public fun apiError(code: String, requestId: String = "req-1", retryable: Boolean = false): String =
    """{"code":"$code","message":"$code","category":"auth","retryable":$retryable,"request_id":"$requestId"}"""

/** The identity provider's error shape. */
public fun providerError(code: String, message: String = code): String = """{"error_code":"$code","msg":"$message"}"""
