package com.cedagova.fastreader.account

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.ReaderLibraryOperations
import com.cedagova.reader.library.model.ReaderDeltaStatus
import com.cedagova.reader.library.model.ReaderLibraryResponse
import com.cedagova.reader.library.model.ReaderMutationKind
import com.cedagova.reader.library.model.ReaderProgressListResponse
import com.cedagova.reader.library.model.ReaderResourceType
import com.cedagova.reader.library.model.ReaderSyncCapability
import com.cedagova.reader.library.model.ReaderSyncDeltaResponse
import com.cedagova.reader.library.model.ReaderSyncMutationBatchResponse
import com.cedagova.reader.library.model.ReaderSyncMutationEnvelope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-side seam: [ReaderApiLibraryGateway] passes each call through
 * unchanged, and [FakeReaderLibraryGateway] answers from a script so the state
 * model LEAF702 adds can be tested with no SDK, no network and no Keystore.
 *
 * "Unchanged" is the whole claim: the gateway must not map a failure, retry,
 * reorder envelopes or invent a default, because every one of those decisions
 * belongs to `:reader-library` or to `:reader-auth`.
 */
class ReaderLibraryGatewayTest {

    @Test
    fun `the production gateway is one library call per method, arguments verbatim`() = runTest {
        val operations = RecordingOperations()
        val gateway = ReaderApiLibraryGateway(operations)
        val envelopes = listOf(envelope("key-1"), envelope("key-2"))

        assertSame(operations.libraryResponse, gateway.library())
        assertSame(operations.progressResponse, gateway.progress())
        assertSame(operations.mutationResponse, gateway.applyMutations(envelopes))
        assertSame(operations.deltaResponse, gateway.deltas("42", 7))
        assertSame(operations.capabilityResponse, gateway.syncCapability())

        assertEquals(
            listOf("library()", "progress()", "applyMutations(2)", "deltas(42, 7)", "syncCapability()"),
            operations.calls,
        )
        assertEquals(envelopes, operations.submitted.single())
    }

    @Test
    fun `the production gateway defaults deltas to the module's own first page`() = runTest {
        val operations = RecordingOperations()

        ReaderApiLibraryGateway(operations).deltas()

        assertEquals(listOf("deltas(0, 100)"), operations.calls)
    }

    @Test
    fun `a failure travels through the gateway as the same exception instance`() = runTest {
        val thrown = ReaderAuthException.Forbidden(code = "reader.forbidden", requestId = "req-403")
        val gateway = ReaderApiLibraryGateway(RecordingOperations(failure = thrown))

        val caught = runCatching { gateway.library() }.exceptionOrNull()

        assertSame(thrown, caught)
    }

    @Test
    fun `the fake records calls, answers from its script and throws what a test sets`() = runTest {
        val fake = FakeReaderLibraryGateway()
        fake.deltaResponses = ArrayDeque(
            listOf(
                FakeReaderLibraryGateway.emptyDeltas(latestCursor = "10"),
                FakeReaderLibraryGateway.emptyDeltas(ReaderDeltaStatus.CURSOR_EXPIRED),
            ),
        )

        assertEquals("10", fake.deltas("0", 100).latestCursor)
        assertEquals(ReaderDeltaStatus.CURSOR_EXPIRED, fake.deltas("10", 100).status)
        // The last queued answer repeats.
        assertEquals(ReaderDeltaStatus.CURSOR_EXPIRED, fake.deltas("10", 100).status)

        fake.applyMutations(listOf(envelope("key-1")))
        fake.nextFailure = ReaderAuthException.SignedOut(code = "auth.revoked_session")
        val caught = runCatching { fake.library() }.exceptionOrNull()

        assertTrue("$caught", caught is ReaderAuthException.SignedOut)
        assertEquals(
            listOf("deltas(0, 100)", "deltas(10, 100)", "deltas(10, 100)", "applyMutations(1)", "library()"),
            fake.calls,
        )
        assertEquals(listOf("key-1"), fake.submitted.single().map { it.idempotencyKey })
    }

    private fun envelope(key: String) = ReaderSyncMutationEnvelope(
        idempotencyKey = key,
        resourceType = ReaderResourceType.LIBRARY_ITEM,
        resourceId = "1f0f1c9e-6a3c-4f8a-9c2b-2f1c7d3e4a5b",
        mutationKind = ReaderMutationKind.UPSERT,
        baseRevision = 0,
    )

    /** The module's operation surface, stubbed: it only records and answers. */
    private class RecordingOperations(private val failure: ReaderAuthException? = null) : ReaderLibraryOperations {
        val calls = mutableListOf<String>()
        val submitted = mutableListOf<List<ReaderSyncMutationEnvelope>>()

        val libraryResponse = ReaderLibraryResponse(requestId = "req-1")
        val progressResponse = ReaderProgressListResponse(requestId = "req-2")
        val mutationResponse = ReaderSyncMutationBatchResponse(requestId = "req-3")
        val deltaResponse = ReaderSyncDeltaResponse(
            requestId = "req-4",
            status = ReaderDeltaStatus.OK,
            minimumValidCursor = "0",
            latestCursor = "42",
        )
        val capabilityResponse = ReaderSyncCapability.UNDECLARED

        override suspend fun library(): ReaderLibraryResponse {
            calls += "library()"
            failure?.let { throw it }
            return libraryResponse
        }

        override suspend fun progress(): ReaderProgressListResponse {
            calls += "progress()"
            failure?.let { throw it }
            return progressResponse
        }

        override suspend fun applyMutations(
            mutations: List<ReaderSyncMutationEnvelope>,
        ): ReaderSyncMutationBatchResponse {
            calls += "applyMutations(${mutations.size})"
            submitted += mutations
            failure?.let { throw it }
            return mutationResponse
        }

        override suspend fun deltas(afterCursor: String, limit: Int): ReaderSyncDeltaResponse {
            calls += "deltas($afterCursor, $limit)"
            failure?.let { throw it }
            return deltaResponse
        }

        override suspend fun syncCapability(): ReaderSyncCapability {
            calls += "syncCapability()"
            failure?.let { throw it }
            return capabilityResponse
        }
    }
}
