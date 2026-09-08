package com.cedagova.fastreader.content

/**
 * A book's content-derived identity: the SHA-256 of the whole file, as
 * `sha256:<64 lowercase hex>` (v1 AD-2).
 *
 * Unchanged from v1 in every way that is persisted — it is still the `Book.id`
 * in the catalog, still the key of `Catalog.readingStates`, and still what
 * `ReadingState.bookDigest` records. What changed in v1.1.0 is *who computes it*
 * (AD-8): ingestion computes it once, when the file is added, and the reader
 * takes it as an input. Nothing on the open path hashes a file, which is what
 * makes opening cost the book's text rather than its size (REQ-110).
 *
 * Wrapped in a type so "the digest" cannot be confused with a catalog id, a URI,
 * or a title at a call site — they are all strings.
 */
@JvmInline
value class BookIdentity(val value: String) {

    override fun toString(): String = value

    companion object {
        /** Builds an identity from a bare hex digest, adding the stored `sha256:` prefix. */
        fun ofSha256Hex(hex: String) = BookIdentity("sha256:$hex")
    }
}
