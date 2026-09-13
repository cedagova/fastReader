package com.cedagova.reader.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CONTRACT.md, "Bootstrap and first calls": the pre-auth document must
 * describe this client before any provider call is made.
 */
class PreAuthGuardTest {

    private val servers = FakeServers()

    private suspend fun client(): ReaderAuthClient =
        ReaderAuthClient.build(testConfig, InMemorySessionStore(), servers.engine).also { it.awaitReady() }

    private suspend fun assertSignInRefused(reasonFragment: String) {
        val client = client()
        for (attempt in listOf<suspend () -> Unit>(
            { client.requestEmailCode("reader@example.test", createUser = true) },
            { client.signInWithPassword("reader@example.test", "correct horse battery staple") },
            { client.requestRecoveryCode("reader@example.test") },
            { client.verifyEmailCode("reader@example.test", "123456") },
        )) {
            val failure = runCatching { attempt() }.exceptionOrNull()
            assertTrue("$failure", failure is ReaderAuthException.ConfigurationMismatch)
            assertTrue(failure!!.message!!, failure.message!!.contains(reasonFragment))
        }
        assertTrue("a provider request was made: ${servers.routes()}", servers.requests.none { it.host == "provider.test" })
        assertTrue("pre-auth is fetched again until it verifies", servers.requests.all { it.path == "/v1/reader/pre-auth" })
        client.close()
    }

    @Test
    fun `a different application id blocks sign-in`() = runTest {
        servers.on(PRE_AUTH) { json(preAuthJson(applicationId = "reader-web")) }
        assertSignInRefused("client.applicationId is reader-web, expected reader-android")
    }

    @Test
    fun `a different publishable key blocks sign-in without echoing either key`() = runTest {
        servers.on(PRE_AUTH) { json(preAuthJson(publicClientId = "sb_publishable_other")) }
        assertSignInRefused("publicClientId differs")
        assertTrue(servers.requests.isNotEmpty())
    }

    @Test
    fun `a different authority blocks sign-in`() = runTest {
        servers.on(PRE_AUTH) { json(preAuthJson(authorityOrigin = "https://other.test")) }
        assertSignInRefused("authorityOrigin is https://other.test")
    }

    @Test
    fun `a document without a configuration blocks sign-in`() = runTest {
        servers.on(PRE_AUTH) { json(NO_SELECTOR_PRE_AUTH) }
        assertSignInRefused("compatibility status is client_unknown")
    }

    @Test
    fun `a matching document lets sign-in through and is fetched once`() = runTest {
        servers.on(PRE_AUTH) { json(preAuthJson()) }
        servers.on(OTP) { json("{}") }
        servers.on(RECOVER) { json("{}") }
        val client = client()

        client.requestEmailCode("reader@example.test", createUser = true)
        client.requestRecoveryCode("reader@example.test")

        assertEquals(listOf(PRE_AUTH, OTP, RECOVER), servers.routes())
        client.close()
    }

    @Test
    fun `the mismatch reason never carries the publishable key`() {
        val document = com.cedagova.reader.auth.api.PreAuthDocument.parse(
            kotlinx.serialization.json.Json.parseToJsonElement(preAuthJson(publicClientId = "sb_publishable_other")).let { it as kotlinx.serialization.json.JsonObject },
        )
        val reason = document.mismatch(testConfig)!!
        assertTrue(reason, !reason.contains(PUBLISHABLE_KEY) && !reason.contains("sb_publishable_other"))
    }
}
