package com.cedagova.reader.library.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One entry of `GET /v1/reader/capabilities`. The module reads exactly one of
 * them — `reader.sync.v1` — and ignores the rest.
 */
@Serializable
data class ReaderCapabilityEntry(
    @SerialName("key") val key: ReaderCapabilityKey = ReaderCapabilityKey.UNKNOWN,
    @SerialName("availability") val availability: ReaderCapabilityAvailability = ReaderCapabilityAvailability.UNKNOWN,
    @SerialName("reason") val reason: ReaderCapabilityReason = ReaderCapabilityReason.UNKNOWN,
    @SerialName("quota") val quota: ReaderCapabilityQuota? = null,
    @SerialName("actorState") val actorState: JsonObject = EMPTY_JSON_OBJECT,
)

/** A capability's quota, when it has one. `reader.sync.v1` does not. */
@Serializable
data class ReaderCapabilityQuota(
    @SerialName("limit") val limit: Long,
    @SerialName("used") val used: Long,
    @SerialName("remaining") val remaining: Long,
    @SerialName("resetsAt") val resetsAt: String,
)

/**
 * The module's answer to "may this client sync right now?".
 *
 * A capabilities document with no `reader.sync.v1` entry reads as unavailable
 * with reason [ReaderCapabilityReason.UNKNOWN] — never as available, and never
 * as an error: an older or differently configured server simply does not offer
 * the capability, and the caller defers sync with that reason shown.
 */
data class ReaderSyncCapability(
    val availability: ReaderCapabilityAvailability,
    val reason: ReaderCapabilityReason,
    val quota: ReaderCapabilityQuota? = null,
    /** False when the document carried no `reader.sync.v1` entry at all. */
    val declared: Boolean = true,
) {
    val isAvailable: Boolean get() = availability == ReaderCapabilityAvailability.AVAILABLE

    companion object {
        /** What a document without a `reader.sync.v1` entry reads as. */
        val UNDECLARED: ReaderSyncCapability = ReaderSyncCapability(
            availability = ReaderCapabilityAvailability.UNAVAILABLE,
            reason = ReaderCapabilityReason.UNKNOWN,
            quota = null,
            declared = false,
        )
    }
}
