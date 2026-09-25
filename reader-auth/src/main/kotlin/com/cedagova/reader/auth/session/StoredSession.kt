package com.cedagova.reader.auth.session

import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import kotlin.time.Instant

/**
 * One session as a [SessionStore] keeps it (#198, A197-F001).
 *
 * Opaque on purpose: the identity provider's session record stays inside the
 * module, so a store implementation holds and hands back the value without
 * reading it, and a provider SDK change never reaches a host's signatures.
 * The production store writes the provider record inside it byte for byte as
 * before, so a stored session keeps its format.
 *
 * [toString] names no token, so a stored session never lands in a log.
 */
public class StoredSession internal constructor(internal val value: UserSession) {

    override fun equals(other: Any?): Boolean = other is StoredSession && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "StoredSession(<redacted>)"

    public companion object {
        /**
         * A signed-in session built from its parts, so a test outside this
         * module can seed a [SessionStore] without the provider SDK — the one
         * shape the module's own test harnesses store: a one-hour bearer
         * token for an `authenticated` user.
         *
         * It is for tests, like `ReaderAuthClient.createForTests`; a real
         * host only ever stores the sessions the module hands its store.
         */
        public fun forTests(
            accessToken: String,
            refreshToken: String,
            expiresAt: Instant,
            userId: String,
            email: String?,
        ): StoredSession = StoredSession(
            UserSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = TEST_LIFETIME_SECONDS,
                tokenType = "bearer",
                user = UserInfo(aud = "authenticated", id = userId, email = email),
                expiresAt = expiresAt,
            ),
        )

        private const val TEST_LIFETIME_SECONDS = 3600L
    }
}
