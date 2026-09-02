package com.cedagova.fastreader.library

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * In-memory stand-in for the Storage Access Framework.
 *
 * Models the three things the catalog actually depends on: a document's bytes and
 * fingerprint, whether a grant is still held, and what a folder currently
 * contains. That is enough to drive add, rescan, dedup, missing and
 * permission-lost transitions without a device.
 */
class FakeDocumentGateway : DocumentGateway {

    class Document(
        var bytes: ByteArray,
        var displayName: String,
        var lastModifiedEpochMs: Long = 1_000,
    ) {
        /** Set to -1 to model a provider that reports no size column. */
        var sizeOverride: Long? = null

        val sizeBytes: Long get() = sizeOverride ?: bytes.size.toLong()
    }

    /** uri -> document. Removing an entry is the file disappearing. */
    val documents = LinkedHashMap<String, Document>()

    /** tree uri -> uris of the documents inside it, at any depth. */
    val folders = LinkedHashMap<String, MutableList<String>>()

    /** Trees that no longer resolve at all, as after the folder was renamed. */
    val missingFolders = mutableSetOf<String>()

    /** Grants the reader revoked; every uri under them becomes inaccessible. */
    val revokedGrants = mutableSetOf<String>()

    val persistedGrants = mutableSetOf<String>()
    val releasedGrants = mutableListOf<String>()

    fun putDocument(uri: String, bytes: ByteArray, displayName: String, lastModifiedEpochMs: Long = 1_000) {
        documents[uri] = Document(bytes, displayName, lastModifiedEpochMs)
    }

    fun putIntoFolder(
        treeUri: String,
        uri: String,
        bytes: ByteArray,
        displayName: String,
        lastModifiedEpochMs: Long = 1_000,
    ) {
        putDocument(uri, bytes, displayName, lastModifiedEpochMs)
        folders.getOrPut(treeUri) { mutableListOf() }.add(uri)
    }

    override fun persistReadPermission(uri: String, isTree: Boolean): Boolean {
        persistedGrants += uri
        return true
    }

    override fun releaseReadPermission(uri: String, isTree: Boolean) {
        persistedGrants -= uri
        releasedGrants += uri
    }

    override fun displayName(uri: String): String? = documents[uri]?.displayName

    override fun lookup(uri: String): DocumentLookup = when {
        isRevoked(uri) -> DocumentLookup.PermissionLost
        else -> documents[uri]?.let {
            DocumentLookup.Found(DocumentRef(uri, it.displayName, it.sizeBytes, it.lastModifiedEpochMs))
        } ?: DocumentLookup.Missing
    }

    override fun listEpubs(treeUri: String): FolderListing = when {
        isRevoked(treeUri) -> FolderListing.PermissionLost
        treeUri in missingFolders -> FolderListing.Missing
        else -> FolderListing.Listed(
            folders[treeUri]
                .orEmpty()
                .mapNotNull { uri ->
                    documents[uri]?.let {
                        DocumentRef(uri, it.displayName, it.sizeBytes, it.lastModifiedEpochMs)
                    }
                },
        )
    }

    override fun open(uri: String): InputStream {
        if (isRevoked(uri)) throw IOException("access to the file was revoked")
        val document = documents[uri] ?: throw IOException("no such document $uri")
        return ByteArrayInputStream(document.bytes)
    }

    private fun isRevoked(uri: String): Boolean =
        revokedGrants.any { granted -> uri == granted || uri.startsWith("$granted/") }
}
