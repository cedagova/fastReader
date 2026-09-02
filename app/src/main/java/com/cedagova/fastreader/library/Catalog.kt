package com.cedagova.fastreader.library

import com.cedagova.fastreader.content.ContentPipelineVersion
import com.cedagova.fastreader.library.store.CatalogSchema
import com.cedagova.fastreader.settings.ReaderSettings
import com.cedagova.fastreader.timing.RsvpTiming
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
    /**
     * Books the reader removed from the library. A removed book that still sits
     * inside an added folder would otherwise come straight back on the next
     * rescan; keeping the decision here makes removal stick until the reader
     * picks the file again or re-adds its folder.
     */
    val removedBookIds: Set<String> = emptySet(),
    /**
     * The book the reader was last in, so launch can go straight back to it
     * (REQ-009). Kept even when that book is currently missing or its permission
     * was lost: the launch routing needs to know *which* book to explain.
     */
    val lastReadBookId: String? = null,
    /**
     * How the reader wants books presented (REQ-020 to REQ-023), stored with the
     * library rather than beside it so one document, one write and one migration
     * chain cover everything the app remembers. Absent in a document written
     * before schema 3; every field then reads back as its documented default.
     */
    val settings: ReaderSettings = ReaderSettings(),
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
    /**
     * True when the file looks untouched since it was last inspected, which is
     * what lets a rescan skip re-parsing it. A provider that reports neither a
     * size nor a modification time gives nothing to compare, so such a file is
     * always re-inspected rather than assumed unchanged.
     */
    fun matchesFingerprint(sizeBytes: Long, lastModifiedEpochMs: Long): Boolean {
        val hasSignal = sizeBytes > 0 || lastModifiedEpochMs > 0
        return hasSignal && this.sizeBytes == sizeBytes && this.lastModifiedEpochMs == lastModifiedEpochMs
    }
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
 * Where the reader is in one book, and how fast it was going (REQ-016).
 *
 * A bare index would not survive the app it is stored by. [tokenIndex] addresses
 * the AD-4 token stream, and that stream is produced by rules that may change, so
 * the two facts that make the index *mean* something are stored beside it:
 *
 * - [bookDigest] — the content-derived identity (AD-2) the position was taken in.
 *   The map key is that same digest today, but storing it makes a position
 *   self-describing rather than only meaningful in the slot it happens to sit in.
 * - [pipelineVersion] — the tokenization rules the index counts (AD-3). When they
 *   change, the stored index points at a different word; the reader detects that
 *   and falls back to [progressFraction] instead of silently resuming somewhere
 *   else. See `com.cedagova.fastreader.reader.ReaderPosition`.
 */
@Serializable
data class ReadingState(
    val bookDigest: String = "",
    /** Index into the book's token stream — the position itself. */
    val tokenIndex: Int = 0,
    /** The tokenization rules [tokenIndex] counts, so a later change is detectable. */
    val pipelineVersion: Int = ContentPipelineVersion.CURRENT,
    /** Share of the book already shown, `0f..1f`. The library's "% read", and the
     *  fallback position when [pipelineVersion] no longer matches. */
    val progressFraction: Float = 0f,
    /** Reading speed in this book, so resuming restores the pace too (REQ-016). */
    val wpm: Int = RsvpTiming.DEFAULT_WPM,
    val updatedAtEpochMs: Long = 0,
) {
    /** True once the last token of the book has been shown (REQ-018's library 100%). */
    val isFinished: Boolean get() = progressFraction >= 1f
}
