package com.cedagova.reader.library.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One entry of `GET /v1/reader/capabilities`. The module reads two of them —
 * `reader.sync.v1` and `reader.publication-import.v1` — and ignores the rest.
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

/**
 * The module's answer to "may this client offer to add a device book to the
 * account right now?" (`reader.publication-import.v1`, core.md §6 and §7.6).
 *
 * The rule is the contract's, word for word: offer import only when the document
 * holds **exactly one** `reader.publication-import.v1` entry and its
 * `availability` is `available`. A missing entry ([entries] = 0) and a
 * duplicated one ([entries] > 1) are both "not permission to import", and
 * neither is an error — they read as unavailable with reason
 * [ReaderCapabilityReason.UNKNOWN], so the host shows its generic sentence.
 *
 * Discovery reserves nothing and carries no numbers: its `quota` is always null,
 * and caps and formats still come from `GET /reader/v1/imports/policy` on every
 * attempt. Admission stays authoritative — this only decides whether the action
 * is offered.
 */
data class ReaderPublicationImportCapability(
    val availability: ReaderCapabilityAvailability,
    val reason: ReaderCapabilityReason,
    /** How many `reader.publication-import.v1` entries the document carried. */
    val entries: Int,
) {
    val isAvailable: Boolean
        get() = entries == 1 && availability == ReaderCapabilityAvailability.AVAILABLE

    companion object {
        /** A document with no entry at all, or one with more than one: never permission. */
        fun undeclared(entries: Int): ReaderPublicationImportCapability = ReaderPublicationImportCapability(
            availability = ReaderCapabilityAvailability.UNAVAILABLE,
            reason = ReaderCapabilityReason.UNKNOWN,
            entries = entries,
        )
    }
}
