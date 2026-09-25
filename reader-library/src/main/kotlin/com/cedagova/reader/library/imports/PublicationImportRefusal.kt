package com.cedagova.reader.library.imports

import com.cedagova.reader.library.model.PublicationFailureCategory
import com.cedagova.reader.library.model.PublicationFormat

/**
 * A refusal this module reaches on its own, from the policy, before any
 * admission exists.
 *
 * Every one of these is a decision the policy already published, so making the
 * round trip only to be told `422 too_large` would be a worse version of the
 * same answer — it would have uploaded nothing but it would still have created
 * traffic the owner did not ask for. [category] is how a host shows the same
 * wording it shows for the backend's own refusal (REQ-507): the client's
 * pre-check and the server's verdict are the same category, never two
 * vocabularies.
 *
 * Every bound quoted here is read from the policy on this attempt. Nothing in
 * this file is a constant.
 */
public sealed interface PublicationImportRefusal {

    /**
     * The category a host renders this as, or null when the refusal is not a
     * failure of *this file* at all.
     */
    public val category: PublicationFailureCategory?

    /**
     * `enabled: false` — this deployment admits no imports from anyone.
     *
     * Not a failure of the book: the action itself is off, and a host hides it
     * with the reason rather than offering something that cannot work.
     */
    public data class ImportsDisabled(
        /** reader-api's echoed request id, for reading a server log against what the screen showed. */
        val requestId: String,
    ) : PublicationImportRefusal {
        override val category: PublicationFailureCategory? get() = null
    }

    /** The policy lists no format for this source's MIME type. */
    public data class FormatNotSupported(
        val mimeType: String,
        /** Every MIME type the policy did declare, in its own order. */
        val supportedMimeTypes: List<String>,
    ) : PublicationImportRefusal {
        override val category: PublicationFailureCategory
            get() = PublicationFailureCategory.UNSUPPORTED
    }

    /** Above the cap this format publishes. [maxSourceBytes] is the policy's number, quoted. */
    public data class TooLarge(val format: PublicationFormat, val sizeBytes: Long, val maxSourceBytes: Long) :
        PublicationImportRefusal {
        override val category: PublicationFailureCategory
            get() = PublicationFailureCategory.TOO_LARGE
    }

    /** A zero-byte source. `size_bytes` is `> 0` in the contract; there is nothing to send. */
    public data object SourceEmpty : PublicationImportRefusal {
        override val category: PublicationFailureCategory
            get() = PublicationFailureCategory.MALFORMED
    }
}
