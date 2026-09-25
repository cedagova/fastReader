package com.cedagova.reader.library.imports

import com.cedagova.reader.auth.testing.FakeClock
import com.cedagova.reader.auth.testing.FakeServers
import com.cedagova.reader.auth.testing.TestSession
import com.cedagova.reader.auth.testing.json
import com.cedagova.reader.library.ReaderLibraryClient
import com.cedagova.reader.library.model.CreatePublicationImportRequest
import com.cedagova.reader.library.model.PublicationFailureCategory
import com.cedagova.reader.library.model.PublicationFormat
import com.cedagova.reader.library.model.PublicationImportStatus
import com.cedagova.reader.library.testing.ReaderLibraryHarness
import com.cedagova.reader.library.testing.publicationTransferClientOver
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole add, end to end, against a real reader-api client and a real
 * storage provider — both mock-transport, neither stubbed.
 *
 * The acceptance criteria of #116 are here: a full transfer in more than one
 * chunk followed by completion and `ready`; an interrupted transfer resumed
 * from the provider's offset; an expired grant re-admitted with the same
 * `client_import_id`; each failure category typed; `enabled: false` typed; a
 * size above the policy cap refused before any admission with the cap read
 * from the policy; and no bearer on any storage request.
 *
 * The session's access token is a deliberately recognisable string so that
 * "the bearer never reaches Storage" can be asserted as an absence of *that
 * value*, not merely of a header name.
 */
class PublicationImportEngineTest {

    private val api = FakeServers()
    private val storage = FakeStorage()

    // Anchored, never `FakeClock()`'s wall-clock default. Grant expiry is a
    // comparison between this clock and [GRANT_EXPIRY], so a clock that moved
    // with the calendar would silently flip every resume onto the expired-grant
    // path once the fixture date passed — the suite would go from proving the
    // HEAD resume to proving nothing, without a line changing.
    private val clock = FakeClock(Instant.parse(NOW))
    private val harness = ReaderLibraryHarness(api, clock)
    private val transfer = publicationTransferClientOver(storage.engine)

    private val bytes = publicationBytes(SIZE)
    private val source = InMemoryPublication(bytes)

    private suspend fun engine(): PublicationImportEngine {
        harness.storedSession = TestSession(accessToken = SESSION_TOKEN, expiresAt = clock.now + 3600.seconds)
        return PublicationImportEngine(harness.operations(), transfer, clock)
    }

    @After
    fun tearDown() {
        transfer.close()
    }

    // ---- the happy path ------------------------------------------------------------------------

    @Test
    fun `an add reads the policy, admits with consent, transfers in chunks, completes and becomes ready`() = runTest {
        api.on(POLICY) { json(policyBody()) }
        api.on(ADMIT) { json(admissionBody(created = true)) }
        api.on(COMPLETE) { json(importBody(status = "verifying_upload")) }
        api.on(STATUS) { json(importBody(status = "ready", canonicalBookId = BOOK_ID)) }

        val step = engine().start(ACCOUNT, source, UploadConsent.GRANTED)

        val transferred = step as PublicationImportStep.Transferred
        assertEquals(PublicationImportStatus.VERIFYING_UPLOAD, transferred.record.status)
        assertEquals(IMPORT_ID, transferred.record.importId)
        assertEquals(SIZE.toLong(), transferred.record.uploadedOffset)
        assertArrayEquals("the provider holds exactly the book", bytes, storage.received)
        assertTrue("a $SIZE-byte book in $CHUNK-byte chunks is more than one PATCH", storage.patchSizes.size >= 2)
        assertTrue("no chunk exceeded the grant's size", storage.patchSizes.all { it <= CHUNK })

        // The admission body is the contract's, field for field.
        val admitted = Json.parseToJsonElement(
            api.requestsTo(ReaderLibraryClient.IMPORTS_PATH).single().body,
        ).jsonObject
        assertEquals(
            PublicationImportRecord.derive(ACCOUNT, source.sha256),
            admitted["client_import_id"]!!.jsonPrimitive.content,
        )
        assertEquals(
            CreatePublicationImportRequest.PROMOTION_SOURCE_DEVICE_ONLY,
            admitted["promotion_source"]!!.jsonPrimitive.content,
        )
        assertEquals(
            CreatePublicationImportRequest.OWNERSHIP_INTENT_ACCOUNT_LIBRARY,
            admitted["ownership_intent"]!!.jsonPrimitive.content,
        )
        assertEquals(true, admitted["upload_consent"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("epub", admitted["source_format"]!!.jsonPrimitive.content)
        assertEquals(EPUB_MIME, admitted["source_mime_type"]!!.jsonPrimitive.content)
        assertEquals(SIZE.toString(), admitted["size_bytes"]!!.jsonPrimitive.content)
        assertEquals(source.sha256, admitted["sha256"]!!.jsonPrimitive.content)
        assertEquals("wittgenstein.epub", admitted["original_file_name"]!!.jsonPrimitive.content)

        val ready = engine().refresh(transferred.record)
        assertEquals(PublicationImportStatus.READY, ready.status)
        assertTrue(ready.isTerminal)
        assertEquals("a ready import binds the device book to its account row (AD-23)", BOOK_ID, ready.canonicalBookId)
    }

    /**
     * The whole reason the transfer is a separate client: the session goes to
     * reader-api and nowhere else.
     */
    @Test
    fun `the session reaches reader-api and never Storage`() = runTest {
        api.on(POLICY) { json(policyBody()) }
        api.on(ADMIT) { json(admissionBody(created = true)) }
        api.on(COMPLETE) { json(importBody(status = "verifying_upload")) }

        engine().start(ACCOUNT, source, UploadConsent.GRANTED)

        assertTrue(
            "every reader-api request carries the session",
            api.requests.filter { it.path.startsWith(ReaderLibraryClient.IMPORTS_PATH) }
                .all { it.bearer == SESSION_TOKEN },
        )
        assertTrue("the provider must have seen the transfer", storage.requests.isNotEmpty())
        storage.requests.forEach { request ->
            assertFalse(
                "a storage request carried an Authorization header",
                request.headers.keys.any { it.equals("Authorization", ignoreCase = true) },
            )
            assertFalse(
                "a storage request carried the session's access token",
                request.headers.values.any { it.contains(SESSION_TOKEN) },
            )
        }
    }

    // ---- refusals the policy already implies ---------------------------------------------------

    @Test
    fun `a size above the policy's cap is refused before any admission, with the policy's cap`() = runTest {
        api.on(POLICY) { json(policyBody(maxSourceBytes = SMALL_CAP)) }

        val step = engine().start(ACCOUNT, source, UploadConsent.GRANTED)

        val refusal = (step as PublicationImportStep.Refused).refusal as PublicationImportRefusal.TooLarge
        assertEquals("the cap is quoted from this attempt's policy", SMALL_CAP, refusal.maxSourceBytes)
        assertEquals(SIZE.toLong(), refusal.sizeBytes)
        assertEquals(PublicationFormat.EPUB, refusal.format)
        assertEquals(PublicationFailureCategory.TOO_LARGE, refusal.category)
        assertEquals("nothing may be admitted", 0, api.requestsTo(ReaderLibraryClient.IMPORTS_PATH).size)
        assertEquals("and nothing may reach Storage", 0, storage.requests.size)
        assertNull(step.record)
    }

    @Test
    fun `imports disabled is a typed refusal and sends nothing`() = runTest {
        api.on(POLICY) { json(policyBody(enabled = false)) }

        val step = engine().start(ACCOUNT, source, UploadConsent.GRANTED)

        val refusal = (step as PublicationImportStep.Refused).refusal as PublicationImportRefusal.ImportsDisabled
        assertEquals(REQUEST_ID, refusal.requestId)
        assertNull("imports being off is not a failure of this book", refusal.category)
        assertEquals(0, api.requestsTo(ReaderLibraryClient.IMPORTS_PATH).size)
        assertEquals(0, storage.requests.size)
    }

    @Test
    fun `a type the policy does not list is refused with the policy's own list`() = runTest {
        api.on(POLICY) { json(policyBody()) }
        val other = InMemoryPublication(bytes, mimeType = "application/x-mobipocket-ebook")

        val step = engine().start(ACCOUNT, other, UploadConsent.GRANTED)

        val refusal = (step as PublicationImportStep.Refused).refusal as PublicationImportRefusal.FormatNotSupported
        assertEquals("application/x-mobipocket-ebook", refusal.mimeType)
        assertEquals(listOf(EPUB_MIME, "application/pdf"), refusal.supportedMimeTypes)
        assertEquals(PublicationFailureCategory.UNSUPPORTED, refusal.category)
        assertEquals(0, api.requestsTo(ReaderLibraryClient.IMPORTS_PATH).size)
    }

    // ---- consent -------------------------------------------------------------------------------

    /**
     * The runtime half of REQ-505. The compile-time half is [UploadConsent]:
     * there is no way to *call* an import without naming consent. This is what
     * happens if a caller builds the request by hand anyway.
     */
    @Test
    fun `an admission without explicit consent never becomes a request`() = runTest {
        val operations = harness.operations()
        val request = CreatePublicationImportRequest(
            clientImportId = PublicationImportRecord.derive(ACCOUNT, source.sha256),
            sourceFormat = PublicationFormat.EPUB,
            sourceMimeType = EPUB_MIME,
            sizeBytes = source.sizeBytes,
            sha256 = source.sha256,
        )

        assertRaises<IllegalArgumentException> { operations.admitImport(request) }
        assertRaises<IllegalArgumentException> { operations.admitImport(request.copy(uploadConsent = false)) }

        assertEquals("no request may exist without consent", 0, api.requestsTo(ReaderLibraryClient.IMPORTS_PATH).size)
        assertEquals(0, storage.requests.size)
    }

    // ---- resume --------------------------------------------------------------------------------

    /**
     * App death mid-transfer (REQ-507): the record is all that survives, and it
     * is enough. The re-admission returns the same import with `created: false`
     * — one import, never two — and the provider's `HEAD` says where to carry
     * on from.
     */
    @Test
    fun `an interrupted transfer resumes from the stored record without a second import`() = runTest {
        api.on(POLICY) { json(policyBody()) }
        api.queue(
            ADMIT,
            { json(admissionBody(created = true)) },
            { json(admissionBody(created = false)) },
        )
        api.on(COMPLETE) { json(importBody(status = "verifying_upload")) }

        storage.interruptNextPatchAfter = KEPT
        val first = engine().start(ACCOUNT, source, UploadConsent.GRANTED)

        val interrupted = first as PublicationImportStep.Interrupted
        // The provider kept KEPT bytes of the chunk whose connection then died,
        // and never got to say so. The record claims only what was acknowledged:
        // a lower bound is recoverable, an over-count would skip bytes that were
        // never stored. The HEAD on resume below is what supplies the truth.
        assertEquals(
            "the record claims only what the provider acknowledged",
            0L,
            interrupted.record.uploadedOffset,
        )
        assertEquals(TUS_LOCATION, interrupted.record.transferLocation)
        assertEquals(IMPORT_ID, interrupted.record.importId)

        // A fresh engine, as a fresh process would have, carrying only the record.
        val second = engine().resume(interrupted.record, InMemoryPublication(bytes), UploadConsent.GRANTED)

        assertTrue(second is PublicationImportStep.Transferred)
        assertArrayEquals("no byte was re-sent and none was skipped", bytes, storage.received)
        // The resume picks up at the provider's own mid-chunk offset: not at the
        // record's lower bound (0, which would re-send) and not past it.
        assertTrue(
            "the resume PATCHed from the provider's durable offset, got ${storage.patchOffsets}",
            storage.patchOffsets.contains(KEPT.toLong()),
        )
        assertTrue(
            "and no PATCH ever declared an offset inside what was already stored",
            storage.patchOffsets.none { it in 1 until KEPT.toLong() },
        )

        val admissions = api.requestsTo(ReaderLibraryClient.IMPORTS_PATH)
        assertEquals("two admissions", 2, admissions.size)
        val ids = admissions.map {
            Json.parseToJsonElement(it.body).jsonObject["client_import_id"]!!.jsonPrimitive.content
        }
        assertEquals("the same client_import_id both times", ids[0], ids[1])
        assertEquals("the resume re-read the provider's offset", 1, storage.countOf("HEAD"))
        assertEquals("and created only one resumable upload", 1, storage.countOf("POST"))
    }

    /**
     * An expired grant. The record's own expiry is past, so the resume does not
     * spend a round trip learning it: it re-admits with the same
     * `client_import_id`, gets a fresh grant, and starts a fresh transfer.
     */
    @Test
    fun `an expired grant re-admits with the same id and starts a fresh transfer`() = runTest {
        api.on(POLICY) { json(policyBody()) }
        api.queue(
            ADMIT,
            { json(admissionBody(created = true)) },
            { json(admissionBody(created = false, expiresAt = LATER_EXPIRY)) },
        )
        api.on(COMPLETE) { json(importBody(status = "verifying_upload")) }

        storage.interruptNextPatchAfter = KEPT
        val interrupted = engine().start(ACCOUNT, source, UploadConsent.GRANTED) as PublicationImportStep.Interrupted
        assertEquals(GRANT_EXPIRY, interrupted.record.grantExpiresAt)
        val headsBefore = storage.countOf("HEAD")

        // The device wakes up after the grant's two hours are gone.
        clock.now = kotlin.time.Instant.parse(GRANT_EXPIRY) + 1.seconds
        val resumed = engine().resume(interrupted.record, InMemoryPublication(bytes), UploadConsent.GRANTED)

        assertTrue(resumed is PublicationImportStep.Transferred)
        assertEquals(
            "a spent grant is not HEADed: the answer is already known",
            headsBefore,
            storage.countOf("HEAD"),
        )
        assertEquals("a fresh transfer is a fresh creation", 2, storage.countOf("POST"))
        assertArrayEquals("and the fresh transfer sent the whole book", bytes, storage.received)

        val ids = api.requestsTo(ReaderLibraryClient.IMPORTS_PATH)
            .map { Json.parseToJsonElement(it.body).jsonObject["client_import_id"]!!.jsonPrimitive.content }
        assertEquals(2, ids.size)
        assertEquals("the same admission, replayed", ids[0], ids[1])
    }

    /**
     * A transient failure on the resume's `HEAD` must not cost the owner the
     * upload.
     *
     * A dropped connection, a socket timeout or a provider 5xx says nothing
     * about the object: the provider is still holding the bytes it had. Only a
     * rejected grant proves the location is gone. Throwing the location away on
     * a blip would re-send the whole book — up to the hosted cap, over mobile
     * data, on exactly the bad link that caused the blip.
     *
     * The expiry is asserted too, and deliberately: the record has been through
     * `withAdmission` by then, so its own `grantExpiresAt` is the *fresh*
     * grant's. A kept location wearing a fresh window would never be judged
     * spent again.
     */
    @Test
    fun `a transient failure on the resume's HEAD keeps the location and re-sends nothing`() = runTest {
        api.on(POLICY) { json(policyBody()) }
        api.queue(
            ADMIT,
            { json(admissionBody(created = true)) },
            { json(admissionBody(created = false, expiresAt = LATER_EXPIRY)) },
        )
        api.on(COMPLETE) { json(importBody(status = "verifying_upload")) }

        storage.interruptNextPatchAfter = KEPT
        val interrupted = engine().start(ACCOUNT, source, UploadConsent.GRANTED) as PublicationImportStep.Interrupted

        // The provider is unwell exactly when the resume asks where it is.
        storage.rejectHeadWith = 503
        val blipped = engine().resume(interrupted.record, InMemoryPublication(bytes), UploadConsent.GRANTED)

        val stalled = blipped as PublicationImportStep.Interrupted
        assertEquals(
            "a provider 5xx does not prove the upload is gone: the location is kept",
            TUS_LOCATION,
            stalled.record.transferLocation,
        )
        assertEquals(
            "and it keeps the window it was issued under, never the fresh grant's",
            GRANT_EXPIRY,
            stalled.record.grantExpiresAt,
        )
        assertEquals("nothing new was created while the provider was unwell", 1, storage.countOf("POST"))

        // The provider recovers; the owner tries again.
        storage.rejectHeadWith = null
        val resumed = engine().resume(stalled.record, InMemoryPublication(bytes), UploadConsent.GRANTED)

        assertTrue(resumed is PublicationImportStep.Transferred)
        assertEquals("the upload survived the blip: no second creation", 1, storage.countOf("POST"))
        assertArrayEquals("and not one byte was re-sent", bytes, storage.received)
        assertTrue(
            "the recovery PATCHed from the provider's durable offset, got ${storage.patchOffsets}",
            storage.patchOffsets.contains(KEPT.toLong()),
        )
    }

    /** The other way a grant can be spent: the provider discards the upload. */
    @Test
    fun `a location the provider no longer knows starts a fresh transfer`() = runTest {
        api.on(POLICY) { json(policyBody()) }
        api.queue(ADMIT, { json(admissionBody(created = true)) }, { json(admissionBody(created = false)) })
        api.on(COMPLETE) { json(importBody(status = "verifying_upload")) }

        storage.interruptNextPatchAfter = KEPT
        val interrupted = engine().start(ACCOUNT, source, UploadConsent.GRANTED) as PublicationImportStep.Interrupted

        storage.rejectHeadWith = 404
        val resumed = engine().resume(interrupted.record, InMemoryPublication(bytes), UploadConsent.GRANTED)

        assertTrue(resumed is PublicationImportStep.Transferred)
        assertEquals(2, storage.countOf("POST"))
        assertArrayEquals(bytes, storage.received)
    }

    /**
     * #142 at the engine: a foreign `Location` settles as the contract's
     * `upload` failure, not retryable, with no location kept and nothing sent
     * to the other origin.
     */
    @Test
    fun `a creation Location on another origin fails the upload and keeps no location`() = runTest {
        api.on(POLICY) { json(policyBody()) }
        api.on(ADMIT) { json(admissionBody(created = true)) }
        storage.location = "https://elsewhere.test/upload/abc"

        val step = engine().start(ACCOUNT, source, UploadConsent.GRANTED)

        assertTrue("got $step", step is PublicationImportStep.Failed)
        step as PublicationImportStep.Failed
        assertEquals(PublicationFailureCategory.UPLOAD, step.category)
        assertEquals(false, step.record.failureRetryable)
        assertNull("a foreign location is never stored", step.record.transferLocation)
        assertEquals(listOf("POST"), storage.requests.map { it.method })
        assertTrue(storage.requests.none { it.host == "elsewhere.test" })
    }

    // ---- failures ------------------------------------------------------------------------------

    /**
     * Every category the contract declares, surfaced as itself.
     *
     * A host renders the backend's word (REQ-507); this is the proof that none
     * of the eight is flattened into "something went wrong" on the way out.
     */
    @Test
    fun `each failure category is surfaced typed`() = runTest {
        val categories = PublicationFailureCategory.entries.filter { it != PublicationFailureCategory.UNKNOWN }
        assertEquals("the contract declares eight categories", 8, categories.size)

        val engine = engine()
        val record = PublicationImportRecord
            .forSource(ACCOUNT, source, PublicationFormat.EPUB)
            .copy(importId = IMPORT_ID)

        categories.forEach { category ->
            val wire = category.name.lowercase()
            api.on(STATUS) { json(importBody(status = "failed", failureCategory = wire)) }

            val failed = engine.refresh(record)

            assertEquals("category $wire", category, failed.failureCategory)
            assertEquals(PublicationImportStatus.FAILED, failed.status)
            assertTrue(failed.isTerminal)
            assertEquals(false, failed.failureRetryable)
        }
    }

    /** A terminal record the admission itself replays is a typed failure, not a transfer. */
    @Test
    fun `a replayed admission that already failed is reported as failed`() = runTest {
        api.on(POLICY) { json(policyBody()) }
        api.on(ADMIT) {
            json(admissionBody(created = false, status = "failed", failureCategory = "malformed", withGrant = false))
        }

        val step = engine().start(ACCOUNT, source, UploadConsent.GRANTED)

        val failed = step as PublicationImportStep.Failed
        assertEquals(PublicationFailureCategory.MALFORMED, failed.category)
        assertEquals("a failed import sends no bytes", 0, storage.requests.size)
    }

    // ---- cancel --------------------------------------------------------------------------------

    @Test
    fun `cancel is explicit and carries the contract's reason`() = runTest {
        api.on(CANCEL) { json(importBody(status = "cancelled", failureCategory = "cancelled")) }
        val record = PublicationImportRecord
            .forSource(ACCOUNT, source, PublicationFormat.EPUB)
            .copy(importId = IMPORT_ID)

        val cancelled = engine().cancel(record)

        assertEquals(PublicationImportStatus.CANCELLED, cancelled.status)
        assertEquals(PublicationFailureCategory.CANCELLED, cancelled.failureCategory)
        val body = Json.parseToJsonElement(api.requests.single { it.path.endsWith("/cancel") }.body).jsonObject
        assertEquals("cancelled_by_actor", body["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cancelling an import that was never admitted calls nothing`() = runTest {
        val record = PublicationImportRecord.forSource(ACCOUNT, source, PublicationFormat.EPUB)

        val cancelled = engine().cancel(record)

        assertEquals(PublicationImportStatus.CANCELLED, cancelled.status)
        assertEquals(0, api.requestsTo(ReaderLibraryClient.IMPORTS_PATH).size)
    }

    // ---- the derived identity ------------------------------------------------------------------

    @Test
    fun `a client import id is the same file in the same account, and nothing else`() {
        val same = PublicationImportRecord.derive(ACCOUNT, source.sha256)
        assertEquals("stable across app death", same, PublicationImportRecord.derive(ACCOUNT, source.sha256))
        assertEquals(
            "the contract's two sha256 spellings are one identity",
            same,
            PublicationImportRecord.derive(ACCOUNT, "sha256:${source.sha256.uppercase()}"),
        )
        assertNotEquals(
            "another account is another import",
            same,
            PublicationImportRecord.derive("other-account", source.sha256),
        )
        assertNotEquals(
            "another book is another import",
            same,
            PublicationImportRecord.derive(ACCOUNT, sha256Hex(publicationBytes(SIZE + 1))),
        )
        assertTrue(same.startsWith(PublicationImportRecord.CLIENT_IMPORT_ID_PREFIX))
        assertTrue(
            "and it fits the contract's 255 characters",
            same.length <= CreatePublicationImportRequest.MAX_CLIENT_IMPORT_ID_LENGTH,
        )
        assertFalse("the account never travels in clear", same.contains(ACCOUNT))
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private fun policyBody(enabled: Boolean = true, maxSourceBytes: Long = HOSTED_CAP) = """
    {
      "request_id": "$REQUEST_ID",
      "enabled": $enabled,
      "ownership": {
        "contract_version": "reader.publication-ownership.v1",
        "device_only_formats": ["epub", "pdf"],
        "device_only_api_calls_required": false,
        "device_only_upload_bytes": false,
        "device_only_account_mutations": false,
        "promotion_upload_consent_required": true,
        "local_state_retention": "until_account_and_activity_admission",
        "activity_resource_types": ["reading_progress", "note", "bookmark"]
      },
      "formats": [
        {
          "format": "epub",
          "extensions": [".epub"],
          "mime_types": ["$EPUB_MIME"],
          "max_source_bytes": $maxSourceBytes,
          "archive": true,
          "device_renderability": "device_native",
          "device_only_allowed": true,
          "account_admission": "upload_only",
          "account_connectivity_required": true,
          "explicit_upload_consent_required": true
        },
        {
          "format": "pdf",
          "extensions": [".pdf"],
          "mime_types": ["application/pdf"],
          "max_source_bytes": $maxSourceBytes,
          "archive": false,
          "device_renderability": "device_native",
          "account_admission": "upload_only"
        }
      ],
      "archive": {"max_entries": 4096, "max_expanded_bytes": 1073741824, "max_expansion_ratio": 100},
      "max_active_imports": 3,
      "max_active_bytes": 157286400,
      "a_field_this_client_does_not_model": true
    }
    """.trimIndent()

    private fun importObject(status: String, failureCategory: String? = null, canonicalBookId: String? = null) = """
    {
      "id": "$IMPORT_ID",
      "client_import_id": "${PublicationImportRecord.derive(ACCOUNT, source.sha256)}",
      "source_format": "epub",
      "source_mime_type": "$EPUB_MIME",
      "original_file_name": "wittgenstein.epub",
      "expected_sha256": "${source.sha256}",
      "expected_size_bytes": $SIZE,
      "status": "$status",
      "promotion": {
        "contract_version": "reader.publication-ownership.v1",
        "source": "device_only",
        "destination": "account_library",
        "account_admission": ${if (canonicalBookId != null) "\"admitted\"" else "\"pending\""},
        ${if (canonicalBookId != null) "\"account_book_id\": \"$canonicalBookId\"," else ""}
        "local_state_disposition": "retain",
        "activity_continuity": ${if (canonicalBookId != null) "\"replay_to_admitted_identity\"" else "\"wait_for_admission\""},
        "local_state_may_be_discarded": false
      },
      ${if (failureCategory != null) """"failure": {"category": "$failureCategory", "retryable": false, "broken_account_entry_created": false, "local_state_disposition": "retained", "external_source_disposition": "untouched"},""" else ""}
      ${if (canonicalBookId != null) """"canonical_book_id": "$canonicalBookId", "canonical_asset_checksum": "sha256:${source.sha256}",""" else ""}
      "created_at": "2026-09-17T10:00:00Z",
      "updated_at": "2026-09-17T10:05:00Z"
    }
    """.trimIndent()

    private fun importBody(status: String, failureCategory: String? = null, canonicalBookId: String? = null) =
        """{"request_id": "$REQUEST_ID", "import": ${importObject(status, failureCategory, canonicalBookId)}}"""

    private fun admissionBody(
        created: Boolean,
        status: String = "pending_upload",
        failureCategory: String? = null,
        withGrant: Boolean = true,
        expiresAt: String = GRANT_EXPIRY,
    ) = """
    {
      "request_id": "$REQUEST_ID",
      "import": ${importObject(status, failureCategory)},
      "created": $created
      ${if (withGrant) "," + grantJson(expiresAt) else ""}
    }
    """.trimIndent()

    private fun grantJson(expiresAt: String) = """
      "transfer_grant": {
        "protocol": "tus",
        "method": "POST",
        "endpoint": "$TUS_ENDPOINT",
        "headers": {${GRANT_HEADERS.entries.joinToString(",") { "\"${it.key}\": \"${it.value}\"" }}},
        "metadata": {"bucket": "publications", "objectName": "reader/0f1e/source.epub"},
        "chunk_size_bytes": $CHUNK,
        "expires_at": "$expiresAt",
        "checksum": "sha256:${source.sha256}",
        "size_bytes": $SIZE
      }
    """.trimIndent()

    private companion object {
        const val ACCOUNT = "9a7e2c10-0000-4000-8000-00000000000a"
        const val IMPORT_ID = "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b"
        const val BOOK_ID = "1f0f1c9e-6a3c-4f8a-9c2b-2f1c7d3e4a5b"
        const val REQUEST_ID = "c0ffee00-0000-4000-8000-000000000116"

        /** Recognisable on purpose: its absence at Storage is the assertion. */
        const val SESSION_TOKEN = "session-token-storage-must-never-see"

        const val SIZE = 5_000
        const val CHUNK = 2_048L
        const val KEPT = 700
        const val HOSTED_CAP = 52_428_800L
        const val SMALL_CAP = 4_096L

        /** The device's clock for every test here. Fixed, so expiry is arithmetic, not a date. */
        const val NOW = "2026-09-17T10:00:00Z"

        /** Two hours ahead of [NOW]: a grant whose window is open. */
        const val GRANT_EXPIRY = "2026-09-17T12:00:00Z"

        /** Five hours ahead of [NOW]: the fresh grant a re-admission hands out. */
        const val LATER_EXPIRY = "2026-09-17T15:00:00Z"

        val POLICY = "GET ${ReaderLibraryClient.IMPORT_POLICY_PATH}"
        val ADMIT = "POST ${ReaderLibraryClient.IMPORTS_PATH}"
        val STATUS = "GET ${ReaderLibraryClient.IMPORTS_PATH}/$IMPORT_ID"
        val COMPLETE = "POST ${ReaderLibraryClient.IMPORTS_PATH}/$IMPORT_ID/complete"
        val CANCEL = "POST ${ReaderLibraryClient.IMPORTS_PATH}/$IMPORT_ID/cancel"
    }
}
