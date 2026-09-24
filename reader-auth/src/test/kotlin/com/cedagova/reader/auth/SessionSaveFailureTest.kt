package com.cedagova.reader.auth

import com.cedagova.reader.auth.session.FileSessionStore
import com.cedagova.reader.auth.session.SessionCipher
import com.cedagova.reader.auth.session.SessionStore
import io.github.jan.supabase.auth.user.UserSession
import java.io.IOException
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #154: a session the provider issued but the device could not save. The
 * device stays signed in for the process, no refresh grant is sent twice, and
 * the host sees [ReaderAuthException.StorageUnavailable] — through the real
 * [FileSessionStore] over a cipher that refuses to encrypt, and through a
 * store whose write fails.
 */
class SessionSaveFailureTest {

    private val clock = FakeClock()
    private val waiter = RecordingWaiter()
    private val servers = FakeServers()

    /** Decrypts what a healthy key wrote earlier; refuses to encrypt while [failEncrypt] is set. */
    private class RefusingCipher(var failEncrypt: Boolean = false) : SessionCipher {
        private val inner = FakeCipher()
        override fun encrypt(plaintext: ByteArray): ByteArray {
            if (failEncrypt) throw KeyStoreException("keystore unavailable")
            return inner.encrypt(plaintext)
        }
        override fun decrypt(blob: ByteArray): ByteArray = inner.decrypt(blob)
    }

    /** Holds [initial]; every write fails like a full disk. */
    private class UnwritableStore(initial: UserSession?) : SessionStore {
        @Volatile var session: UserSession? = initial
        var failedSaves = 0
        override suspend fun save(session: UserSession) {
            failedSaves += 1
            throw IOException("no space left on device")
        }
        override suspend fun load(): UserSession? = session
        override suspend fun clear() { session = null }
    }

    private suspend fun clientOver(store: SessionStore): ReaderAuthClient =
        ReaderAuthClient.build(testConfig, store, servers.engine, clock, waiter).also { it.awaitReady() }

    @Test
    fun `a refresh whose save the keystore refuses keeps the new session and sends one grant`() = runTest {
        servers.on(REFRESH_GRANT) { json(sessionJson("access-2", "refresh-2")) }
        servers.on(CAPABILITIES) { json(CAPABILITIES_BODY) }
        val directory = Files.createTempDirectory("reader-auth").toFile()
        val cipher = RefusingCipher()
        val store = FileSessionStore(directory, cipher, StandardTestDispatcher(testScheduler))
        store.save(session(expiresAt = clock.expiring(60)))
        cipher.failEncrypt = true
        val client = clientOver(store)

        val failure = runCatching { client.capabilities() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.StorageUnavailable)
        assertTrue("${failure?.cause}", failure?.cause is GeneralSecurityException)
        assertTrue(client.currentState() is ReaderSessionState.SignedIn)
        // The stored copy held the consumed token; it is gone, so a restart starts signed out.
        assertFalse(store.file.exists())

        // The same call again: the refreshed session is current, so no second grant.
        client.capabilities()

        assertEquals(listOf(REFRESH_GRANT, CAPABILITIES), servers.routes())
        assertEquals("access-2", servers.requestsTo("/v1/reader/capabilities").single().bearer)
        assertEquals(emptyList<kotlin.time.Duration>(), waiter.waits)
        client.close()
    }

    @Test
    fun `a refresh whose file write fails is not retried with the consumed token`() = runTest {
        servers.on(REFRESH_GRANT) { json(sessionJson("access-2", "refresh-2")) }
        servers.on(CAPABILITIES) { json(CAPABILITIES_BODY) }
        val store = UnwritableStore(session(expiresAt = clock.expiring(60)))
        val client = clientOver(store)

        val failure = runCatching { client.capabilities() }.exceptionOrNull()
        val state = client.onForeground()
        client.capabilities()

        assertTrue("$failure", failure is ReaderAuthException.StorageUnavailable)
        assertTrue("${failure?.cause}", failure?.cause is IOException)
        assertTrue(state is ReaderSessionState.SignedIn)
        assertEquals(1, servers.requestsTo("/auth/v1/token").size)
        assertEquals("""{"refresh_token":"refresh-1"}""", servers.requestsTo("/auth/v1/token").single().body)
        assertEquals(listOf("access-2"), servers.requestsTo("/v1/reader/capabilities").map { it.bearer })
        // One save of the loaded session at start-up, one of the refreshed one.
        assertEquals(2, store.failedSaves)
        assertNull(store.session)
        assertEquals(emptyList<kotlin.time.Duration>(), waiter.waits)
        client.close()
    }

    @Test
    fun `a sign-in whose save fails is a typed failure and signed in for the process`() = runTest {
        servers.on(PRE_AUTH) { json(preAuthJson()) }
        servers.on(PASSWORD_GRANT) { json(sessionJson("access-9", "refresh-9")) }
        servers.on(CAPABILITIES) { json(CAPABILITIES_BODY) }
        val store = UnwritableStore(null)
        val client = clientOver(store)

        val failure = runCatching { client.signInWithPassword("reader@example.test", "secret") }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.StorageUnavailable)
        assertTrue(client.currentState() is ReaderSessionState.SignedIn)
        client.capabilities()
        assertEquals("access-9", servers.requestsTo("/v1/reader/capabilities").single().bearer)
        client.close()
    }

    @Test
    fun `a save failure is reported once and not blamed on the next operation`() = runTest {
        servers.on(PRE_AUTH) { json(preAuthJson()) }
        servers.on(PASSWORD_GRANT) { json(sessionJson("access-9", "refresh-9")) }
        servers.on(LOGOUT_OTHERS) { json("{}") }
        val client = clientOver(UnwritableStore(null))

        runCatching { client.signInWithPassword("reader@example.test", "secret") }
        client.signOutOtherDevices()

        assertEquals(listOf(PRE_AUTH, PASSWORD_GRANT, LOGOUT_OTHERS), servers.routes())
        client.close()
    }

    /**
     * #183: the save-failure slot is shared, and a refresh resets it, saves,
     * and reads it back with no suspension in between — so only a caller on
     * another thread can land in that window. The test puts one there
     * deterministically: a collector on [Dispatchers.Unconfined] runs inline
     * the moment the SDK makes the refreshed session current, which is after
     * the failed save was recorded and before the refresh reads it, and it
     * starts signOutOtherDevices() undispatched, so that call runs up to its
     * first network request inside the window.
     */
    @Test
    fun `signOutOtherDevices during a refresh whose save fails leaves the failure to the refresh`() = runTest {
        servers.on(REFRESH_GRANT) { json(sessionJson("access-2", "refresh-2")) }
        servers.on(CAPABILITIES) { json(CAPABILITIES_BODY) }
        servers.on(LOGOUT_OTHERS) { json("{}") }
        val stored = session(expiresAt = clock.expiring(60))
        val store = UnwritableStore(stored)
        val client = clientOver(store)
        val otherDevices = CompletableDeferred<Deferred<Result<Unit>>>()
        launch(Dispatchers.Unconfined) {
            client.sessionState.first { it is ReaderSessionState.SignedIn && it.expiresAt > stored.expiresAt }
            otherDevices.complete(
                this@runTest.async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.signOutOtherDevices() } },
            )
        }

        val refreshFailure = runCatching { client.capabilities() }.exceptionOrNull()
        val signOutOthers = otherDevices.await().await()

        assertTrue("$refreshFailure", refreshFailure is ReaderAuthException.StorageUnavailable)
        assertTrue("${signOutOthers.exceptionOrNull()}", signOutOthers.isSuccess)
        // The stored copy held the refresh token the grant consumed; the refresh removed it.
        assertNull(store.session)
        assertEquals(listOf("access-2"), servers.requestsTo("/auth/v1/logout").map { it.bearer })
        assertEquals(1, servers.requestsTo("/auth/v1/token").size)
        assertTrue(servers.requestsTo("/v1/reader/capabilities").isEmpty())
        client.close()
    }
}
