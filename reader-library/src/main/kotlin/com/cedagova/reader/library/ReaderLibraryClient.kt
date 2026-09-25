package com.cedagova.reader.library

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.auth.api.ReaderApiClient
import com.cedagova.reader.library.model.CancelPublicationImportRequest
import com.cedagova.reader.library.model.CreatePublicationImportRequest
import com.cedagova.reader.library.model.PublicationImportAdmissionResponse
import com.cedagova.reader.library.model.PublicationImportPolicyResponse
import com.cedagova.reader.library.model.PublicationImportResponse
import com.cedagova.reader.library.model.ReaderAssetGrantResponse
import com.cedagova.reader.library.model.ReaderCapabilityEntry
import com.cedagova.reader.library.model.ReaderCapabilityKey
import com.cedagova.reader.library.model.ReaderLibraryResponse
import com.cedagova.reader.library.model.ReaderProgressListResponse
import com.cedagova.reader.library.model.ReaderPublicationImportCapability
import com.cedagova.reader.library.model.ReaderSyncCapability
import com.cedagova.reader.library.model.ReaderSyncDeltaResponse
import com.cedagova.reader.library.model.ReaderSyncMutationBatchRequest
import com.cedagova.reader.library.model.ReaderSyncMutationBatchResponse
import com.cedagova.reader.library.model.ReaderSyncMutationEnvelope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The account-library client: every typed operation of
 * [ReaderLibraryOperations], over the one authenticated client `:reader-auth`
 * owns.
 *
 * It holds no session, no token, no refresh and no retry of its own. Every call
 * goes through [ReaderApiClient], so the bearer, `X-Reader-Client`,
 * `X-Request-ID`, the 10 s timeout, the single-flight refresh and the whole
 * error policy are `:reader-auth`'s, unchanged — which is exactly why tokens
 * never leave that module.
 *
 * What this class adds is shape: the request bodies it sends and the response
 * documents it reads are the ones `reader-auth/contracts/reader-api.openapi.json` declares,
 * and `ReaderLibraryContractTest` fails the build when a model and that
 * document disagree.
 */
public class ReaderLibraryClient(private val api: ReaderApiClient) : ReaderLibraryOperations {

    /**
     * The one JSON body builder: a request model in, the object
     * [ReaderApiClient.post] puts on the wire out.
     */
    private fun <T> body(serializer: KSerializer<T>, value: T): JsonObject =
        ReaderLibraryJson.encodeToJsonElement(serializer, value).jsonObject

    override suspend fun library(): ReaderLibraryResponse =
        decode(api.get(LIBRARY_PATH), ReaderLibraryResponse.serializer(), LIBRARY_PATH)

    override suspend fun progress(): ReaderProgressListResponse =
        decode(api.get(PROGRESS_PATH), ReaderProgressListResponse.serializer(), PROGRESS_PATH)

    override suspend fun applyMutations(mutations: List<ReaderSyncMutationEnvelope>): ReaderSyncMutationBatchResponse {
        require(mutations.size >= ReaderSyncMutationBatchRequest.MIN_MUTATIONS) {
            "a sync mutation batch needs at least ${ReaderSyncMutationBatchRequest.MIN_MUTATIONS} envelope"
        }
        require(mutations.size <= ReaderSyncMutationBatchRequest.MAX_MUTATIONS) {
            "a sync mutation batch holds at most ${ReaderSyncMutationBatchRequest.MAX_MUTATIONS} envelopes, not ${mutations.size}"
        }
        val body = ReaderLibraryJson.encodeToJsonElement(
            ReaderSyncMutationBatchRequest.serializer(),
            ReaderSyncMutationBatchRequest(mutations),
        ).jsonObject
        return decode(api.post(MUTATIONS_PATH, body), ReaderSyncMutationBatchResponse.serializer(), MUTATIONS_PATH)
    }

    override suspend fun deltas(afterCursor: String, limit: Int): ReaderSyncDeltaResponse {
        require(CURSOR.matches(afterCursor)) { "a delta cursor is a decimal string, not '$afterCursor'" }
        require(limit in MIN_DELTA_LIMIT..MAX_DELTA_LIMIT) {
            "a delta limit is $MIN_DELTA_LIMIT..$MAX_DELTA_LIMIT, not $limit"
        }
        val path = "$DELTAS_PATH?after_cursor=$afterCursor&limit=$limit"
        return decode(api.get(path), ReaderSyncDeltaResponse.serializer(), DELTAS_PATH)
    }

    override suspend fun syncCapability(): ReaderSyncCapability {
        val document = api.capabilities()
        val entries = document[CAPABILITIES_FIELD] as? JsonArray ?: return ReaderSyncCapability.UNDECLARED
        val entry = entries.firstOrNull { element ->
            val key = (element as? JsonObject)?.get(CAPABILITY_KEY_FIELD) as? JsonPrimitive
            key?.contentOrNull == SYNC_CAPABILITY_KEY
        } ?: return ReaderSyncCapability.UNDECLARED
        val decoded = decode(entry.jsonObject, ReaderCapabilityEntry.serializer(), ReaderApiClient.CAPABILITIES_PATH)
        return ReaderSyncCapability(
            availability = decoded.availability,
            reason = decoded.reason,
            quota = decoded.quota,
            declared = decoded.key == ReaderCapabilityKey.SYNC_V1,
        )
    }

    override suspend fun publicationImportCapability(): ReaderPublicationImportCapability {
        val document = api.capabilities()
        val entries = (document[CAPABILITIES_FIELD] as? JsonArray).orEmpty().filter { element ->
            val key = (element as? JsonObject)?.get(CAPABILITY_KEY_FIELD) as? JsonPrimitive
            key?.contentOrNull == PUBLICATION_IMPORT_CAPABILITY_KEY
        }
        // Exactly one, or it is not permission (core.md §6): a duplicate is as
        // unusable as an absence, and neither is decoded as if it were the answer.
        val only = entries.singleOrNull() ?: return ReaderPublicationImportCapability.undeclared(entries.size)
        val decoded = decode(only.jsonObject, ReaderCapabilityEntry.serializer(), ReaderApiClient.CAPABILITIES_PATH)
        return ReaderPublicationImportCapability(
            availability = decoded.availability,
            reason = decoded.reason,
            entries = 1,
        )
    }

    // ---- Publication imports (#116) ---------------------------------------------------------

    override suspend fun importPolicy(): PublicationImportPolicyResponse =
        decode(api.get(IMPORT_POLICY_PATH), PublicationImportPolicyResponse.serializer(), IMPORT_POLICY_PATH)

    /**
     * The one place a publication-import admission is put on the wire, and so
     * the one place that can refuse to.
     *
     * The three requires below are not defensive noise. `upload_consent` is the
     * owner's explicit decision that this file's bytes may leave the device
     * (REQ-505); `promotion_source` and `ownership_intent` are what make this a
     * device-to-account promotion rather than some other admission. A request
     * missing any of them is a caller bug that must never reach stage, so it
     * fails here, before the request exists.
     */
    override suspend fun admitImport(request: CreatePublicationImportRequest): PublicationImportAdmissionResponse {
        require(request.uploadConsent == true) {
            "a publication import is admitted only with explicit upload_consent: the owner's bytes leave the device"
        }
        require(request.promotionSource == CreatePublicationImportRequest.PROMOTION_SOURCE_DEVICE_ONLY) {
            "promotion_source must be '${CreatePublicationImportRequest.PROMOTION_SOURCE_DEVICE_ONLY}', not '${request.promotionSource}'"
        }
        require(request.ownershipIntent == CreatePublicationImportRequest.OWNERSHIP_INTENT_ACCOUNT_LIBRARY) {
            "ownership_intent must be '${CreatePublicationImportRequest.OWNERSHIP_INTENT_ACCOUNT_LIBRARY}', not '${request.ownershipIntent}'"
        }
        require(
            request.clientImportId.isNotBlank() &&
                request.clientImportId.length <= CreatePublicationImportRequest.MAX_CLIENT_IMPORT_ID_LENGTH,
        ) {
            "a client_import_id is 1..${CreatePublicationImportRequest.MAX_CLIENT_IMPORT_ID_LENGTH} characters"
        }
        require(request.sizeBytes > 0) { "a source size is positive, not ${request.sizeBytes}" }
        require(SHA256.matches(request.sha256)) { "sha256 must be 64 hex characters, optionally 'sha256:'-prefixed" }
        val document = api.post(IMPORTS_PATH, body(CreatePublicationImportRequest.serializer(), request))
        return decode(document, PublicationImportAdmissionResponse.serializer(), IMPORTS_PATH)
    }

    override suspend fun importRecord(importId: String): PublicationImportResponse {
        val path = importPath(importId)
        return decode(api.get(path), PublicationImportResponse.serializer(), path)
    }

    override suspend fun completeImport(importId: String): PublicationImportResponse {
        // The route takes no body: reader-api observes the stored object itself
        // and never accepts an uploader-supplied digest. An empty object is
        // what a POST with nothing to say sends.
        val path = importPath(importId) + COMPLETE_SUFFIX
        return decode(api.post(path, EMPTY_BODY), PublicationImportResponse.serializer(), path)
    }

    override suspend fun cancelImport(importId: String, reason: String): PublicationImportResponse {
        require(reason.isNotBlank() && reason.length <= CancelPublicationImportRequest.MAX_REASON_LENGTH) {
            "a cancel reason is 1..${CancelPublicationImportRequest.MAX_REASON_LENGTH} characters"
        }
        val path = importPath(importId) + CANCEL_SUFFIX
        val document = api.post(
            path,
            body(CancelPublicationImportRequest.serializer(), CancelPublicationImportRequest(reason)),
        )
        return decode(document, PublicationImportResponse.serializer(), path)
    }

    /** `/reader/v1/imports/{id}`, with the id checked to be a path segment and nothing more. */
    private fun importPath(importId: String): String {
        require(IMPORT_ID.matches(importId)) { "an import id is a UUID, not '$importId'" }
        return "$IMPORTS_PATH/$importId"
    }

    // ---- Asset downloads (#118) -------------------------------------------------------------

    /**
     * The one place a download grant is asked for, and so the one place the
     * address of a book's bytes is decided: by reader-api, from an asset id.
     *
     * The route takes no body — the asset is the whole request — so an empty
     * object is what a POST with nothing to say sends, exactly as
     * [completeImport] does.
     */
    override suspend fun assetDownloadGrant(assetId: String): ReaderAssetGrantResponse {
        require(ASSET_ID.matches(assetId)) { "an asset id is a UUID, not '$assetId'" }
        val path = "$ASSETS_PATH/$assetId$DOWNLOAD_GRANT_SUFFIX"
        return decode(api.post(path, EMPTY_BODY), ReaderAssetGrantResponse.serializer(), path)
    }

    /**
     * The one place a contract mismatch becomes an error. A body that parsed as
     * JSON but does not match the pinned document is surfaced as the existing
     * [ReaderAuthException.ApiError] branch — this module adds no failure type
     * of its own — carrying the server's own `request_id` so the answer can be
     * found in a server log.
     */
    private fun <T> decode(document: JsonObject, serializer: KSerializer<T>, path: String): T = try {
        ReaderLibraryJson.decodeFromJsonElement(serializer, document)
    } catch (e: Exception) {
        throw ReaderAuthException.ApiError(
            status = CONTRACT_MISMATCH_STATUS,
            code = CONTRACT_MISMATCH_CODE,
            requestId = (document[REQUEST_ID_FIELD] as? JsonPrimitive)?.contentOrNull,
            description = "$path does not match the pinned reader-api contract: ${e.message}",
        )
    }

    public companion object {
        public const val LIBRARY_PATH: String = "/v1/reader/library"
        public const val PROGRESS_PATH: String = "/v1/reader/progress"
        public const val MUTATIONS_PATH: String = "/v1/reader/sync/mutations"
        public const val DELTAS_PATH: String = "/v1/reader/sync/deltas"

        // The publication-import routes (#116). Note the prefix: these are
        // `/reader/v1/...`, not `/v1/reader/...` like the four above. That is
        // the pinned document's own spelling, and
        // `every route the module calls is declared by the pinned document`
        // is what keeps this honest rather than plausible.
        public const val IMPORTS_PATH: String = "/reader/v1/imports"
        public const val IMPORT_POLICY_PATH: String = "/reader/v1/imports/policy"
        public const val COMPLETE_SUFFIX: String = "/complete"
        public const val CANCEL_SUFFIX: String = "/cancel"

        /** The document's own path-parameter name, for the contract test's route check. */
        public const val IMPORT_ID_TEMPLATE: String = "{ingestion_id}"

        // The asset download grant (#118). Back to the `/v1/reader/...` prefix,
        // again because that is the pinned document's own spelling.
        public const val ASSETS_PATH: String = "/v1/reader/assets"
        public const val DOWNLOAD_GRANT_SUFFIX: String = "/download-grant"

        /** The document's own path-parameter name, for the contract test's route check. */
        public const val ASSET_ID_TEMPLATE: String = "{asset_id}"

        /** The capability this module gates every sync request on. */
        public const val SYNC_CAPABILITY_KEY: String = "reader.sync.v1"

        /** The capability that decides whether "Add to account library" is offered (#139). */
        public const val PUBLICATION_IMPORT_CAPABILITY_KEY: String = "reader.publication-import.v1"

        /** The cursor a client that has never synced starts from. */
        public const val FIRST_CURSOR: String = "0"

        /** The document's own default page size. */
        public const val DEFAULT_DELTA_LIMIT: Int = 100
        public const val MIN_DELTA_LIMIT: Int = 1
        public const val MAX_DELTA_LIMIT: Int = 500

        /**
         * The status an unusable-but-successful body is reported under: the
         * transport succeeded, so there is no HTTP status to quote, and 200 is
         * what the server actually said.
         */
        private const val CONTRACT_MISMATCH_STATUS = 200
        private const val CONTRACT_MISMATCH_CODE = "client.contract_mismatch"
        private const val REQUEST_ID_FIELD = "request_id"
        private const val CAPABILITIES_FIELD = "capabilities"
        private const val CAPABILITY_KEY_FIELD = "key"
        private val CURSOR = Regex("^[0-9]+$")
        private val SHA256 = Regex("^(?:sha256:)?[0-9A-Fa-f]{64}$")
        private val IMPORT_ID = Regex("^[0-9a-fA-F-]{36}$")
        private val ASSET_ID = Regex("^[0-9a-fA-F-]{36}$")
        private val EMPTY_BODY = JsonObject(emptyMap())
    }
}
