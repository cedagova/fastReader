package com.cedagova.reader.library

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.auth.api.ReaderApiClient
import com.cedagova.reader.library.model.ReaderCapabilityEntry
import com.cedagova.reader.library.model.ReaderCapabilityKey
import com.cedagova.reader.library.model.ReaderLibraryResponse
import com.cedagova.reader.library.model.ReaderProgressListResponse
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
 * The account-library client: five typed operations over the one authenticated
 * client `:reader-auth` owns.
 *
 * It holds no session, no token, no refresh and no retry of its own. Every call
 * goes through [ReaderApiClient], so the bearer, `X-Reader-Client`,
 * `X-Request-ID`, the 10 s timeout, the single-flight refresh and the whole
 * error policy are `:reader-auth`'s, unchanged — which is exactly why tokens
 * never leave that module.
 *
 * What this class adds is shape: the request bodies it sends and the response
 * documents it reads are the ones `contracts/reader-api.openapi.json` declares,
 * and `ReaderLibraryContractTest` fails the build when a model and that
 * document disagree.
 */
class ReaderLibraryClient(private val api: ReaderApiClient) : ReaderLibraryOperations {

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

    companion object {
        const val LIBRARY_PATH: String = "/v1/reader/library"
        const val PROGRESS_PATH: String = "/v1/reader/progress"
        const val MUTATIONS_PATH: String = "/v1/reader/sync/mutations"
        const val DELTAS_PATH: String = "/v1/reader/sync/deltas"

        /** The capability this module gates every sync request on. */
        const val SYNC_CAPABILITY_KEY: String = "reader.sync.v1"

        /** The cursor a client that has never synced starts from. */
        const val FIRST_CURSOR: String = "0"

        /** The document's own default page size. */
        const val DEFAULT_DELTA_LIMIT: Int = 100
        const val MIN_DELTA_LIMIT: Int = 1
        const val MAX_DELTA_LIMIT: Int = 500

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
    }
}
