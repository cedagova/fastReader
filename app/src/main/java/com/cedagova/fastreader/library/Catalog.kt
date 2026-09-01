package com.cedagova.fastreader.library

import com.cedagova.fastreader.library.store.CatalogSchema
import kotlinx.serialization.Serializable

/**
 * The on-device book catalog.
 *
 * These types are both the API the library UI consumes and the persisted schema
 * (AD-3): every field is written to disk, so changing one is a schema change that
 * needs a migration in [com.cedagova.fastreader.library.store.CatalogSchema].
 */
@Serializable
data class Catalog(
    val schemaVersion: Int = CatalogSchema.CURRENT_VERSION,
    val books: List<Book> = emptyList(),
    val folders: List<BookFolder> = emptyList(),
    /**
     * Reading positions keyed by book id, kept independently of [books] so that
     * removing a book from the library preserves its position for a later
     * re-add (REQ-004) and a missing book keeps it while gone (REQ-005).
     */
    val readingStates: Map<String, ReadingState> = emptyMap(),
) {
    fun book(id: String): Book? = books.firstOrNull { it.id == id }

    fun folder(id: String): BookFolder? = folders.firstOrNull { it.id == id }
}

/** A book in the catalog, identified by content rather than by where it lives (AD-2). */
@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String? = null,
    val language: String? = null,
    val publicationId: String? = null,
    val hasCover: Boolean = false,
    /** What inspecting the file concluded about the book itself; durable across availability changes. */
    val contentStatus: BookContentStatus = BookContentStatus.READABLE,
    /** Distinct machine-readable rejection code, persisted so the UI can explain a rejected book. */
    val rejectReason: String? = null,
    /** Plain-language detail behind [rejectReason]. */
    val rejectDetail: String? = null,
    /** Every place this exact book is reachable from; more than one means it was added twice. */
    val sources: List<BookSource> = emptyList(),
    val addedAtEpochMs: Long = 0,
    val lastSeenEpochMs: Long = 0,
) {
    /**
     * The state the library shows. Availability wins over the content verdict: a
     * DRM-protected book whose folder disappeared is reported as gone, and
     * [rejectReason] still explains why it was unusable once it comes back.
     */
    val status: BookStatus
        get() {
            if (sources.none { it.availability == SourceAvailability.AVAILABLE }) {
                return if (sources.any { it.availability == SourceAvailability.PERMISSION_LOST }) {
                    BookStatus.PERMISSION_LOST
                } else {
                    BookStatus.MISSING
                }
            }
            return when (contentStatus) {
                BookContentStatus.READABLE -> BookStatus.READABLE
                BookContentStatus.DRM_PROTECTED -> BookStatus.DRM_PROTECTED
                BookContentStatus.CORRUPT -> BookStatus.CORRUPT
            }
        }

    /** The source to read this book from, or null when none is currently reachable. */
    val readableSource: BookSource?
        get() = sources.firstOrNull { it.availability == SourceAvailability.AVAILABLE }

    /** File names this book is reachable under; the library search matches on them (REQ-003). */
    val fileNames: List<String> get() = sources.map { it.displayName }.distinct()
}

/** What inspecting the file itself concluded. */
enum class BookContentStatus { READABLE, DRM_PROTECTED, CORRUPT }

/** The state the library presents for a book. */
enum class BookStatus { READABLE, DRM_PROTECTED, CORRUPT, MISSING, PERMISSION_LOST }

/** One place a book is reachable from. Files are read here in place and never copied (AD-1). */
@Serializable
data class BookSource(
    val uri: String,
    val origin: SourceOrigin,
    val displayName: String,
    val folderId: String? = null,
    val sizeBytes: Long = -1,
    val lastModifiedEpochMs: Long = 0,
    val availability: SourceAvailability = SourceAvailability.AVAILABLE,
) {
    /** True when the file looks untouched since it was last inspected. */
    fun matchesFingerprint(sizeBytes: Long, lastModifiedEpochMs: Long): Boolean =
        this.sizeBytes == sizeBytes && this.lastModifiedEpochMs == lastModifiedEpochMs
}

enum class SourceOrigin { DIRECT_PICK, FOLDER }

enum class SourceAvailability { AVAILABLE, MISSING, PERMISSION_LOST }

/** A folder the reader added; its EPUBs are discovered recursively on every rescan. */
@Serializable
data class BookFolder(
    val id: String,
    val treeUri: String,
    val displayName: String,
    val status: FolderStatus = FolderStatus.AVAILABLE,
    val addedAtEpochMs: Long = 0,
    val lastScannedEpochMs: Long = 0,
)

enum class FolderStatus { AVAILABLE, MISSING, PERMISSION_LOST }

/**
 * Per-book reading position and progress slot. This leaf only stores and returns
 * it; the reader (increment 002) is what writes real values into it.
 */
@Serializable
data class ReadingState(
    val spineIndex: Int = 0,
    val wordIndex: Int = 0,
    val progressFraction: Float = 0f,
    val updatedAtEpochMs: Long = 0,
)
