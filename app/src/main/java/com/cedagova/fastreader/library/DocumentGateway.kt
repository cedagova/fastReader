package com.cedagova.fastreader.library

import java.io.InputStream

/** A file the reader granted access to, as the platform describes it. */
data class DocumentRef(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long = -1,
    val lastModifiedEpochMs: Long = 0,
)

/** Outcome of asking the platform about one document. */
sealed interface DocumentLookup {
    data class Found(val ref: DocumentRef) : DocumentLookup

    /** The document is gone — deleted, renamed, or its folder moved. */
    data object Missing : DocumentLookup

    /** Access was revoked; the reader can grant it again. */
    data object PermissionLost : DocumentLookup
}

/** Outcome of listing an added folder. */
sealed interface FolderListing {
    data class Listed(val documents: List<DocumentRef>) : FolderListing

    data object Missing : FolderListing

    data object PermissionLost : FolderListing
}

/**
 * The document-access boundary the catalog depends on.
 *
 * Keeping it behind an interface with plain string URIs is what lets ingestion,
 * dedup, rescan, and the missing/permission-lost states be proven by fast JVM
 * tests; the Storage Access Framework implementation lives in
 * [com.cedagova.fastreader.library.saf.SafDocumentGateway].
 */
interface DocumentGateway {

    /** Takes a long-lived read grant for a picked document or folder tree. */
    fun persistReadPermission(uri: String, isTree: Boolean): Boolean

    /** Gives back a grant the catalog no longer needs. */
    fun releaseReadPermission(uri: String, isTree: Boolean)

    fun lookup(uri: String): DocumentLookup

    /** The platform's name for a document or folder, when it has one. */
    fun displayName(uri: String): String?

    /** Every `.epub` under [treeUri], including subfolders. */
    fun listEpubs(treeUri: String): FolderListing

    /** Opens the document for reading. Callers close the stream. */
    @Throws(java.io.IOException::class)
    fun open(uri: String): InputStream
}
