package com.cedagova.fastreader.library

import com.cedagova.fastreader.epub.EpubByteSource
import com.cedagova.fastreader.epub.EpubInspection
import com.cedagova.fastreader.epub.EpubInspector
import com.cedagova.fastreader.epub.EpubRejectReason
import com.cedagova.fastreader.library.store.CoverStore

/** What one ingestion pass changed. */
data class IngestOutcome(
    val catalog: Catalog,
    val added: Int = 0,
    val updated: Int = 0,
    val rejected: Int = 0,
    val unavailable: Int = 0,
)

/** Reports scan progress so the library can show a loading state. */
fun interface ScanProgress {
    fun onProgress(processed: Int, total: Int, currentName: String?)

    companion object {
        val None = ScanProgress { _, _, _ -> }
    }
}

/**
 * Turns picked files and added folders into catalog entries.
 *
 * Pure with respect to the catalog: every method takes the current [Catalog] and
 * returns the next one, which keeps ingestion, dedup, and the missing /
 * permission-lost transitions testable without Android.
 */
class CatalogIngestor(
    private val gateway: DocumentGateway,
    private val covers: CoverStore,
    private val inspect: (EpubByteSource) -> EpubInspection = EpubInspector::inspect,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Adds individually picked EPUB files (REQ-001). */
    fun addPickedBooks(
        catalog: Catalog,
        uris: List<String>,
        progress: ScanProgress = ScanProgress.None,
    ): IngestOutcome {
        val working = Working(catalog, clock())
        uris.forEachIndexed { index, uri ->
            gateway.persistReadPermission(uri, isTree = false)
            progress.onProgress(index, uris.size, null)
            when (val lookup = gateway.lookup(uri)) {
                is DocumentLookup.Found -> working.ingest(lookup.ref, SourceOrigin.DIRECT_PICK, folderId = null)
                DocumentLookup.Missing, DocumentLookup.PermissionLost -> working.unavailable++
            }
        }
        progress.onProgress(uris.size, uris.size, null)
        return working.finish()
    }

    /** Adds a folder and every `.epub` under it, recursively (REQ-002). */
    fun addFolder(
        catalog: Catalog,
        treeUri: String,
        displayName: String,
        progress: ScanProgress = ScanProgress.None,
    ): IngestOutcome {
        gateway.persistReadPermission(treeUri, isTree = true)
        val now = clock()
        val working = Working(catalog, now)
        val existing = catalog.folder(treeUri)
        val listing = gateway.listEpubs(treeUri)
        val status = when (listing) {
            is FolderListing.Listed -> FolderStatus.AVAILABLE
            FolderListing.Missing -> FolderStatus.MISSING
            FolderListing.PermissionLost -> FolderStatus.PERMISSION_LOST
        }
        working.folders[treeUri] = BookFolder(
            id = treeUri,
            treeUri = treeUri,
            displayName = displayName,
            status = status,
            addedAtEpochMs = existing?.addedAtEpochMs ?: now,
            lastScannedEpochMs = now,
        )
        if (listing is FolderListing.Listed) {
            listing.documents.forEachIndexed { index, ref ->
                progress.onProgress(index, listing.documents.size, ref.displayName)
                working.ingest(ref, SourceOrigin.FOLDER, folderId = treeUri)
            }
            progress.onProgress(listing.documents.size, listing.documents.size, null)
        }
        return working.finish()
    }

    /**
     * Re-reads every added folder and every picked file: new books appear, gone
     * files become missing, and revoked grants become permission-lost.
     * Unchanged files are recognised by size and modification time and are not
     * re-parsed, which is what keeps a large folder cheap to rescan.
     */
    fun rescan(catalog: Catalog, progress: ScanProgress = ScanProgress.None): IngestOutcome {
        val now = clock()
        val working = Working(catalog, now)
        val work = ArrayList<WorkItem>()

        for (folder in catalog.folders) {
            when (val listing = gateway.listEpubs(folder.treeUri)) {
                is FolderListing.Listed -> {
                    working.folders[folder.id] =
                        folder.copy(status = FolderStatus.AVAILABLE, lastScannedEpochMs = now)
                    listing.documents.forEach { work += WorkItem(it, SourceOrigin.FOLDER, folder.id) }
                    val present = listing.documents.mapTo(HashSet()) { it.uri }
                    working.markSources(
                        availability = SourceAvailability.MISSING,
                        predicate = { source ->
                            source.origin == SourceOrigin.FOLDER &&
                                source.folderId == folder.id &&
                                source.uri !in present
                        },
                    )
                }

                FolderListing.Missing -> {
                    working.folders[folder.id] = folder.copy(status = FolderStatus.MISSING)
                    working.markFolderSources(folder.id, SourceAvailability.MISSING)
                }

                FolderListing.PermissionLost -> {
                    working.folders[folder.id] = folder.copy(status = FolderStatus.PERMISSION_LOST)
                    working.markFolderSources(folder.id, SourceAvailability.PERMISSION_LOST)
                }
            }
        }

        val pickedUris = catalog.books
            .flatMap { book -> book.sources.filter { it.origin == SourceOrigin.DIRECT_PICK } }
            .map { it.uri }
            .distinct()
        for (uri in pickedUris) {
            when (val lookup = gateway.lookup(uri)) {
                is DocumentLookup.Found -> work += WorkItem(lookup.ref, SourceOrigin.DIRECT_PICK, null)
                DocumentLookup.Missing ->
                    working.markSources(SourceAvailability.MISSING) { it.uri == uri }

                DocumentLookup.PermissionLost ->
                    working.markSources(SourceAvailability.PERMISSION_LOST) { it.uri == uri }
            }
        }

        work.forEachIndexed { index, item ->
            progress.onProgress(index, work.size, item.ref.displayName)
            working.ingest(item.ref, item.origin, item.folderId)
        }
        progress.onProgress(work.size, work.size, null)
        return working.finish()
    }

    /** Removes a catalog entry. The file is never touched and the position is kept (REQ-004). */
    fun removeBook(catalog: Catalog, bookId: String): Catalog {
        val book = catalog.book(bookId) ?: return catalog
        book.sources
            .filter { it.origin == SourceOrigin.DIRECT_PICK }
            .forEach { gateway.releaseReadPermission(it.uri, isTree = false) }
        return catalog.copy(books = catalog.books.filterNot { it.id == bookId })
    }

    /** Removes an added folder and the entries only it provided. Files are never touched. */
    fun removeFolder(catalog: Catalog, folderId: String): Catalog {
        val folder = catalog.folder(folderId) ?: return catalog
        gateway.releaseReadPermission(folder.treeUri, isTree = true)
        val books = catalog.books
            .map { book -> book.copy(sources = book.sources.filterNot { it.folderId == folderId }) }
            .filter { it.sources.isNotEmpty() }
        return catalog.copy(books = books, folders = catalog.folders.filterNot { it.id == folderId })
    }

    private class WorkItem(val ref: DocumentRef, val origin: SourceOrigin, val folderId: String?)

    private inner class Working(private val original: Catalog, val now: Long) {
        val books = LinkedHashMap<String, Book>().apply { original.books.forEach { put(it.id, it) } }
        val folders = LinkedHashMap<String, BookFolder>().apply { original.folders.forEach { put(it.id, it) } }

        /** Source URI to book id, so scanning a large folder stays linear in the number of files. */
        private val bookIdByUri = HashMap<String, String>().apply {
            original.books.forEach { book -> book.sources.forEach { put(it.uri, book.id) } }
        }

        var added = 0
        var updated = 0
        var rejected = 0
        var unavailable = 0

        fun finish(): IngestOutcome = IngestOutcome(
            catalog = original.copy(books = books.values.toList(), folders = folders.values.toList()),
            added = added,
            updated = updated,
            rejected = rejected,
            unavailable = unavailable,
        )

        fun markFolderSources(folderId: String, availability: SourceAvailability) {
            markSources(availability) { it.origin == SourceOrigin.FOLDER && it.folderId == folderId }
        }

        fun markSources(availability: SourceAvailability, predicate: (BookSource) -> Boolean) {
            for ((id, book) in books.entries.toList()) {
                var changed = false
                val sources = book.sources.map { source ->
                    if (predicate(source) && source.availability != availability) {
                        changed = true
                        source.copy(availability = availability)
                    } else {
                        source
                    }
                }
                if (changed) books[id] = book.copy(sources = sources)
            }
        }

        fun ingest(ref: DocumentRef, origin: SourceOrigin, folderId: String?) {
            val holderId = bookIdByUri[ref.uri]
            val holder = holderId?.let { id -> books[id]?.let { id to it } }
            val knownSource = holder?.second?.sources?.firstOrNull { it.uri == ref.uri }

            // Unchanged file: no need to re-open or re-parse it.
            if (holder != null && knownSource != null &&
                knownSource.matchesFingerprint(ref.sizeBytes, ref.lastModifiedEpochMs)
            ) {
                val refreshed = holder.second.copy(
                    sources = holder.second.sources.map { source ->
                        if (source.uri == ref.uri) {
                            source.copy(
                                availability = SourceAvailability.AVAILABLE,
                                displayName = ref.displayName,
                                folderId = folderId ?: source.folderId,
                            )
                        } else {
                            source
                        }
                    },
                    lastSeenEpochMs = now,
                )
                if (refreshed != holder.second) updated++
                books[holder.first] = refreshed
                return
            }

            val inspection = inspect(EpubByteSource { gateway.open(ref.uri) })
            val digest = inspection.contentDigest
            if (digest == null) {
                // The bytes could not be read at all: an access problem, not a bad book.
                val availability = when (gateway.lookup(ref.uri)) {
                    DocumentLookup.PermissionLost -> SourceAvailability.PERMISSION_LOST
                    else -> SourceAvailability.MISSING
                }
                markSources(availability) { it.uri == ref.uri }
                unavailable++
                return
            }

            // The file at this URI may have been replaced by different content.
            if (holder != null && holder.first != digest) {
                val stripped = holder.second.copy(sources = holder.second.sources.filterNot { it.uri == ref.uri })
                if (stripped.sources.isEmpty()) {
                    books.remove(holder.first)
                    stripped.sources.forEach { bookIdByUri.remove(it.uri) }
                } else {
                    books[holder.first] = stripped
                }
            }

            val source = BookSource(
                uri = ref.uri,
                origin = origin,
                displayName = ref.displayName,
                folderId = folderId,
                sizeBytes = ref.sizeBytes,
                lastModifiedEpochMs = ref.lastModifiedEpochMs,
                availability = SourceAvailability.AVAILABLE,
            )
            val existing = books[digest]
            val fallbackTitle = ref.displayName.substringBeforeLast('.', ref.displayName).ifBlank { ref.displayName }

            val book = when (inspection) {
                is EpubInspection.Readable -> {
                    if (existing?.hasCover != true) {
                        inspection.cover?.let { covers.write(digest, it.bytes) }
                    }
                    val hasCover = existing?.hasCover == true || inspection.cover != null
                    Book(
                        id = digest,
                        title = inspection.metadata.title ?: fallbackTitle,
                        author = inspection.metadata.author,
                        language = inspection.metadata.language,
                        publicationId = inspection.metadata.publicationId,
                        hasCover = hasCover,
                        contentStatus = BookContentStatus.READABLE,
                        rejectReason = null,
                        rejectDetail = null,
                        sources = existing.mergeSources(source),
                        addedAtEpochMs = existing?.addedAtEpochMs ?: now,
                        lastSeenEpochMs = now,
                    )
                }

                is EpubInspection.Rejected -> {
                    rejected++
                    Book(
                        id = digest,
                        title = existing?.title ?: fallbackTitle,
                        author = existing?.author,
                        language = existing?.language,
                        publicationId = existing?.publicationId,
                        hasCover = existing?.hasCover == true,
                        contentStatus = inspection.reason.toContentStatus(),
                        rejectReason = inspection.reason.name,
                        rejectDetail = inspection.detail,
                        sources = existing.mergeSources(source),
                        addedAtEpochMs = existing?.addedAtEpochMs ?: now,
                        lastSeenEpochMs = now,
                    )
                }
            }

            if (existing == null) added++ else if (existing != book) updated++
            books[digest] = book
            book.sources.forEach { bookIdByUri[it.uri] = digest }
        }

        /** Keeps every other place this book is reachable from, so a duplicate add never duplicates the entry. */
        private fun Book?.mergeSources(source: BookSource): List<BookSource> =
            (this?.sources.orEmpty().filterNot { it.uri == source.uri }) + source
    }

    private fun EpubRejectReason.toContentStatus(): BookContentStatus = when (this) {
        EpubRejectReason.DRM_PROTECTED -> BookContentStatus.DRM_PROTECTED
        EpubRejectReason.CORRUPT_ARCHIVE,
        EpubRejectReason.INVALID_STRUCTURE,
        EpubRejectReason.UNREADABLE,
        -> BookContentStatus.CORRUPT
    }
}
