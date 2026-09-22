package com.cedagova.reader.library

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.model.ReaderCapabilityAvailability
import com.cedagova.reader.library.model.ReaderCapabilityReason
import com.cedagova.reader.library.model.ReaderCoverStatus
import com.cedagova.reader.library.model.ReaderDeltaStatus
import com.cedagova.reader.library.model.ReaderLibraryStatus
import com.cedagova.reader.library.model.ReaderMembershipAction
import com.cedagova.reader.library.model.ReaderMembershipState
import com.cedagova.reader.library.model.ReaderMutationKind
import com.cedagova.reader.library.model.ReaderResourceType
import com.cedagova.reader.library.model.ReaderServerAdmission
import com.cedagova.reader.library.model.ReaderSyncConflictCode
import com.cedagova.reader.library.model.ReaderSyncMutationEnvelope
import com.cedagova.reader.library.model.ReaderSyncRejectionCode
import com.cedagova.reader.library.model.ReaderSyncStatus
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every operation `:reader-library` offers, driven over a Ktor mock engine
 * through a real `ReaderAuthClient`. No network, no device, no backend value.
 *
 * Two things are being proved at once, and both matter:
 *
 * 1. the module sends the request the pinned contract declares and turns the
 *    server's document into the right typed result — including the outcomes
 *    that are results rather than failures (`replayed`, `superseded`,
 *    `conflict`, `rejected`, `cursor_expired`); and
 * 2. it inherits `:reader-auth`'s call policy untouched: the same bearer, the
 *    same single-flight refresh on an expiry, and the same
 *    `ReaderAuthException` branch for every HTTP failure.
 */
class ReaderLibraryClientTest {

    // ---------------------------------------------------------------- reads

    @Test
    fun `library returns the account rows and carries the authenticated headers`() = runTest {
        val h = Harness()
        h.servers.on(LIBRARY) { json(LIBRARY_BODY) }

        val response = h.operations().library()

        assertEquals("c0ffee00-0000-4000-8000-000000000001", response.requestId)
        assertEquals("reader.v1", response.contractVersion)
        val item = response.items.single()
        assertEquals(ReaderLibraryStatus.READING, item.status)
        assertEquals(ReaderCoverStatus.COVERED, item.coverStatus)
        assertEquals("2026-09-13T21:00:00Z", item.lastOpenedAt)
        assertEquals(BOOK_ID, item.book.id)
        assertEquals("Tractatus", item.book.title)
        assertEquals("Wittgenstein", item.book.author)
        assertEquals(JsonPrimitive("9780000000000"), item.book.metadata["isbn"])
        val asset = item.assets.single()
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", asset.checksum)
        assertEquals(482913L, asset.sizeBytes)

        val request = h.servers.requestsTo(ReaderLibraryClient.LIBRARY_PATH).single()
        assertEquals("GET", request.method)
        assertEquals("api.test", request.host)
        assertEquals("access-1", request.bearer)
        assertEquals("reader-android", request.headers["X-Reader-Client"])
        assertEquals("application/json", request.headers["Accept"])
        assertNotNull(request.headers["X-Request-ID"])
        assertEquals("", request.query)
        h.close()
    }

    @Test
    fun `progress returns every position the account holds, locator untouched`() = runTest {
        val h = Harness()
        h.servers.on(PROGRESS) { json(PROGRESS_BODY) }

        val response = h.operations().progress()

        val row = response.progress.single()
        assertEquals(BOOK_ID, row.bookId)
        assertEquals(42.5, row.progressPercent, 0.0)
        assertEquals("Preface", row.chapterTitle)
        assertEquals(BOOK_ID, row.location.publication.publicationId)
        assertEquals("epub", row.location.publication.format)
        // The locator is carried through whole, including a field FastReader never writes.
        assertEquals(JsonPrimitive("epubcfi(/6/14!/4/2/2)"), row.location.locator["epub_cfi"])
        assertEquals(JsonPrimitive("OEBPS/preface.xhtml"), row.location.locator["href"])
        assertEquals("access-1", h.servers.requestsTo(ReaderLibraryClient.PROGRESS_PATH).single().bearer)
        h.close()
    }

    // ------------------------------------------------------------ mutations

    @Test
    fun `a mutation batch sends the contract's envelope and reads an applied result`() = runTest {
        val h = Harness()
        h.servers.on(MUTATIONS) { json(mutationsBody(mutationResult())) }

        val response = h.operations().applyMutations(listOf(envelope()))

        val result = response.results.single()
        assertEquals(ReaderSyncStatus.APPLIED, result.status)
        assertTrue(result.isAdmitted)
        assertEquals(ReaderServerAdmission.ACCEPTED, result.serverAdmission)
        assertEquals(7L, result.revision)
        assertEquals("1042", result.cursor)
        assertEquals(ReaderResourceType.LIBRARY_ITEM, result.resourceType)
        assertEquals(ReaderMutationKind.UPSERT, result.mutationKind)
        assertEquals(JsonPrimitive("reading"), result.canonicalPayload["status"])
        assertNull(result.conflict)
        assertNull(result.rejection)

        val sent = h.servers.requestsTo(ReaderLibraryClient.MUTATIONS_PATH).single()
        assertEquals("POST", sent.method)
        assertEquals("access-1", sent.bearer)
        assertTrue(sent.headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals(
            """{"mutations":[{"idempotency_key":"key-1","resource_type":"library_item","resource_id":"$BOOK_ID",""" +
                """"mutation_kind":"upsert","base_revision":6,"payload":{"status":"reading"}}]}""",
            sent.body,
        )
        h.close()
    }

    /** A re-sent key is not admitted twice: the server returns the same admission as `replayed`. */
    @Test
    fun `a replayed result reports the original admission`() = runTest {
        val h = Harness()
        h.servers.on(MUTATIONS) { json(mutationsBody(mutationResult(status = "replayed", admission = "replayed"))) }

        val result = h.operations().applyMutations(listOf(envelope())).results.single()

        assertEquals(ReaderSyncStatus.REPLAYED, result.status)
        assertEquals(ReaderServerAdmission.REPLAYED, result.serverAdmission)
        assertTrue(result.isAdmitted)
        assertEquals("1042", result.cursor)
        h.close()
    }

    @Test
    fun `a superseded result is admitted and carries the canonical payload to adopt`() = runTest {
        val h = Harness()
        h.servers.on(MUTATIONS) { json(mutationsBody(mutationResult(status = "superseded"))) }

        val result = h.operations().applyMutations(listOf(envelope())).results.single()

        assertEquals(ReaderSyncStatus.SUPERSEDED, result.status)
        assertTrue(result.isAdmitted)
        assertEquals(JsonPrimitive("reading"), result.canonicalPayload["status"])
        h.close()
    }

    /** AD-22: a conflict is adopted from the server's canonical payload, never prompted. */
    @Test
    fun `a conflict result carries the server's canonical resolution`() = runTest {
        val conflict = """, "conflict": {"conflict_id": "c-1", "code": "revision_conflict", "remote_revision": 9,
            "conflicting_fields": ["status"], "canonical_payload": {"status": "archived"}}"""
        val h = Harness()
        h.servers.on(MUTATIONS) {
            json(mutationsBody(mutationResult(status = "conflict", admission = null, extra = conflict)))
        }

        val result = h.operations().applyMutations(listOf(envelope())).results.single()

        assertEquals(ReaderSyncStatus.CONFLICT, result.status)
        assertFalse(result.isAdmitted)
        assertNull(result.serverAdmission)
        assertNull(result.revision)
        assertNull(result.cursor)
        val detail = result.conflict!!
        assertEquals("c-1", detail.conflictId)
        assertEquals(ReaderSyncConflictCode.REVISION_CONFLICT, detail.code)
        assertEquals(9L, detail.remoteRevision)
        assertEquals(listOf("status"), detail.conflictingFields)
        assertEquals(JsonPrimitive("archived"), detail.canonicalPayload["status"])
        h.close()
    }

    /** Every rejection code the document declares decodes to its own member. */
    @Test
    fun `each rejection code decodes to its typed reason`() = runTest {
        val codes = mapOf(
            "invalid_payload" to ReaderSyncRejectionCode.INVALID_PAYLOAD,
            "invalid_resource_id" to ReaderSyncRejectionCode.INVALID_RESOURCE_ID,
            "idempotency_mismatch" to ReaderSyncRejectionCode.IDEMPOTENCY_MISMATCH,
            "related_resource_missing" to ReaderSyncRejectionCode.RELATED_RESOURCE_MISSING,
            "unsupported_mutation" to ReaderSyncRejectionCode.UNSUPPORTED_MUTATION,
        )
        val h = Harness()
        h.servers.on(MUTATIONS) { recorded ->
            val code = codes.keys.first { recorded.body.contains("\"$it\"") }
            json(
                mutationsBody(
                    mutationResult(
                        status = "rejected",
                        admission = null,
                        extra = """, "rejection": {"code": "$code", "detail": "$code refused", "retryable": false}""",
                    ).replace("""{"status": "reading"}""", "{}"),
                ),
            )
        }
        val operations = h.operations()

        codes.forEach { (wire, expected) ->
            val result = operations.applyMutations(listOf(envelope(payloadValue = wire))).results.single()
            assertEquals(ReaderSyncStatus.REJECTED, result.status)
            assertFalse(result.isAdmitted)
            val rejection = result.rejection!!
            assertEquals(expected, rejection.code)
            assertFalse(rejection.retryable)
            assertTrue(result.canonicalPayload.isEmpty())
        }
        h.close()
    }

    /** A membership outcome rides on the result; the three contract constants are read back. */
    @Test
    fun `a membership outcome decodes with the constants the contract fixes`() = runTest {
        val membership = """, "membership": {"state": "absent", "action": "remove", "open_session_behavior": "keep_readable_until_close",
            "reopen_allowed": false, "identity_restored": false, "undo_available": true, "undo_scope": "immediate_confirmation",
            "activity_identity_preserved": true, "download_bytes_changed": false, "durable_recovery_available": false}"""
        val h = Harness()
        h.servers.on(MUTATIONS) { json(mutationsBody(mutationResult(extra = membership))) }

        val outcome = h.operations().applyMutations(listOf(envelope())).results.single().membership!!

        assertEquals(ReaderMembershipState.ABSENT, outcome.state)
        assertEquals(ReaderMembershipAction.REMOVE, outcome.action)
        assertFalse(outcome.reopenAllowed)
        assertTrue(outcome.undoAvailable)
        assertTrue(outcome.activityIdentityPreserved)
        assertFalse(outcome.downloadBytesChanged)
        assertFalse(outcome.durableRecoveryAvailable)
        h.close()
    }

    /** Partial results are the contract: one accepted sibling is not rolled back by another's rejection. */
    @Test
    fun `a batch reports each envelope independently`() = runTest {
        val h = Harness()
        h.servers.on(MUTATIONS) {
            json(
                mutationsBody(
                    mutationResult(idempotencyKey = "key-1"),
                    mutationResult(
                        idempotencyKey = "key-2",
                        status = "rejected",
                        admission = null,
                        extra = """, "rejection": {"code": "invalid_payload", "detail": "bad", "retryable": false}""",
                    ),
                ),
            )
        }

        val results = h.operations()
            .applyMutations(listOf(envelope(key = "key-1"), envelope(key = "key-2"))).results

        assertEquals(listOf("key-1", "key-2"), results.map { it.idempotencyKey })
        assertEquals(ReaderSyncStatus.APPLIED, results[0].status)
        assertEquals(ReaderSyncStatus.REJECTED, results[1].status)
        h.close()
    }

    @Test
    fun `a batch outside the contract's bounds is refused before any request`() = runTest {
        val h = Harness()
        val operations = h.operations()

        val empty = runCatching { operations.applyMutations(emptyList()) }.exceptionOrNull()
        val tooMany = runCatching { operations.applyMutations(List(51) { envelope(key = "key-$it") }) }.exceptionOrNull()

        assertTrue("$empty", empty is IllegalArgumentException)
        assertTrue("$tooMany", tooMany is IllegalArgumentException)
        assertTrue(h.servers.requests.isEmpty())
        h.close()
    }

    // --------------------------------------------------------------- deltas

    @Test
    fun `deltas sends the cursor and limit the contract declares and reads the stream`() = runTest {
        val h = Harness()
        h.servers.on(DELTAS) { json(deltasBody(changes = DELTA_CHANGE)) }

        val response = h.operations().deltas(afterCursor = "900", limit = 250)

        assertEquals(ReaderDeltaStatus.OK, response.status)
        assertEquals("1042", response.latestCursor)
        assertEquals("900", response.minimumValidCursor)
        assertEquals("1042", response.nextCursor)
        assertFalse(response.hasMore)
        assertFalse(response.rebootstrapRequired)
        val change = response.changes.single()
        assertEquals("1042", change.cursor)
        assertEquals(ReaderResourceType.LIBRARY_ITEM, change.resourceType)
        assertEquals(7L, change.revision)
        assertEquals(ReaderMutationKind.UPSERT, change.kind)
        assertEquals(JsonPrimitive("finished"), change.canonicalPayload["status"])
        assertEquals("after_cursor=900&limit=250", h.servers.requestsTo(ReaderLibraryClient.DELTAS_PATH).single().query)
        h.close()
    }

    @Test
    fun `deltas defaults to the first cursor and the document's own page size`() = runTest {
        val h = Harness()
        h.servers.on(DELTAS) { json(deltasBody()) }

        h.operations().deltas()

        assertEquals("after_cursor=0&limit=100", h.servers.requestsTo(ReaderLibraryClient.DELTAS_PATH).single().query)
        h.close()
    }

    /** `cursor_expired` is an answer, not a failure: the caller re-bootstraps. */
    @Test
    fun `an expired cursor is a typed result asking for a rebootstrap`() = runTest {
        val h = Harness()
        h.servers.on(DELTAS) { json(deltasBody(status = "cursor_expired", rebootstrap = true, nextCursor = null)) }

        val response = h.operations().deltas(afterCursor = "1")

        assertEquals(ReaderDeltaStatus.CURSOR_EXPIRED, response.status)
        assertTrue(response.rebootstrapRequired)
        assertNull(response.nextCursor)
        assertEquals("900", response.minimumValidCursor)
        h.close()
    }

    @Test
    fun `an invalid cursor is a typed result too`() = runTest {
        val h = Harness()
        h.servers.on(DELTAS) { json(deltasBody(status = "cursor_invalid", rebootstrap = true, nextCursor = null)) }

        assertEquals(ReaderDeltaStatus.CURSOR_INVALID, h.operations().deltas().status)
        h.close()
    }

    @Test
    fun `a cursor or limit the contract forbids is refused before any request`() = runTest {
        val h = Harness()
        val operations = h.operations()

        val badCursor = runCatching { operations.deltas(afterCursor = "not-a-cursor") }.exceptionOrNull()
        val badLimit = runCatching { operations.deltas(limit = 501) }.exceptionOrNull()

        assertTrue("$badCursor", badCursor is IllegalArgumentException)
        assertTrue("$badLimit", badLimit is IllegalArgumentException)
        assertTrue(h.servers.requests.isEmpty())
        h.close()
    }

    // ---------------------------------------------------------- capabilities

    @Test
    fun `the sync capability is read from the entry the document declares`() = runTest {
        val h = Harness()
        h.servers.on(CAPABILITIES) { json(capabilitiesBody(syncEntry())) }

        val capability = h.operations().syncCapability()

        assertTrue(capability.isAvailable)
        assertTrue(capability.declared)
        assertEquals(ReaderCapabilityAvailability.AVAILABLE, capability.availability)
        assertEquals(ReaderCapabilityReason.AVAILABLE, capability.reason)
        assertNull(capability.quota)
        assertEquals("clientVersion=1.0.0", h.servers.requestsTo("/v1/reader/capabilities").single().query)
        h.close()
    }

    @Test
    fun `an unavailable sync capability reports the server's reason`() = runTest {
        val h = Harness()
        h.servers.on(CAPABILITIES) { json(capabilitiesBody(syncEntry("unavailable", "client_version_too_old"))) }

        val capability = h.operations().syncCapability()

        assertFalse(capability.isAvailable)
        assertTrue(capability.declared)
        assertEquals(ReaderCapabilityReason.CLIENT_VERSION_TOO_OLD, capability.reason)
        h.close()
    }

    /** A document that does not offer the capability at all reads as unavailable, reason unknown. */
    @Test
    fun `a document without a sync entry reads as undeclared, never as available`() = runTest {
        val h = Harness()
        h.servers.on(CAPABILITIES) { json(capabilitiesBody(null)) }

        val capability = h.operations().syncCapability()

        assertFalse(capability.isAvailable)
        assertFalse(capability.declared)
        assertEquals(ReaderCapabilityAvailability.UNAVAILABLE, capability.availability)
        assertEquals(ReaderCapabilityReason.UNKNOWN, capability.reason)
        h.close()
    }

    // ------------------------------------------ the publication-import capability (#139)

    @Test
    fun `exactly one available import entry is permission to offer import`() = runTest {
        val h = Harness()
        h.servers.on(CAPABILITIES) { json(capabilitiesBody(syncEntry() + ",\n    " + importEntry())) }

        val capability = h.operations().publicationImportCapability()

        assertTrue(capability.isAvailable)
        assertEquals(1, capability.entries)
        assertEquals(ReaderCapabilityReason.AVAILABLE, capability.reason)
        h.close()
    }

    @Test
    fun `an unavailable import entry carries the server's typed reason`() = runTest {
        val h = Harness()
        h.servers.on(CAPABILITIES) { json(capabilitiesBody(importEntry("unavailable", "quota_exhausted"))) }

        val capability = h.operations().publicationImportCapability()

        assertFalse(capability.isAvailable)
        assertEquals(ReaderCapabilityAvailability.UNAVAILABLE, capability.availability)
        assertEquals(ReaderCapabilityReason.QUOTA_EXHAUSTED, capability.reason)
        h.close()
    }

    /** core.md §6: a missing or duplicated entry is not permission to import. */
    @Test
    fun `a missing or duplicated import entry is never permission`() = runTest {
        val missing = Harness()
        missing.servers.on(CAPABILITIES) { json(capabilitiesBody(syncEntry())) }
        val none = missing.operations().publicationImportCapability()
        assertFalse(none.isAvailable)
        assertEquals(0, none.entries)
        assertEquals(ReaderCapabilityReason.UNKNOWN, none.reason)
        missing.close()

        val doubled = Harness()
        doubled.servers.on(CAPABILITIES) { json(capabilitiesBody(importEntry() + ",\n    " + importEntry())) }
        val two = doubled.operations().publicationImportCapability()
        assertFalse("two available entries are still not permission", two.isAvailable)
        assertEquals(2, two.entries)
        assertEquals(ReaderCapabilityReason.UNKNOWN, two.reason)
        doubled.close()
    }

    // ------------------------------------------------------- error branches

    /**
     * The branch that only a real client can prove: an expired token is
     * refreshed once, in flight, and the operation retries with the new bearer.
     */
    @Test
    fun `an expired token is refreshed once and the operation retries`() = runTest {
        val h = Harness()
        h.servers.queue(
            LIBRARY,
            { json(apiError("auth.expired_token"), HttpStatusCode.Unauthorized) },
            { json(LIBRARY_BODY) },
        )
        h.servers.on(REFRESH_GRANT) { json(sessionJson("access-2", "refresh-2")) }

        val response = h.operations().library()

        assertEquals(1, response.items.size)
        assertEquals(listOf(LIBRARY, REFRESH_GRANT, LIBRARY), h.servers.routes())
        val attempts = h.servers.requestsTo(ReaderLibraryClient.LIBRARY_PATH)
        assertEquals("access-1", attempts[0].bearer)
        assertEquals("access-2", attempts[1].bearer)
        assertNotNull(h.store.session)
        h.close()
    }

    @Test
    fun `any other 401 signs the client out and clears the session`() = runTest {
        val h = Harness()
        h.servers.on(MUTATIONS) { json(apiError("auth.revoked_session", "req-401"), HttpStatusCode.Unauthorized) }

        val failure = runCatching { h.operations().applyMutations(listOf(envelope())) }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.SignedOut)
        assertEquals("auth.revoked_session", (failure as ReaderAuthException.SignedOut).code)
        assertEquals("req-401", failure.requestId)
        assertNull(h.store.session)
        h.close()
    }

    @Test
    fun `403 is Forbidden with the session intact`() = runTest {
        val h = Harness()
        h.servers.on(DELTAS) { json(apiError("reader.forbidden", "req-403"), HttpStatusCode.Forbidden) }

        val failure = runCatching { h.operations().deltas() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.Forbidden)
        assertEquals("req-403", (failure as ReaderAuthException.Forbidden).requestId)
        assertNotNull(h.store.session)
        h.close()
    }

    @Test
    fun `429 waits once and then reports try later`() = runTest {
        val h = Harness()
        h.servers.on(PROGRESS) {
            json(apiError("rate.limited", "req-429", retryable = true), HttpStatusCode.TooManyRequests, "Retry-After" to "7")
        }

        val failure = runCatching { h.operations().progress() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.TryLater)
        assertEquals(429, (failure as ReaderAuthException.TryLater).status)
        assertEquals(7.seconds, failure.retryAfter)
        assertEquals(listOf(7.seconds), h.waiter.waits)
        assertEquals(2, h.servers.requestsTo(ReaderLibraryClient.PROGRESS_PATH).size)
        h.close()
    }

    @Test
    fun `a 502 jwks dependency failure waits once and then reports try later`() = runTest {
        val h = Harness()
        h.servers.on(LIBRARY) { json(apiError("auth.jwks_dependency_failed", "req-502"), HttpStatusCode.BadGateway) }

        val failure = runCatching { h.operations().library() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.TryLater)
        assertEquals(502, (failure as ReaderAuthException.TryLater).status)
        assertEquals(2, h.servers.requestsTo(ReaderLibraryClient.LIBRARY_PATH).size)
        assertNotNull(h.store.session)
        h.close()
    }

    @Test
    fun `any other 502 is an ApiError carrying the server's code and request id`() = runTest {
        val h = Harness()
        h.servers.on(LIBRARY) { json(apiError("db.unavailable", "req-502b", retryable = true), HttpStatusCode.BadGateway) }

        val failure = runCatching { h.operations().library() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.ApiError)
        assertEquals("db.unavailable", (failure as ReaderAuthException.ApiError).code)
        assertEquals("req-502b", failure.requestId)
        assertEquals(1, h.servers.requestsTo(ReaderLibraryClient.LIBRARY_PATH).size)
        h.close()
    }

    @Test
    fun `a network failure clears nothing and never becomes a new exception type`() = runTest {
        val h = Harness()
        h.servers.on(LIBRARY) { networkFailure() }

        val failure = runCatching { h.operations().library() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.NetworkUnavailable)
        assertNotNull(h.store.session)
        h.close()
    }

    @Test
    fun `a signed-out client never reaches the network`() = runTest {
        val h = Harness(signedIn = false)

        val failure = runCatching { h.operations().library() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.SignedOut)
        assertTrue(h.servers.requests.isEmpty())
        h.close()
    }

    /** A 200 the module cannot read is reported as what it is: an unusable answer. */
    @Test
    fun `a body that does not match the pinned contract is an ApiError, not a crash`() = runTest {
        val h = Harness()
        h.servers.on(LIBRARY) { json("""{"request_id":"req-bad","items":[{"book":{"id":"x"}}]}""") }

        val failure = runCatching { h.operations().library() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.ApiError)
        assertEquals("client.contract_mismatch", (failure as ReaderAuthException.ApiError).code)
        assertEquals("req-bad", failure.requestId)
        assertTrue(failure.description.contains(ReaderLibraryClient.LIBRARY_PATH))
        h.close()
    }

    // -------------------------------------------------- forward compatibility

    /** A value reader-api adds later must not stop an older client from reading the rest. */
    @Test
    fun `an enum member this client does not know decodes as unknown`() = runTest {
        val h = Harness()
        h.servers.on(LIBRARY) { json(LIBRARY_BODY.replace("\"status\": \"reading\"", "\"status\": \"lending\"")) }

        val item = h.operations().library().items.single()

        assertEquals(ReaderLibraryStatus.UNKNOWN, item.status)
        assertEquals("Tractatus", item.book.title)
        h.close()
    }

    private fun envelope(key: String = "key-1", payloadValue: String = "reading") = ReaderSyncMutationEnvelope(
        idempotencyKey = key,
        resourceType = ReaderResourceType.LIBRARY_ITEM,
        resourceId = BOOK_ID,
        mutationKind = ReaderMutationKind.UPSERT,
        baseRevision = 6,
        payload = buildJsonObject { put("status", payloadValue) },
    )
}
